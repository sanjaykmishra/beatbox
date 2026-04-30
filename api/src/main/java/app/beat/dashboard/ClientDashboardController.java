package app.beat.dashboard;

import app.beat.activity.ActivityEventRepository;
import app.beat.alerts.AlertService;
import app.beat.alerts.ClientAlert;
import app.beat.alerts.ClientAlertRepository;
import app.beat.client.Client;
import app.beat.client.ClientRepository;
import app.beat.clientcontext.ClientContext;
import app.beat.clientcontext.ClientContextRepository;
import app.beat.infra.AppException;
import app.beat.infra.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-client dashboard, activity feed, alert refresh, and setup-checklist dismiss. Spec source:
 * docs/16-client-dashboard.md.
 */
@RestController
public class ClientDashboardController {

  private static final Pattern ISO_DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");

  private final ClientRepository clients;
  private final ClientContextRepository contexts;
  private final ClientAlertRepository alertRepo;
  private final AlertService alertService;
  private final DashboardStats stats;
  private final ActivityEventRepository events;

  public ClientDashboardController(
      ClientRepository clients,
      ClientContextRepository contexts,
      ClientAlertRepository alertRepo,
      AlertService alertService,
      DashboardStats stats,
      ActivityEventRepository events) {
    this.clients = clients;
    this.contexts = contexts;
    this.alertRepo = alertRepo;
    this.alertService = alertService;
    this.stats = stats;
    this.events = events;
  }

  // ===================== DTOs =====================

  public record DashboardDto(
      ClientHeader client,
      Stats30d stats_30d,
      List<AlertDto> alerts,
      List<ComingUp> coming_up,
      List<ActivityDto> recent_activity) {}

  public record ClientHeader(
      UUID id,
      String name,
      String logo_url,
      String primary_color,
      String default_cadence,
      Instant setup_dismissed_at) {}

  public record Stat(int value, Integer delta_pct, Integer delta_pts, String delta_label) {}

  public record Stats30d(Stat coverage_count, Stat tier_1_count, Stat sentiment, Stat reach) {}

  public record AlertDto(
      String alert_type,
      String severity,
      int count,
      String badge_label,
      String card_title,
      String card_subtitle,
      String card_action_label,
      String card_action_path) {}

  public record ComingUp(String kind, String title, String subtitle, String path) {}

  public record ActivityDto(
      Instant occurred_at, String kind, String label, String detail, Tag tag, String actor_label) {}

  public record Tag(String label, String tone) {}

  // ===================== Endpoints =====================

  @GetMapping("/v1/clients/{id}/dashboard")
  public DashboardDto dashboard(@PathVariable UUID id, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    Client client =
        clients
            .findInWorkspace(ctx.workspaceId(), id)
            .orElseThrow(() -> AppException.notFound("Client"));

    return new DashboardDto(
        new ClientHeader(
            client.id(),
            client.name(),
            client.logoUrl(),
            client.primaryColor(),
            client.defaultCadence(),
            client.setupDismissedAt()),
        statsFor(client.id()),
        alertsFor(client.id()),
        comingUpFor(client),
        recentActivityFor(client.id(), 8));
  }

  @GetMapping("/v1/clients/{id}/activity")
  public List<ActivityDto> activity(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "100") int limit,
      HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    clients
        .findInWorkspace(ctx.workspaceId(), id)
        .orElseThrow(() -> AppException.notFound("Client"));
    return recentActivityFor(id, Math.min(Math.max(limit, 1), 500));
  }

  @PostMapping("/v1/clients/{id}/alerts/refresh")
  public List<AlertDto> refreshAlerts(@PathVariable UUID id, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    clients
        .findInWorkspace(ctx.workspaceId(), id)
        .orElseThrow(() -> AppException.notFound("Client"));
    alertService.recomputeFor(id);
    return alertsFor(id);
  }

  @PostMapping("/v1/clients/{id}/setup-checklist/dismiss")
  public ResponseEntity<Void> dismissSetup(@PathVariable UUID id, HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    clients
        .findInWorkspace(ctx.workspaceId(), id)
        .orElseThrow(() -> AppException.notFound("Client"));
    clients.dismissSetup(id);
    alertService.recomputeFor(id);
    return ResponseEntity.noContent().build();
  }

  // ===================== Helpers =====================

  private Stats30d statsFor(UUID clientId) {
    var current = stats.window(clientId, 30, 0);
    var prior = stats.window(clientId, 30, 30);

    Stat coverage = pctStat(current.coverage(), prior.coverage());
    Stat tier1 = ptsStat(current.tier1(), prior.tier1());
    Stat sentiment = ptsStat(current.sentimentPts(), prior.sentimentPts(), "pts");
    Stat reach =
        pctStat(
            (int) Math.min(current.reach(), Integer.MAX_VALUE),
            (int) Math.min(prior.reach(), Integer.MAX_VALUE));
    return new Stats30d(coverage, tier1, sentiment, reach);
  }

  private static Stat pctStat(int current, int prior) {
    Integer pct = null;
    String label = "stable";
    if (prior > 0) {
      pct = (int) Math.round(((double) (current - prior) / prior) * 100);
      label = arrowLabel(pct, "%");
    } else if (current > 0) {
      label = "↑ new";
    }
    return new Stat(current, pct, null, label);
  }

  private static Stat ptsStat(int current, int prior) {
    int delta = current - prior;
    return new Stat(current, null, delta, arrowLabel(delta, ""));
  }

  private static Stat ptsStat(int current, int prior, String unit) {
    int delta = current - prior;
    return new Stat(current, null, delta, arrowLabel(delta, " " + unit));
  }

  private static String arrowLabel(int delta, String suffix) {
    if (delta >= 5) return "↑ " + delta + suffix;
    if (delta <= -5) return "↓ " + Math.abs(delta) + suffix;
    return "stable";
  }

  private List<AlertDto> alertsFor(UUID clientId) {
    return alertRepo.findByClient(clientId).stream()
        .sorted(
            java.util.Comparator.comparingInt(
                    (ClientAlert a) -> app.beat.alerts.AlertTypes.score(a.severity()))
                .reversed())
        .map(
            a ->
                new AlertDto(
                    a.alertType(),
                    a.severity(),
                    a.count(),
                    a.badgeLabel(),
                    a.cardTitle(),
                    a.cardSubtitle(),
                    a.cardActionLabel(),
                    a.cardActionPath()))
        .toList();
  }

  private List<ComingUp> comingUpFor(Client client) {
    var out = new ArrayList<ComingUp>();
    LocalDate today = LocalDate.now(ZoneOffset.UTC);

    // 1. Next reporting period.
    if (client.defaultCadence() != null) {
      LocalDate prev =
          app.beat.alerts.AlertEngine.previousPeriodEnd(today, client.defaultCadence());
      if (prev != null) {
        LocalDate nextEnd = nextPeriodEnd(today, client.defaultCadence(), prev);
        long daysAway = nextEnd.toEpochDay() - today.toEpochDay();
        out.add(
            new ComingUp(
                "report_due",
                nextEnd
                        .getMonth()
                        .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ROOT)
                    + " report",
                "Period ends in " + daysAway + " day" + (daysAway == 1 ? "" : "s"),
                "/clients/" + client.id() + "/reports/new"));
      }
    }

    // 2. Important dates from client_context.important_dates (regex parse).
    contexts
        .findByClient(client.id())
        .map(ClientContext::importantDates)
        .ifPresent(
            text -> {
              if (text == null) return;
              Matcher m = ISO_DATE.matcher(text);
              while (m.find() && out.size() < 5) {
                try {
                  LocalDate d = LocalDate.parse(m.group(1));
                  if (d.isAfter(today)) {
                    out.add(
                        new ComingUp(
                            "important_date",
                            "Important date",
                            d + " · from client context",
                            "/clients/" + client.id() + "/context"));
                  }
                } catch (Exception ignored) {
                  /* skip non-date matches */
                }
              }
            });

    if (out.size() > 5) return out.subList(0, 5);
    return out;
  }

  private static LocalDate nextPeriodEnd(LocalDate today, String cadence, LocalDate prevEnd) {
    return switch (cadence) {
      case "weekly" -> prevEnd.plusDays(7);
      case "biweekly" -> prevEnd.plusDays(14);
      case "monthly" -> today.withDayOfMonth(1).plusMonths(1).minusDays(1);
      case "quarterly" -> {
        LocalDate firstOfNextQuarter =
            today.withDayOfMonth(1).plusMonths(3 - ((today.getMonthValue() - 1) % 3));
        yield firstOfNextQuarter.minusDays(1);
      }
      default -> today.plusMonths(1);
    };
  }

  private List<ActivityDto> recentActivityFor(UUID clientId, int limit) {
    return events.findByClient(clientId, limit).stream()
        .map(
            e -> {
              var formatted = ActivityEventFormatter.format(e);
              Tag tag =
                  formatted.tagLabel() == null
                      ? null
                      : new Tag(formatted.tagLabel(), formatted.tagTone());
              return new ActivityDto(
                  e.occurredAt(),
                  formatted.kind(),
                  formatted.label(),
                  formatted.detail(),
                  tag,
                  null);
            })
        .toList();
  }
}
