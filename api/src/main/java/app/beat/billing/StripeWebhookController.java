package app.beat.billing;

import app.beat.activity.ActivityRecorder;
import app.beat.activity.EventKinds;
import app.beat.auth.UserRepository;
import app.beat.workspace.Workspace;
import app.beat.workspace.WorkspaceRepository;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.Subscription;
import com.stripe.net.Webhook;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stripe webhook receiver. Signature-verified via STRIPE_WEBHOOK_SECRET. Idempotent: re-handling
 * the same event yields the same DB state (we set, never increment). Always returns 200 once we
 * recognize the event so Stripe stops retrying.
 *
 * <p>Handled events per docs/04-api-surface.md §POST /v1/webhooks/stripe:
 *
 * <ul>
 *   <li>checkout.session.completed → activate subscription, set workspace plan
 *   <li>customer.subscription.updated → update plan limits
 *   <li>customer.subscription.deleted → downgrade to trial-expired
 *   <li>invoice.payment_failed → email workspace owner + activity event
 * </ul>
 */
@RestController
public class StripeWebhookController {

  private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

  private final String webhookSecret;
  private final WorkspaceRepository workspaces;
  private final UserRepository users;
  private final EmailService email;
  private final ActivityRecorder activity;
  private final BillingService billing;

  public StripeWebhookController(
      @Value("${STRIPE_WEBHOOK_SECRET:}") String webhookSecret,
      WorkspaceRepository workspaces,
      UserRepository users,
      EmailService email,
      ActivityRecorder activity,
      BillingService billing) {
    this.webhookSecret = webhookSecret;
    this.workspaces = workspaces;
    this.users = users;
    this.email = email;
    this.activity = activity;
    this.billing = billing;
  }

  @PostMapping("/v1/webhooks/stripe")
  public ResponseEntity<String> receive(
      @RequestBody String payload, @RequestHeader("Stripe-Signature") String signature) {
    if (webhookSecret == null || webhookSecret.isBlank()) {
      log.warn("stripe webhook arrived but STRIPE_WEBHOOK_SECRET is not set; dropping");
      return ResponseEntity.status(503).body("billing not configured");
    }
    Event event;
    try {
      event = Webhook.constructEvent(payload, signature, webhookSecret);
    } catch (SignatureVerificationException e) {
      log.warn("stripe webhook: bad signature: {}", e.getMessage());
      return ResponseEntity.status(400).body("bad signature");
    }
    try {
      switch (event.getType()) {
        case "checkout.session.completed" -> handleCheckoutCompleted(event);
        case "customer.subscription.updated" -> handleSubscriptionUpdated(event);
        case "customer.subscription.deleted" -> handleSubscriptionDeleted(event);
        case "invoice.payment_failed" -> handlePaymentFailed(event);
        default -> log.debug("stripe webhook: ignoring {}", event.getType());
      }
    } catch (Exception e) {
      log.warn("stripe webhook: handler threw on {}: {}", event.getType(), e.toString());
      // Return 200 anyway — we don't want Stripe to retry indefinitely. Investigate via logs.
    }
    return ResponseEntity.ok("ok");
  }

  private void handleCheckoutCompleted(Event event) {
    var session = (com.stripe.model.checkout.Session) deserialize(event);
    if (session == null) return;
    String workspaceIdStr =
        session.getMetadata() == null ? null : session.getMetadata().get("workspace_id");
    String plan = session.getMetadata() == null ? null : session.getMetadata().get("plan");
    if (workspaceIdStr == null || plan == null) {
      log.warn("stripe webhook: checkout.session.completed missing metadata");
      return;
    }
    UUID workspaceId = UUID.fromString(workspaceIdStr);
    String stripeCustomerId = session.getCustomer();
    String stripeSubscriptionId = session.getSubscription();
    var limits = PlanLimits.forPlan(plan);
    Workspace updated =
        workspaces.setSubscription(
            workspaceId,
            plan,
            limits.clients(),
            limits.reportsMonthly(),
            stripeCustomerId,
            stripeSubscriptionId);
    activity.recordSystem(
        EventKinds.BILLING_SUBSCRIPTION_ACTIVATED, "workspace", workspaceId, Map.of("plan", plan));
    log.info(
        "stripe webhook: activated plan={} for workspace={} customer={}",
        updated.plan(),
        workspaceId,
        stripeCustomerId);
  }

  private void handleSubscriptionUpdated(Event event) {
    Subscription sub = (Subscription) deserialize(event);
    if (sub == null) return;
    Optional<Workspace> wsOpt = workspaces.findByStripeCustomerId(sub.getCustomer());
    if (wsOpt.isEmpty()) {
      log.warn("stripe webhook: subscription.updated for unknown customer {}", sub.getCustomer());
      return;
    }
    Workspace ws = wsOpt.get();
    String plan = ws.plan();
    if (sub.getItems() != null
        && sub.getItems().getData() != null
        && !sub.getItems().getData().isEmpty()) {
      String priceId = sub.getItems().getData().get(0).getPrice().getId();
      String guess = guessPlanFromPriceId(priceId);
      if (guess != null) plan = guess;
    }
    var limits = PlanLimits.forPlan(plan);
    workspaces.setSubscription(
        ws.id(), plan, limits.clients(), limits.reportsMonthly(), sub.getCustomer(), sub.getId());
    activity.recordSystem(
        EventKinds.BILLING_SUBSCRIPTION_UPDATED,
        "workspace",
        ws.id(),
        Map.of("plan", plan, "status", sub.getStatus()));
  }

  private void handleSubscriptionDeleted(Event event) {
    Subscription sub = (Subscription) deserialize(event);
    if (sub == null) return;
    Optional<Workspace> wsOpt = workspaces.findByStripeCustomerId(sub.getCustomer());
    if (wsOpt.isEmpty()) return;
    Workspace ws = wsOpt.get();
    var limits = PlanLimits.forPlan(PlanLimits.TRIAL);
    workspaces.setSubscription(
        ws.id(),
        PlanLimits.TRIAL,
        limits.clients(),
        limits.reportsMonthly(),
        ws.stripeCustomerId(),
        null);
    activity.recordSystem(
        EventKinds.BILLING_SUBSCRIPTION_CANCELLED, "workspace", ws.id(), Map.of());
    notifyOwner(
        ws,
        "Your Beat subscription was cancelled",
        "Your subscription has been cancelled. You can re-subscribe any time from your workspace settings.");
  }

  private void handlePaymentFailed(Event event) {
    com.stripe.model.Invoice invoice = (com.stripe.model.Invoice) deserialize(event);
    if (invoice == null) return;
    String customerId = invoice.getCustomer();
    if (customerId == null) return;
    Optional<Workspace> wsOpt = workspaces.findByStripeCustomerId(customerId);
    if (wsOpt.isEmpty()) return;
    Workspace ws = wsOpt.get();
    activity.recordSystem(
        EventKinds.BILLING_PAYMENT_FAILED,
        "workspace",
        ws.id(),
        Map.of("invoice_id", invoice.getId() == null ? "" : invoice.getId()));
    notifyOwner(
        ws,
        "Action needed: payment failed for Beat",
        "We couldn't charge your card for the latest invoice. "
            + "Please update your payment method in your workspace settings to keep your subscription active.");
  }

  private void notifyOwner(Workspace ws, String subject, String text) {
    users
        .findOwnerOfWorkspace(ws.id())
        .ifPresent(
            owner ->
                email.sendTransactional(
                    owner.email(),
                    subject,
                    "<p>Hi " + escapeHtml(owner.name()) + ",</p><p>" + escapeHtml(text) + "</p>"));
  }

  private static String escapeHtml(String s) {
    if (s == null) return "";
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private String guessPlanFromPriceId(String priceId) {
    if (priceId == null) return null;
    if (priceId.equals(billing.priceIdFor(PlanLimits.SOLO, "monthly"))
        || priceId.equals(billing.priceIdFor(PlanLimits.SOLO, "yearly"))) return PlanLimits.SOLO;
    if (priceId.equals(billing.priceIdFor(PlanLimits.AGENCY, "monthly"))
        || priceId.equals(billing.priceIdFor(PlanLimits.AGENCY, "yearly")))
      return PlanLimits.AGENCY;
    return null;
  }

  private static Object deserialize(Event event) {
    return event.getDataObjectDeserializer().getObject().orElse(null);
  }
}
