package app.beat.billing;

/**
 * Plan limits per docs/01-product-and-users.md §Pricing.
 *
 * <p>Solo: 5 client workspaces, 50 reports/mo. Agency: 15 clients, unlimited reports (modeled as
 * Integer.MAX_VALUE). Trial mirrors Solo.
 */
public final class PlanLimits {

  public static final String TRIAL = "trial";
  public static final String SOLO = "solo";
  public static final String AGENCY = "agency";
  public static final String ENTERPRISE = "enterprise";

  public static final int UNLIMITED = Integer.MAX_VALUE;

  private PlanLimits() {}

  public record Limits(int clients, int reportsMonthly) {}

  public static Limits forPlan(String plan) {
    return switch (plan == null ? TRIAL : plan) {
      case SOLO -> new Limits(5, 50);
      case AGENCY -> new Limits(15, UNLIMITED);
      case ENTERPRISE -> new Limits(UNLIMITED, UNLIMITED);
      default -> new Limits(5, 50); // trial mirrors solo
    };
  }
}
