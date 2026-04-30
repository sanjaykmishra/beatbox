package app.beat.alerts;

import app.beat.client.Client;
import app.beat.client.ClientRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Orchestrates AlertEngine + persistence. Three triggers per docs/16 §Refresh strategy:
 *
 * <ul>
 *   <li>Event-driven: callers (controllers, workers) call {@link #recomputeFor(UUID)} after writing
 *       the relevant {@code activity_events} row.
 *   <li>Scheduled: {@link #scheduledRefresh()} every 30 minutes for time-based alerts.
 *   <li>On-demand: {@link #recomputeFor(UUID)} from POST /v1/clients/:id/alerts/refresh.
 * </ul>
 */
@Service
public class AlertService {

  private static final Logger log = LoggerFactory.getLogger(AlertService.class);

  private final AlertEngine engine;
  private final ClientAlertRepository alerts;
  private final ClientRepository clients;

  public AlertService(AlertEngine engine, ClientAlertRepository alerts, ClientRepository clients) {
    this.engine = engine;
    this.alerts = alerts;
    this.clients = clients;
  }

  /** Recompute and persist the alert set for one client. Safe to call from anywhere. */
  public void recomputeFor(UUID clientId) {
    if (clientId == null) return;
    try {
      Client client = clients.findById(clientId).orElse(null);
      if (client == null) return;
      var computed = engine.computeFor(client);
      alerts.replace(client.id(), client.workspaceId(), computed);
    } catch (Exception e) {
      log.warn("alert recompute failed for client={}: {}", clientId, e.toString());
    }
  }

  @Scheduled(fixedDelayString = "${beat.alerts.scan-ms:1800000}") // 30 minutes
  public void scheduledRefresh() {
    long started = System.currentTimeMillis();
    int n = 0;
    for (Client c : clients.listAllActive()) {
      recomputeFor(c.id());
      n++;
    }
    log.info(
        "alert scan: refreshed {} client(s) in {} ms", n, System.currentTimeMillis() - started);
  }
}
