package app.beat.billing;

import app.beat.activity.ActivityRecorder;
import app.beat.activity.EventKinds;
import app.beat.auth.User;
import app.beat.auth.UserRepository;
import app.beat.infra.AppException;
import app.beat.infra.RequestContext;
import app.beat.workspace.Workspace;
import app.beat.workspace.WorkspaceRepository;
import com.stripe.exception.StripeException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class BillingController {

  private final BillingService billing;
  private final WorkspaceRepository workspaces;
  private final UserRepository users;
  private final ActivityRecorder activity;

  public BillingController(
      BillingService billing,
      WorkspaceRepository workspaces,
      UserRepository users,
      ActivityRecorder activity) {
    this.billing = billing;
    this.workspaces = workspaces;
    this.users = users;
    this.activity = activity;
  }

  public record BillingDto(
      String plan,
      int plan_limit_clients,
      int plan_limit_reports_monthly,
      Instant trial_ends_at,
      String stripe_customer_id,
      String stripe_subscription_id,
      boolean stripe_configured) {}

  public record CheckoutRequest(
      @NotBlank @Pattern(regexp = "solo|agency") String plan,
      @NotBlank @Pattern(regexp = "monthly|yearly") String interval) {}

  public record CheckoutResponse(String checkout_url) {}

  public record PortalResponse(String portal_url) {}

  @GetMapping("/v1/billing")
  public BillingDto current(HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    Workspace ws =
        workspaces
            .findById(ctx.workspaceId())
            .orElseThrow(() -> AppException.notFound("Workspace"));
    return new BillingDto(
        ws.plan(),
        ws.planLimitClients(),
        ws.planLimitReportsMonthly(),
        ws.trialEndsAt(),
        ws.stripeCustomerId(),
        ws.stripeSubscriptionId(),
        billing.isConfigured());
  }

  @PostMapping("/v1/billing/checkout")
  public CheckoutResponse checkout(
      @Valid @RequestBody CheckoutRequest body, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    if (!billing.isConfigured()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Billing not configured");
    }
    Workspace ws =
        workspaces
            .findById(ctx.workspaceId())
            .orElseThrow(() -> AppException.notFound("Workspace"));
    User user = users.findById(ctx.userId()).orElse(null);
    String email = user == null ? null : user.email();
    try {
      String url = billing.createCheckoutSession(ws, body.plan(), body.interval(), email);
      activity.recordUser(
          ws.id(),
          ctx.userId(),
          EventKinds.BILLING_CHECKOUT_STARTED,
          "workspace",
          ws.id(),
          Map.of("plan", body.plan(), "interval", body.interval()));
      return new CheckoutResponse(url);
    } catch (IllegalArgumentException e) {
      throw AppException.badRequest(
          "/errors/billing-config", "Plan price not configured", e.getMessage());
    } catch (StripeException e) {
      throw AppException.badRequest(
          "/errors/stripe", "Stripe rejected the request", e.getMessage());
    }
  }

  @PostMapping("/v1/billing/portal")
  public PortalResponse portal(HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    if (!billing.isConfigured()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Billing not configured");
    }
    Workspace ws =
        workspaces
            .findById(ctx.workspaceId())
            .orElseThrow(() -> AppException.notFound("Workspace"));
    try {
      String url = billing.createPortalSession(ws);
      activity.recordUser(
          ws.id(), ctx.userId(), EventKinds.BILLING_PORTAL_OPENED, "workspace", ws.id(), Map.of());
      return new PortalResponse(url);
    } catch (IllegalStateException e) {
      throw AppException.badRequest(
          "/errors/no-customer",
          "No Stripe customer",
          "Complete checkout once before opening the portal.");
    } catch (StripeException e) {
      throw AppException.badRequest(
          "/errors/stripe", "Stripe rejected the request", e.getMessage());
    }
  }
}
