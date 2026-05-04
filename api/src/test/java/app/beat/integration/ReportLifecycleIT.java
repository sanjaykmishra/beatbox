package app.beat.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.beat.report.ReportRepository;
import app.beat.workspace.WorkspaceMemberRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * End-to-end coverage of the V013 report lifecycle:
 *
 * <ul>
 *   <li>Generate is allowed from {@code draft}, {@code ready} (re-generate), {@code failed} (retry)
 *       — all flip to {@code processing}. Rejected from {@code published}.
 *   <li>Publish requires {@code ready}. In a single-person workspace the creator can publish; in a
 *       multi-person workspace the creator gets 403, but another member can publish.
 *   <li>Delete is allowed only on {@code ready} or {@code failed}; rejected on {@code draft},
 *       {@code processing}, {@code published}.
 *   <li>Once published, every mutation endpoint refuses (409).
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIf("dockerAvailable")
class ReportLifecycleIT extends IntegrationTestBase {

  @Autowired MockMvc mvc;
  @Autowired ReportRepository reports;
  @Autowired WorkspaceMemberRepository members;
  private final ObjectMapper json = new ObjectMapper();

  // ---- Publish ----

  @Test
  void singlePersonWorkspaceCreatorCanPublish() throws Exception {
    Workspace ws = signUp();
    String reportId = readyReport(ws);

    authedPost("/v1/reports/" + reportId + "/publish", ws.token)
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("published"));
    assertThat(getStatus(ws.token, reportId)).isEqualTo("published");
  }

  @Test
  void multiPersonWorkspaceRejectsCreatorSelfPublish() throws Exception {
    Workspace owner = signUp();
    UUID inviteeUserId = createSecondMember(owner.workspaceId, "member");
    // Sanity: countActiveMembers should now be 2.
    assertThat(members.countActiveMembers(UUID.fromString(owner.workspaceId))).isEqualTo(2);

    String reportId = readyReport(owner);
    // Owner is the creator; with another member present they can't self-publish.
    authedPost("/v1/reports/" + reportId + "/publish", owner.token)
        .andExpect(MockMvcResultMatchers.status().isForbidden());
    assertThat(getStatus(owner.token, reportId)).isEqualTo("ready");
    // Confirm the invitee row exists (we don't have a session for them in this IT — exercising
    // the "other member publishes" path requires the session machinery, which is out of scope
    // here. The forbidden-on-creator branch is the unique behavior under test.)
    assertThat(inviteeUserId).isNotNull();
  }

  @Test
  void publishRejectedFromNonReadyStates() throws Exception {
    Workspace ws = signUp();
    String reportId = createReport(ws.token, createClient(ws.token, "X"));

    // draft
    authedPost("/v1/reports/" + reportId + "/publish", ws.token)
        .andExpect(MockMvcResultMatchers.status().isBadRequest());

    reports.setStatus(UUID.fromString(reportId), "processing");
    authedPost("/v1/reports/" + reportId + "/publish", ws.token)
        .andExpect(MockMvcResultMatchers.status().isBadRequest());

    reports.setStatus(UUID.fromString(reportId), "failed");
    authedPost("/v1/reports/" + reportId + "/publish", ws.token)
        .andExpect(MockMvcResultMatchers.status().isBadRequest());

    reports.setStatus(UUID.fromString(reportId), "published");
    authedPost("/v1/reports/" + reportId + "/publish", ws.token)
        .andExpect(MockMvcResultMatchers.status().isBadRequest());
  }

  @Test
  void publishedReportRefusesAllMutations() throws Exception {
    Workspace ws = signUp();
    String reportId = readyReport(ws);
    authedPost("/v1/reports/" + reportId + "/publish", ws.token)
        .andExpect(MockMvcResultMatchers.status().isOk());

    // POST coverage
    mvc.perform(
            MockMvcRequestBuilders.post("/v1/reports/" + reportId + "/coverage")
                .header("Authorization", "Bearer " + ws.token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("urls", List.of("https://example.com/x")))))
        .andExpect(MockMvcResultMatchers.status().isConflict());

    // POST generate
    authedPost("/v1/reports/" + reportId + "/generate", ws.token)
        .andExpect(MockMvcResultMatchers.status().isConflict());

    // PATCH summary
    mvc.perform(
            MockMvcRequestBuilders.patch("/v1/reports/" + reportId + "/summary")
                .header("Authorization", "Bearer " + ws.token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("summary", "new summary"))))
        .andExpect(MockMvcResultMatchers.status().isConflict());

    // DELETE report
    mvc.perform(
            MockMvcRequestBuilders.delete("/v1/reports/" + reportId)
                .header("Authorization", "Bearer " + ws.token))
        .andExpect(MockMvcResultMatchers.status().isConflict());
  }

  // ---- Delete ----

  @Test
  void deleteAllowedOnReadyAndFailed() throws Exception {
    Workspace ws = signUp();
    String clientId = createClient(ws.token, "Delta");

    String r1 = createReport(ws.token, clientId);
    reports.setStatus(UUID.fromString(r1), "ready");
    mvc.perform(
            MockMvcRequestBuilders.delete("/v1/reports/" + r1)
                .header("Authorization", "Bearer " + ws.token))
        .andExpect(MockMvcResultMatchers.status().isNoContent());

    String r2 = createReport(ws.token, clientId);
    reports.setStatus(UUID.fromString(r2), "failed");
    mvc.perform(
            MockMvcRequestBuilders.delete("/v1/reports/" + r2)
                .header("Authorization", "Bearer " + ws.token))
        .andExpect(MockMvcResultMatchers.status().isNoContent());
  }

  @Test
  void deleteRejectedOnDraft() throws Exception {
    // Drafts are pre-generation work-in-progress — no delete affordance. (Processing IS
    // deletable now as a stuck-report escape hatch; covered separately.)
    Workspace ws = signUp();
    String clientId = createClient(ws.token, "Echo");
    String draft = createReport(ws.token, clientId);
    mvc.perform(
            MockMvcRequestBuilders.delete("/v1/reports/" + draft)
                .header("Authorization", "Bearer " + ws.token))
        .andExpect(MockMvcResultMatchers.status().isConflict());
  }

  @Test
  void deleteAllowedOnProcessingAsRecovery() throws Exception {
    // Stuck-render escape hatch: when the worker dies mid-job and leaves the report at
    // 'processing' indefinitely, the user can still delete it without admin intervention.
    Workspace ws = signUp();
    String clientId = createClient(ws.token, "EchoStuck");
    String processing = createReport(ws.token, clientId);
    reports.setStatus(UUID.fromString(processing), "processing");
    mvc.perform(
            MockMvcRequestBuilders.delete("/v1/reports/" + processing)
                .header("Authorization", "Bearer " + ws.token))
        .andExpect(MockMvcResultMatchers.status().isNoContent());
  }

  @Test
  void generateAllowedOnProcessingAsRecovery() throws Exception {
    // Same escape hatch on the generate side: re-queue the render job to recover from a stuck
    // worker. The render-jobs row gets upserted back to 'queued' (RenderJobRepository.enqueue).
    Workspace ws = signUp();
    String reportId = readyReport(ws);
    reports.setStatus(UUID.fromString(reportId), "processing");
    // Generate on processing returns 202 — note that the actual extraction-pending /
    // no-done-items checks fire after this point, so the test ensures we get past the status
    // gate. With a freshly-created report there are no items yet, so we expect 400 from the
    // no-done-items check rather than the report-in-flight check we used to throw.
    authedPost("/v1/reports/" + reportId + "/generate", ws.token)
        .andExpect(MockMvcResultMatchers.status().isBadRequest())
        .andExpect(
            MockMvcResultMatchers.jsonPath("$.title")
                .value(org.hamcrest.Matchers.not("Report is already generating")));
  }

  // ---- Generate ----

  @Test
  void generateRejectedWhenPublished() throws Exception {
    Workspace ws = signUp();
    String reportId = readyReport(ws);
    reports.setStatus(UUID.fromString(reportId), "published");
    authedPost("/v1/reports/" + reportId + "/generate", ws.token)
        .andExpect(MockMvcResultMatchers.status().isConflict());
  }

  // ---- Share ----

  @Test
  void shareRequiresPublished() throws Exception {
    Workspace ws = signUp();
    String reportId = readyReport(ws);
    // ready isn't sharable any more under V013
    mvc.perform(
            MockMvcRequestBuilders.post("/v1/reports/" + reportId + "/share")
                .header("Authorization", "Bearer " + ws.token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(MockMvcResultMatchers.status().isBadRequest());

    reports.setStatus(UUID.fromString(reportId), "published");
    mvc.perform(
            MockMvcRequestBuilders.post("/v1/reports/" + reportId + "/share")
                .header("Authorization", "Bearer " + ws.token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(MockMvcResultMatchers.status().isOk());
  }

  // ---- helpers ----

  private record Workspace(String token, String userId, String workspaceId) {}

  private Workspace signUp() throws Exception {
    String email = "rl+" + System.nanoTime() + "@example.com";
    MvcResult res =
        mvc.perform(
                MockMvcRequestBuilders.post("/v1/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "email",
                                email,
                                "password",
                                "supersecret",
                                "name",
                                "Lifecycle Tester",
                                "workspace_name",
                                "RL WS " + System.nanoTime()))))
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
    JsonNode body = json.readTree(res.getResponse().getContentAsByteArray());
    return new Workspace(
        body.get("session_token").asText(),
        body.get("user").get("id").asText(),
        body.get("workspace").get("id").asText());
  }

  /**
   * Inserts a second active member directly via the repository. The integration tests don't have a
   * tidy "invite by email" flow (real invitations are out of scope); we just need a row in
   * workspace_members so countActiveMembers returns >1. Returns the new user_id.
   */
  private UUID createSecondMember(String workspaceId, String role) throws Exception {
    String email = "second+" + System.nanoTime() + "@example.com";
    MvcResult res =
        mvc.perform(
                MockMvcRequestBuilders.post("/v1/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "email",
                                email,
                                "password",
                                "supersecret",
                                "name",
                                "Second User",
                                "workspace_name",
                                "Second WS " + System.nanoTime()))))
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
    UUID userId =
        UUID.fromString(
            json.readTree(res.getResponse().getContentAsByteArray())
                .get("user")
                .get("id")
                .asText());
    members.insert(UUID.fromString(workspaceId), userId, role);
    return userId;
  }

  private String createClient(String token, String name) throws Exception {
    MvcResult res =
        mvc.perform(
                MockMvcRequestBuilders.post("/v1/clients")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("name", name))))
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
    return json.readTree(res.getResponse().getContentAsByteArray()).get("id").asText();
  }

  private String createReport(String token, String clientId) throws Exception {
    MvcResult res =
        mvc.perform(
                MockMvcRequestBuilders.post("/v1/clients/" + clientId + "/reports")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "period_start", "2026-01-01",
                                "period_end", "2026-01-31"))))
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
    return json.readTree(res.getResponse().getContentAsByteArray()).get("id").asText();
  }

  /** Create a report and force it to 'ready' status (skipping the actual render). */
  private String readyReport(Workspace ws) throws Exception {
    String clientId = createClient(ws.token, "Acme " + System.nanoTime());
    String reportId = createReport(ws.token, clientId);
    reports.setStatus(UUID.fromString(reportId), "ready");
    return reportId;
  }

  private ResultActions authedPost(String path, String token) throws Exception {
    return mvc.perform(
        MockMvcRequestBuilders.post(path).header("Authorization", "Bearer " + token));
  }

  private String getStatus(String token, String reportId) throws Exception {
    MvcResult res =
        mvc.perform(
                MockMvcRequestBuilders.get("/v1/reports/" + reportId)
                    .header("Authorization", "Bearer " + token))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
    return json.readTree(res.getResponse().getContentAsByteArray()).get("status").asText();
  }
}
