package app.beat.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@EnabledIf("dockerAvailable")
class OwnedPostsIT {

  static boolean dockerAvailable() {
    try {
      return DockerClientFactory.instance().isDockerAvailable();
    } catch (Throwable t) {
      return false;
    }
  }

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void disableR2(DynamicPropertyRegistry r) {
    r.add("beat.r2.account-id", () -> "");
  }

  @Autowired MockMvc mvc;

  private final ObjectMapper json = new ObjectMapper();

  @Test
  void createListPatchTransitionDeletePost() throws Exception {
    var session = signupAndCreateClient();
    String token = session.token();
    String clientId = session.clientId();

    // Create a draft post.
    MvcResult created =
        mvc.perform(
                MockMvcRequestBuilders.post("/v1/posts")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "client_id", clientId,
                                "title", "Series B announcement",
                                "primary_content_text",
                                    "We're thrilled to announce our $30M Series B, led by Sequoia.",
                                "target_platforms", List.of("linkedin", "x"),
                                "series_tag", "funding"))))
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("draft"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.target_platforms[0]").value("linkedin"))
            .andReturn();
    String postId = json.readTree(created.getResponse().getContentAsByteArray()).get("id").asText();

    // Patch in platform variants (simulating the Week 2 variant LLM call).
    mvc.perform(
            MockMvcRequestBuilders.patch("/v1/posts/" + postId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "platform_variants",
                            Map.of(
                                "x",
                                Map.of(
                                    "content",
                                    "We raised $30M Series B (led by Sequoia).",
                                    "char_count",
                                    47),
                                "linkedin",
                                Map.of(
                                    "content",
                                    "We're thrilled to announce our $30M Series B, led by Sequoia.",
                                    "char_count",
                                    60))))))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.platform_variants.x.content").exists());

    // Transition: draft -> internal_review.
    mvc.perform(
            MockMvcRequestBuilders.post(
                    "/v1/posts/" + postId + "/transitions/submit_for_internal_review")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("internal_review"))
        .andExpect(MockMvcResultMatchers.jsonPath("$.submitted_for_review_at").exists());

    // List filtered by status — should include this post.
    MvcResult list =
        mvc.perform(
                MockMvcRequestBuilders.get("/v1/posts?status=internal_review")
                    .header("Authorization", "Bearer " + token))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
    JsonNode items = json.readTree(list.getResponse().getContentAsByteArray()).get("items");
    assertThat(items.size()).isGreaterThanOrEqualTo(1);

    // Reject without a reason should 400.
    mvc.perform(
            MockMvcRequestBuilders.post("/v1/posts/" + postId + "/transitions/reject")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(MockMvcResultMatchers.status().isBadRequest());

    // Reject WITH a reason should succeed.
    mvc.perform(
            MockMvcRequestBuilders.post("/v1/posts/" + postId + "/transitions/reject")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("reason", "Wrong tone for client."))))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("rejected"));

    // Disallowed transition (rejected -> internal_review) should 400.
    mvc.perform(
            MockMvcRequestBuilders.post(
                    "/v1/posts/" + postId + "/transitions/submit_for_internal_review")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(MockMvcResultMatchers.status().isBadRequest());

    // Soft-delete.
    mvc.perform(
            MockMvcRequestBuilders.delete("/v1/posts/" + postId)
                .header("Authorization", "Bearer " + token))
        .andExpect(MockMvcResultMatchers.status().isNoContent());

    // GET deleted post should 404.
    mvc.perform(
            MockMvcRequestBuilders.get("/v1/posts/" + postId)
                .header("Authorization", "Bearer " + token))
        .andExpect(MockMvcResultMatchers.status().isNotFound());
  }

  @Test
  void patchAcceptsArrayUpdatesIncludingTargetPlatforms() throws Exception {
    // Regression for "PATCH 500 when client posts target_platforms": Postgres needs an
    // explicit cast on the bound array parameter inside COALESCE.
    var session = signupAndCreateClient();
    String token = session.token();

    MvcResult created =
        mvc.perform(
                MockMvcRequestBuilders.post("/v1/posts")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "client_id", session.clientId(),
                                "target_platforms", List.of("linkedin")))))
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
    String postId = json.readTree(created.getResponse().getContentAsByteArray()).get("id").asText();

    // PATCH with target_platforms (text[]) and asset_ids (uuid[]) — these go through the
    // CAST(:tp AS text[]) / CAST(:assets AS uuid[]) paths.
    mvc.perform(
            MockMvcRequestBuilders.patch("/v1/posts/" + postId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "target_platforms", List.of("linkedin", "x", "bluesky"),
                            "scheduled_for", "2026-12-15T16:00:00Z",
                            "asset_ids", List.of()))))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.target_platforms.length()").value(3))
        .andExpect(MockMvcResultMatchers.jsonPath("$.scheduled_for").exists());

    // List with from/to window (Instant params) — exercises the timestamptz binding too.
    mvc.perform(
            MockMvcRequestBuilders.get(
                    "/v1/posts?from=2026-12-01T00:00:00Z&to=2027-01-01T00:00:00Z")
                .header("Authorization", "Bearer " + token))
        .andExpect(MockMvcResultMatchers.status().isOk());
  }

  @Test
  void rejectsUnknownPlatform() throws Exception {
    var session = signupAndCreateClient();
    mvc.perform(
            MockMvcRequestBuilders.post("/v1/posts")
                .header("Authorization", "Bearer " + session.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "client_id", session.clientId(),
                            "primary_content_text", "Hello",
                            "target_platforms", List.of("myspace")))))
        .andExpect(MockMvcResultMatchers.status().isBadRequest());
  }

  // ------- helpers -------

  private record Session(String token, String clientId) {}

  private Session signupAndCreateClient() throws Exception {
    String email = "alex+" + System.nanoTime() + "@example.com";
    MvcResult signup =
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
                                "Alex",
                                "workspace_name",
                                "Hayworth PR " + System.nanoTime()))))
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
    String token =
        json.readTree(signup.getResponse().getContentAsByteArray()).get("session_token").asText();

    MvcResult client =
        mvc.perform(
                MockMvcRequestBuilders.post("/v1/clients")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("name", "Acme Corp"))))
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
    String clientId =
        json.readTree(client.getResponse().getContentAsByteArray()).get("id").asText();
    return new Session(token, clientId);
  }
}
