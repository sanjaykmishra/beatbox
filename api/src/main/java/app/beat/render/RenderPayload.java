package app.beat.render;

import java.util.List;

/**
 * Wire shape for render service /render and /preview. Mirror in render/templates/standard.hbs.
 *
 * <p>{@code base_url} is consumed only by /render to inject {@code <base href>} so relative
 * screenshot URLs (the local-disk fallback shape, {@code /v1/screenshots/...}) resolve from
 * puppeteer's {@code about:blank} context. Production R2 URLs are absolute, so {@code base_url} is
 * unused there and may be null.
 *
 * <p><b>Social mentions are first-class</b> per CLAUDE.md guardrail #8. Both streams are surfaced
 * in the rendered report:
 *
 * <ul>
 *   <li>{@link Glance} unifies counts across articles + social posts.
 *   <li>{@link Highlight} is a kind-tagged record so the unified Highlights section can render
 *       either an article highlight or a social-post highlight without two branches in the
 *       template.
 *   <li>{@link Item} is the article-coverage row (Coverage section).
 *   <li>{@link SocialMention} is the social-post row (separate Social mentions section, since the
 *       visual treatment differs enough — platform glyph, author handle, engagement counts — that
 *       mixing them into one list would be hard to scan).
 * </ul>
 */
public record RenderPayload(
    Branding branding,
    Client client,
    Report report,
    Glance glance,
    List<Highlight> highlights,
    List<Item> coverage_items,
    List<SocialMention> social_mentions,
    String base_url) {

  public record Branding(String agency_name, String logo_url, String primary_color) {}

  public record Client(String name, String logo_url, String primary_color) {}

  public record Report(
      String title,
      String period_label,
      String executive_summary,
      List<String> executive_summary_paragraphs) {}

  /**
   * {@code total} is the substantive coverage count combined across articles and social posts
   * (excludes 'missing' items in either stream). {@code missing_count} is the combined off-topic
   * count from both streams; the template uses it to render a small disclosure footnote when
   * nonzero so the share-link recipient understands the discrepancy between the URL count and the
   * headline number.
   */
  public record Glance(
      int total,
      int tier_1,
      int outlets,
      long reach_total,
      String reach_human,
      int missing_count) {}

  /**
   * Unified highlight. {@code kind} is "article" or "social"; the template uses it to switch which
   * fields to surface. Both kinds carry {@code headline}/{@code lede}/{@code outlet_name} (for
   * social, those map to the post's first line, summary, and platform display label respectively)
   * so the basic card chrome can be shared.
   */
  public record Highlight(
      String kind,
      String headline,
      String lede,
      String publish_date,
      Integer tier,
      String outlet_name,
      String screenshot_url,
      // Social-only fields. Null for article highlights.
      String platform,
      String author_handle,
      String author_display_name) {}

  public record Item(
      String headline,
      String lede,
      String summary,
      String publish_date,
      Integer tier,
      String sentiment,
      String outlet_name,
      String screenshot_url) {}

  /**
   * Social-post row for the Social mentions section. {@code platform} drives the platform glyph the
   * template renders; engagement counts are pre-formatted strings (e.g. "1.2K") so the template
   * doesn't need helpers. {@code key_excerpt} is the LLM-extracted standout sentence if any,
   * falling back to the start of {@code content_text}.
   */
  public record SocialMention(
      String platform,
      String source_url,
      String posted_date,
      String author_handle,
      String author_display_name,
      String author_avatar_url,
      String content_text,
      String key_excerpt,
      String summary,
      String sentiment,
      String likes_human,
      String reposts_human,
      String replies_human) {}
}
