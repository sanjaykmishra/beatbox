package app.beat.billing;

/**
 * Plan limits per docs/01-product-and-users.md §Pricing and docs/17-phase-1-5-social.md §17.3.
 *
 * <p>Solo: 5 client workspaces, 50 reports/mo, 1 GB asset storage. Agency: 15 clients, unlimited
 * reports, 10 GB asset storage. Trial mirrors Solo.
 */
public final class PlanLimits {

  public static final String TRIAL = "trial";
  public static final String SOLO = "solo";
  public static final String AGENCY = "agency";
  public static final String ENTERPRISE = "enterprise";

  public static final int UNLIMITED = Integer.MAX_VALUE;
  public static final long UNLIMITED_BYTES = Long.MAX_VALUE;

  private static final long GB = 1024L * 1024L * 1024L;

  private PlanLimits() {}

  public record Limits(int clients, int reportsMonthly, long assetStorageBytes) {}

  public static Limits forPlan(String plan) {
    return switch (plan == null ? TRIAL : plan) {
      case SOLO -> new Limits(5, 50, 1L * GB);
      case AGENCY -> new Limits(15, UNLIMITED, 10L * GB);
      case ENTERPRISE -> new Limits(UNLIMITED, UNLIMITED, UNLIMITED_BYTES);
      default -> new Limits(5, 50, 1L * GB); // trial mirrors solo
    };
  }
}
