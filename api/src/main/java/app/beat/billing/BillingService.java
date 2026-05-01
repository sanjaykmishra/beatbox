package app.beat.billing;

import app.beat.workspace.Workspace;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.billingportal.SessionCreateParams;
import com.stripe.param.checkout.SessionCreateParams.LineItem;
import com.stripe.param.checkout.SessionCreateParams.Mode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper over the Stripe SDK. All Stripe-specific concerns live here so controllers stay
 * agnostic. Configured-or-stub: when STRIPE_SECRET_KEY is empty, the controller can detect this and
 * return 503 instead of crashing — keeps local dev viable without a Stripe account.
 */
@Service
public class BillingService {

  private static final Logger log = LoggerFactory.getLogger(BillingService.class);

  private final String secretKey;
  private final String soloMonthly;
  private final String soloYearly;
  private final String agencyMonthly;
  private final String agencyYearly;
  private final String studioMonthly;
  private final String studioYearly;
  private final String appBaseUrl;

  public BillingService(
      @Value("${STRIPE_SECRET_KEY:}") String secretKey,
      @Value("${STRIPE_PRICE_SOLO_MONTHLY:}") String soloMonthly,
      @Value("${STRIPE_PRICE_SOLO_YEARLY:}") String soloYearly,
      @Value("${STRIPE_PRICE_AGENCY_MONTHLY:}") String agencyMonthly,
      @Value("${STRIPE_PRICE_AGENCY_YEARLY:}") String agencyYearly,
      @Value("${STRIPE_PRICE_STUDIO_MONTHLY:}") String studioMonthly,
      @Value("${STRIPE_PRICE_STUDIO_YEARLY:}") String studioYearly,
      @Value("${APP_BASE_URL:}") String appBaseUrl) {
    this.secretKey = secretKey;
    this.soloMonthly = soloMonthly;
    this.soloYearly = soloYearly;
    this.agencyMonthly = agencyMonthly;
    this.agencyYearly = agencyYearly;
    this.studioMonthly = studioMonthly;
    this.studioYearly = studioYearly;
    this.appBaseUrl = appBaseUrl;
  }

  @PostConstruct
  void init() {
    if (isConfigured()) {
      Stripe.apiKey = secretKey;
      log.info("BillingService configured (live=Stripe API set)");
    } else {
      log.info("BillingService not configured (STRIPE_SECRET_KEY empty)");
    }
  }

  public boolean isConfigured() {
    return secretKey != null && !secretKey.isBlank();
  }

  /** Resolves a (plan, interval) pair to the configured Stripe price ID, or null if unknown. */
  public String priceIdFor(String plan, String interval) {
    if (plan == null || interval == null) return null;
    return switch (plan + ":" + interval) {
      case "solo:monthly" -> soloMonthly;
      case "solo:yearly" -> soloYearly;
      case "agency:monthly" -> agencyMonthly;
      case "agency:yearly" -> agencyYearly;
      case "studio:monthly" -> studioMonthly;
      case "studio:yearly" -> studioYearly;
      default -> null;
    };
  }

  /** Creates a Stripe Checkout Session and returns its hosted URL. */
  public String createCheckoutSession(Workspace ws, String plan, String interval, String userEmail)
      throws StripeException {
    String priceId = priceIdFor(plan, interval);
    if (priceId == null || priceId.isBlank()) {
      throw new IllegalArgumentException("No Stripe price configured for " + plan + "/" + interval);
    }
    var paramsBuilder =
        com.stripe.param.checkout.SessionCreateParams.builder()
            .setMode(Mode.SUBSCRIPTION)
            .setSuccessUrl(appBaseUrl + "/settings?billing=ok")
            .setCancelUrl(appBaseUrl + "/settings?billing=cancelled")
            .addLineItem(LineItem.builder().setPrice(priceId).setQuantity(1L).build())
            .setClientReferenceId(ws.id().toString())
            .putMetadata("workspace_id", ws.id().toString())
            .putMetadata("plan", plan);
    if (ws.stripeCustomerId() != null && !ws.stripeCustomerId().isBlank()) {
      paramsBuilder.setCustomer(ws.stripeCustomerId());
    } else if (userEmail != null && !userEmail.isBlank()) {
      paramsBuilder.setCustomerEmail(userEmail);
    }
    Session session = Session.create(paramsBuilder.build());
    return session.getUrl();
  }

  /** Creates a Customer Portal session for self-serve plan/payment management. */
  public String createPortalSession(Workspace ws) throws StripeException {
    if (ws.stripeCustomerId() == null || ws.stripeCustomerId().isBlank()) {
      throw new IllegalStateException("Workspace has no Stripe customer; complete checkout first");
    }
    var params =
        SessionCreateParams.builder()
            .setCustomer(ws.stripeCustomerId())
            .setReturnUrl(appBaseUrl + "/settings")
            .build();
    return com.stripe.model.billingportal.Session.create(params).getUrl();
  }
}
