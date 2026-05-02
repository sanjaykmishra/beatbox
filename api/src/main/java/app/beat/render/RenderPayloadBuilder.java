package app.beat.render;

import app.beat.client.Client;
import app.beat.coverage.CoverageItem;
import app.beat.outlet.Outlet;
import app.beat.outlet.OutletRepository;
import app.beat.report.Report;
import app.beat.social.SocialAuthor;
import app.beat.social.SocialAuthorRepository;
import app.beat.social.SocialMention;
import app.beat.workspace.Workspace;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Builds the wire payload from in-DB rows. Pure logic; deterministic. */
@Component
public class RenderPayloadBuilder {

  private static final DateTimeFormatter PUBLISHED_FMT = DateTimeFormatter.ISO_DATE;

  private final OutletRepository outlets;
  private final SocialAuthorRepository socialAuthors;
  private final String internalApiUrl;

  public RenderPayloadBuilder(
      OutletRepository outlets,
      SocialAuthorRepository socialAuthors,
      @Value("${beat.render.internal-api-url:}") String internalApiUrl) {
    this.outlets = outlets;
    this.socialAuthors = socialAuthors;
    this.internalApiUrl = internalApiUrl == null ? "" : internalApiUrl;
  }

  /**
   * Article-only build for callers that don't carry social mentions yet (test fixtures, legacy
   * paths). Production callers should use the two-list overload below so social mentions surface in
   * the rendered report per CLAUDE.md guardrail #8.
   */
  public RenderPayload build(
      Workspace workspace, Client client, Report report, List<CoverageItem> items) {
    return build(workspace, client, report, items, List.of());
  }

  public RenderPayload build(
      Workspace workspace,
      Client client,
      Report report,
      List<CoverageItem> items,
      List<SocialMention> mentions) {
    Map<UUID, Outlet> outletCache = preloadOutlets(items);
    Map<UUID, SocialAuthor> authorCache = preloadAuthors(mentions);
    var branding = buildBranding(workspace, client);
    var clientDto =
        new RenderPayload.Client(client.name(), client.logoUrl(), client.primaryColor());
    var reportDto =
        new RenderPayload.Report(
            report.title(),
            periodLabel(report),
            report.executiveSummary(),
            paragraphs(report.executiveSummary()));

    // Substantive sets: extraction finished AND the subject is actually mentioned. Items tagged
    // 'missing' are URLs the user added that turned out to be off-topic. Kept on the builder UI
    // for source-list cleanup but excluded from the rendered report — counting an article that
    // doesn't mention the client toward "Total coverage" is misleading, and putting one in
    // Highlights is straight-up wrong.
    var substantiveArticles =
        items.stream()
            .filter(c -> "done".equals(c.extractionStatus()))
            .filter(c -> !"missing".equals(c.subjectProminence()))
            .toList();
    var substantiveMentions =
        mentions.stream()
            .filter(m -> "done".equals(m.extractionStatus()))
            .filter(m -> !"missing".equals(m.subjectProminence()))
            .toList();

    // Disclosure footnote driver: combined missing count across both streams.
    int missingArticles =
        (int)
            items.stream()
                .filter(c -> "done".equals(c.extractionStatus()))
                .filter(c -> "missing".equals(c.subjectProminence()))
                .count();
    int missingMentions =
        (int)
            mentions.stream()
                .filter(m -> "done".equals(m.extractionStatus()))
                .filter(m -> "missing".equals(m.subjectProminence()))
                .count();
    int missingCount = missingArticles + missingMentions;

    var glance =
        AtAGlance.compute(substantiveArticles, substantiveMentions, outletCache, missingCount);
    var highlights =
        Highlights.pickTop(substantiveArticles, substantiveMentions, outletCache, 4).stream()
            .map(p -> toHighlight(p, outletCache, authorCache))
            .toList();
    var itemDtos = substantiveArticles.stream().map(c -> toItem(c, outletCache)).toList();
    var mentionDtos = substantiveMentions.stream().map(m -> toMention(m, authorCache)).toList();

    String baseUrl = internalApiUrl.isBlank() ? null : internalApiUrl;
    return new RenderPayload(
        branding, clientDto, reportDto, glance, highlights, itemDtos, mentionDtos, baseUrl);
  }

  private RenderPayload.Branding buildBranding(Workspace ws, Client client) {
    String logo = client.logoUrl() != null ? client.logoUrl() : ws.logoUrl();
    String color =
        client.primaryColor() != null
            ? client.primaryColor()
            : (ws.primaryColor() != null ? ws.primaryColor() : "1F2937");
    return new RenderPayload.Branding(ws.name(), logo, color);
  }

  private Map<UUID, Outlet> preloadOutlets(List<CoverageItem> items) {
    Map<UUID, Outlet> out = new HashMap<>();
    for (CoverageItem c : items) {
      if (c.outletId() != null && !out.containsKey(c.outletId())) {
        outlets.findById(c.outletId()).ifPresent(o -> out.put(o.id(), o));
      }
    }
    return out;
  }

  private Map<UUID, SocialAuthor> preloadAuthors(List<SocialMention> mentions) {
    Map<UUID, SocialAuthor> out = new HashMap<>();
    for (SocialMention m : mentions) {
      if (m.authorId() != null && !out.containsKey(m.authorId())) {
        socialAuthors.findById(m.authorId()).ifPresent(a -> out.put(a.id(), a));
      }
    }
    return out;
  }

  private RenderPayload.Highlight toHighlight(
      Highlights.Picked p, Map<UUID, Outlet> outletCache, Map<UUID, SocialAuthor> authorCache) {
    if (p instanceof Highlights.PickedArticle pa) {
      CoverageItem c = pa.item();
      Outlet o = c.outletId() == null ? null : outletCache.get(c.outletId());
      return new RenderPayload.Highlight(
          "article",
          c.headline(),
          c.lede(),
          c.publishDate() == null ? null : c.publishDate().toString(),
          c.tierAtExtraction(),
          o == null ? null : o.name(),
          c.screenshotUrl(),
          /* platform */ null,
          /* author_handle */ null,
          /* author_display_name */ null);
    }
    Highlights.PickedSocial ps = (Highlights.PickedSocial) p;
    SocialMention m = ps.mention();
    SocialAuthor author = m.authorId() == null ? null : authorCache.get(m.authorId());
    String headline = firstLine(m.contentText());
    return new RenderPayload.Highlight(
        "social",
        headline,
        m.summary(),
        m.postedAt() == null
            ? null
            : m.postedAt().atOffset(ZoneOffset.UTC).toLocalDate().toString(),
        /* tier */ null,
        platformLabel(m.platform()),
        author == null ? null : author.avatarUrl(),
        m.platform(),
        author == null ? null : author.handle(),
        author == null ? null : author.displayName());
  }

  private RenderPayload.Item toItem(CoverageItem c, Map<UUID, Outlet> outletCache) {
    Outlet o = c.outletId() == null ? null : outletCache.get(c.outletId());
    return new RenderPayload.Item(
        c.headline(),
        c.lede(),
        c.summary(),
        c.publishDate() == null ? null : c.publishDate().format(PUBLISHED_FMT),
        c.tierAtExtraction(),
        c.sentiment(),
        o == null ? null : o.name(),
        c.screenshotUrl());
  }

  private RenderPayload.SocialMention toMention(
      SocialMention m, Map<UUID, SocialAuthor> authorCache) {
    SocialAuthor author = m.authorId() == null ? null : authorCache.get(m.authorId());
    String posted =
        m.postedAt() == null
            ? null
            : m.postedAt().atOffset(ZoneOffset.UTC).toLocalDate().toString();
    // The SocialMention entity doesn't carry a separate key_excerpt; the LLM extraction stores
    // its standout line in `summary`. Pass content_text + summary; the template renders one
    // or the other per its own preference.
    return new RenderPayload.SocialMention(
        m.platform(),
        m.sourceUrl(),
        posted,
        author == null ? null : author.handle(),
        author == null ? null : author.displayName(),
        author == null ? null : author.avatarUrl(),
        m.contentText(),
        /* key_excerpt */ null,
        m.summary(),
        m.sentiment(),
        AtAGlance.formatReach(m.likesCount() == null ? 0 : m.likesCount()),
        AtAGlance.formatReach(m.repostsCount() == null ? 0 : m.repostsCount()),
        AtAGlance.formatReach(m.repliesCount() == null ? 0 : m.repliesCount()));
  }

  /** Platform → human-readable label used in the unified Highlights "outlet" slot. */
  private static String platformLabel(String platform) {
    if (platform == null) return null;
    return switch (platform) {
      case "x" -> "X";
      case "linkedin" -> "LinkedIn";
      case "bluesky" -> "Bluesky";
      case "threads" -> "Threads";
      case "instagram" -> "Instagram";
      case "facebook" -> "Facebook";
      case "tiktok" -> "TikTok";
      case "reddit" -> "Reddit";
      case "substack" -> "Substack";
      case "youtube" -> "YouTube";
      case "mastodon" -> "Mastodon";
      default -> platform;
    };
  }

  /**
   * First line of a post body, capped to ~120 chars; used as the highlight "headline" for social
   * posts (which don't have one). Falls back to empty when content is null/blank.
   */
  private static String firstLine(String content) {
    if (content == null) return "";
    int nl = content.indexOf('\n');
    String line = nl < 0 ? content : content.substring(0, nl);
    line = line.trim();
    if (line.length() > 120) line = line.substring(0, 117) + "…";
    return line;
  }

  static String periodLabel(Report r) {
    if (r.periodStart() == null || r.periodEnd() == null) return "";
    var fmt = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);
    return r.periodStart().format(fmt) + " — " + r.periodEnd().format(fmt);
  }

  static List<String> paragraphs(String summary) {
    if (summary == null || summary.isBlank()) return List.of();
    List<String> out = new ArrayList<>();
    for (String p : summary.split("\\n\\s*\\n")) {
      String t = p.trim();
      if (!t.isEmpty()) out.add(t);
    }
    return out;
  }
}
