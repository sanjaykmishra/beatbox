package app.beat.render;

import app.beat.client.Client;
import app.beat.coverage.CoverageItem;
import app.beat.outlet.Outlet;
import app.beat.outlet.OutletRepository;
import app.beat.report.Report;
import app.beat.workspace.Workspace;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Builds the wire payload from in-DB rows. Pure logic; deterministic. */
@Component
public class RenderPayloadBuilder {

  private final OutletRepository outlets;

  public RenderPayloadBuilder(OutletRepository outlets) {
    this.outlets = outlets;
  }

  public RenderPayload build(
      Workspace workspace, Client client, Report report, List<CoverageItem> items) {
    Map<UUID, Outlet> outletCache = preloadOutlets(items);
    var branding = buildBranding(workspace, client);
    var clientDto =
        new RenderPayload.Client(client.name(), client.logoUrl(), client.primaryColor());
    var reportDto =
        new RenderPayload.Report(
            report.title(),
            periodLabel(report),
            report.executiveSummary(),
            paragraphs(report.executiveSummary()));
    var glance = AtAGlance.compute(items, outletCache);
    var highlights =
        Highlights.pickTop(items, outletCache, 4).stream()
            .map(c -> toHighlight(c, outletCache))
            .toList();
    var itemDtos = items.stream().map(c -> toItem(c, outletCache)).toList();
    return new RenderPayload(branding, clientDto, reportDto, glance, highlights, itemDtos);
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

  private RenderPayload.Highlight toHighlight(CoverageItem c, Map<UUID, Outlet> outletCache) {
    Outlet o = c.outletId() == null ? null : outletCache.get(c.outletId());
    return new RenderPayload.Highlight(
        c.headline(),
        c.lede(),
        c.publishDate() == null ? null : c.publishDate().toString(),
        c.tierAtExtraction(),
        o == null ? null : o.name(),
        c.screenshotUrl());
  }

  private RenderPayload.Item toItem(CoverageItem c, Map<UUID, Outlet> outletCache) {
    Outlet o = c.outletId() == null ? null : outletCache.get(c.outletId());
    return new RenderPayload.Item(
        c.headline(),
        c.lede(),
        c.summary(),
        c.publishDate() == null ? null : c.publishDate().toString(),
        c.tierAtExtraction(),
        c.sentiment(),
        o == null ? null : o.name(),
        c.screenshotUrl());
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
