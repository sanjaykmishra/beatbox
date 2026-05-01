package app.beat.render;

import java.util.List;

/**
 * Wire shape for render service /render and /preview. Mirror in render/templates/standard.hbs.
 *
 * <p>{@code base_url} is consumed only by /render to inject {@code <base href>} so relative
 * screenshot URLs (the local-disk fallback shape, {@code /v1/screenshots/...}) resolve from
 * puppeteer's {@code about:blank} context. Production R2 URLs are absolute, so {@code base_url} is
 * unused there and may be null.
 */
public record RenderPayload(
    Branding branding,
    Client client,
    Report report,
    Glance glance,
    List<Highlight> highlights,
    List<Item> coverage_items,
    String base_url) {

  public record Branding(String agency_name, String logo_url, String primary_color) {}

  public record Client(String name, String logo_url, String primary_color) {}

  public record Report(
      String title,
      String period_label,
      String executive_summary,
      List<String> executive_summary_paragraphs) {}

  public record Glance(int total, int tier_1, int outlets, long reach_total, String reach_human) {}

  public record Highlight(
      String headline,
      String lede,
      String publish_date,
      Integer tier,
      String outlet_name,
      String screenshot_url) {}

  public record Item(
      String headline,
      String lede,
      String summary,
      String publish_date,
      Integer tier,
      String sentiment,
      String outlet_name,
      String screenshot_url) {}
}
