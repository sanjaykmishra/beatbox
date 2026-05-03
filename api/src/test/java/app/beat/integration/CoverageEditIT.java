package app.beat.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.beat.report.ReportRepository;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * Covers the "+ Add URLs" flow on the coverage view, which reuses POST /v1/reports/:id/coverage.
 * Per the V013 lifecycle:
 *
 * <ul>
 *   <li>Adding to a 'draft' / 'ready' / 'failed' report leaves status unchanged. The previous PDF
 *       (when present) stays valid until the user re-Generates.
 *   <li>Adding to a 'processing' report is rejected — we don't let the user race the renderer.
 *   <li>Adding to a 'published' report is rejected with 409 — published reports are locked.
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIf("dockerAvailable")
class CoverageEditIT extends IntegrationTestBase {

  @Autowired MockMvc mvc;
  @Autowired ReportRepository reports;
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void addUrlsKeepsStatusForDraftReadyFailed() throws Exception {
    Workspace ws = signUp();

    String clientId = createClient(ws.token, "Acme Corp");
    String reportId = createReport(ws.token, clientId);

    // Initial add — draft → draft.
    addUrls(ws.token, reportId, List.of("https://example.com/a"))
        .andExpect(MockMvcResultMatchers.status().isAccepted())
        .andExpect(MockMvcResultMatchers.jsonPath("$.items.length()").value(1));
    assertThat(getStatus(ws.token, reportId)).isEqualTo("draft");

    // 'ready' stays 'ready' — adds don't auto-flip back to draft any more (V013 lifecycle).
    reports.setStatus(UUID.fromString(reportId), "ready");
    addUrls(ws.token, reportId, List.of("https://example.com/b"))
        .andExpect(MockMvcResultMatchers.status().isAccepted());
    assertThat(getStatus(ws.token, reportId)).isEqualTo("ready");

    // Same for 'failed'.
    reports.setStatus(UUID.fromString(reportId), "failed");
    addUrls(ws.token, reportId, List.of("https://example.com/c"))
        .andExpect(MockMvcResultMatchers.status().isAccepted());
    assertThat(getStatus(ws.token, reportId)).isEqualTo("failed");
  }

  @Test
  void addUrlsRejectedWhileReportIsProcessing() throws Exception {
    Workspace ws = signUp();
    String clientId = createClient(ws.token, "Beta Co");
    String reportId = createReport(ws.token, clientId);

    reports.setStatus(UUID.fromString(reportId), "processing");
    addUrls(ws.token, reportId, List.of("https://example.com/x"))
        .andExpect(MockMvcResultMatchers.status().isBadRequest())
        .andExpect(MockMvcResultMatchers.jsonPath("$.title").value("Report is generating"));
  }

  @Test
  void addUrlsRejectedWhenPublished() throws Exception {
    Workspace ws = signUp();
    String clientId = createClient(ws.token, "Gamma Co");
    String reportId = createReport(ws.token, clientId);

    reports.setStatus(UUID.fromString(reportId), "published");
    addUrls(ws.token, reportId, List.of("https://example.com/y"))
        .andExpect(MockMvcResultMatchers.status().isConflict())
        .andExpect(MockMvcResultMatchers.jsonPath("$.title").value("Report is published"));
  }

  // ---- helpers ----

  private record Workspace(String token, String workspaceId) {}

  private Workspace signUp() throws Exception {
    String email = "edit+" + System.nanoTime() + "@example.com";
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
                                "Edit Tester",
                                "workspace_name",
                                "Edit WS " + System.nanoTime()))))
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
    JsonNode body = json.readTree(res.getResponse().getContentAsByteArray());
    return new Workspace(
        body.get("session_token").asText(), body.get("workspace").get("id").asText());
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

  private org.springframework.test.web.servlet.ResultActions addUrls(
      String token, String reportId, List<String> urls) throws Exception {
    return mvc.perform(
        MockMvcRequestBuilders.post("/v1/reports/" + reportId + "/coverage")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("urls", urls))));
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
