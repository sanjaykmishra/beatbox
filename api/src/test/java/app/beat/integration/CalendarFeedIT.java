package app.beat.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
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

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIf("dockerAvailable")
class CalendarFeedIT extends IntegrationTestBase {

  @Autowired MockMvc mvc;
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void crudCalendarEventAndSeeItInFeed() throws Exception {
    var session = signupAndCreateClient();
    String token = session.token();

    // Create an embargo standalone event.
    MvcResult created =
        mvc.perform(
                MockMvcRequestBuilders.post("/v1/calendar/events")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "client_id", session.clientId(),
                                "event_type", "embargo",
                                "title", "Series B embargo lifts",
                                "occurs_at", "2026-12-15T16:00:00Z"))))
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andExpect(MockMvcResultMatchers.jsonPath("$.event_type").value("embargo"))
            .andReturn();
    String eventId =
        json.readTree(created.getResponse().getContentAsByteArray()).get("id").asText();

    // Feed should include it.
    MvcResult feed =
        mvc.perform(
                MockMvcRequestBuilders.get(
                        "/v1/calendar/feed?from=2026-12-01T00:00:00Z&to=2027-01-01T00:00:00Z")
                    .header("Authorization", "Bearer " + token))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
    JsonNode body = json.readTree(feed.getResponse().getContentAsByteArray());
    JsonNode items = body.get("items");
    boolean found = false;
    for (JsonNode i : items) {
      if (eventId.equals(i.get("source_id").asText()) && "embargo".equals(i.get("type").asText())) {
        found = true;
        break;
      }
    }
    assertThat(found).as("feed should contain the embargo we just created").isTrue();
    // The full available_types list should include all the registered sources.
    java.util.Set<String> available = new java.util.HashSet<>();
    body.get("available_types").forEach(t -> available.add(t.asText()));
    assertThat(available).contains("post", "report_due", "embargo", "launch", "other");

    // Filter to types=launch — embargo should NOT appear.
    MvcResult filtered =
        mvc.perform(
                MockMvcRequestBuilders.get(
                        "/v1/calendar/feed?types=launch&from=2026-12-01T00:00:00Z&to=2027-01-01T00:00:00Z")
                    .header("Authorization", "Bearer " + token))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
    JsonNode filteredItems =
        json.readTree(filtered.getResponse().getContentAsByteArray()).get("items");
    for (JsonNode i : filteredItems) {
      assertThat(i.get("type").asText()).isEqualTo("launch");
    }

    // PATCH title.
    mvc.perform(
            MockMvcRequestBuilders.patch("/v1/calendar/events/" + eventId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("title", "Series B embargo (revised)"))))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.title").value("Series B embargo (revised)"));

    // Reject end-before-start on PATCH.
    mvc.perform(
            MockMvcRequestBuilders.patch("/v1/calendar/events/" + eventId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("ends_at", "2026-12-01T00:00:00Z"))))
        .andExpect(MockMvcResultMatchers.status().isBadRequest());

    // Reject unknown event_type on POST.
    mvc.perform(
            MockMvcRequestBuilders.post("/v1/calendar/events")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "client_id", session.clientId(),
                            "event_type", "myspace_post",
                            "title", "Bad type",
                            "occurs_at", "2026-12-15T16:00:00Z"))))
        .andExpect(MockMvcResultMatchers.status().isBadRequest());

    // Soft-delete.
    mvc.perform(
            MockMvcRequestBuilders.delete("/v1/calendar/events/" + eventId)
                .header("Authorization", "Bearer " + token))
        .andExpect(MockMvcResultMatchers.status().isNoContent());
    mvc.perform(
            MockMvcRequestBuilders.get("/v1/calendar/events/" + eventId)
                .header("Authorization", "Bearer " + token))
        .andExpect(MockMvcResultMatchers.status().isNotFound());
  }

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
                    .content(json.writeValueAsString(Map.of("name", "Acme"))))
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
    String clientId =
        json.readTree(client.getResponse().getContentAsByteArray()).get("id").asText();
    return new Session(token, clientId);
  }
}
