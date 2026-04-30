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
class AuthFlowIT {

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
  void signupLoginCreateClientLogout() throws Exception {
    String email = "alex+" + System.nanoTime() + "@example.com";

    MvcResult signup =
        mvc.perform(
                MockMvcRequestBuilders.post("/v1/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "email", email,
                                "password", "supersecret",
                                "name", "Alex",
                                "workspace_name", "Hayworth PR"))))
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andExpect(MockMvcResultMatchers.header().exists("X-Request-ID"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.workspace.slug").value("hayworth-pr"))
            .andReturn();

    String token =
        json.readTree(signup.getResponse().getContentAsByteArray()).get("session_token").asText();
    assertThat(token).isNotBlank();

    mvc.perform(
            MockMvcRequestBuilders.get("/v1/workspace").header("Authorization", "Bearer " + token))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.name").value("Hayworth PR"));

    mvc.perform(MockMvcRequestBuilders.get("/v1/workspace"))
        .andExpect(MockMvcResultMatchers.status().isUnauthorized())
        .andExpect(MockMvcResultMatchers.jsonPath("$.request_id").exists());

    MvcResult create =
        mvc.perform(
                MockMvcRequestBuilders.post("/v1/clients")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("name", "Acme Corp"))))
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn();
    JsonNode created = json.readTree(create.getResponse().getContentAsByteArray());
    String clientId = created.get("id").asText();

    mvc.perform(
            MockMvcRequestBuilders.patch("/v1/clients/" + clientId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("primary_color", "1F2937", "default_cadence", "monthly"))))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.primary_color").value("1F2937"))
        .andExpect(MockMvcResultMatchers.jsonPath("$.default_cadence").value("monthly"));

    mvc.perform(
            MockMvcRequestBuilders.get("/v1/clients").header("Authorization", "Bearer " + token))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.items.length()").value(1));

    mvc.perform(
            MockMvcRequestBuilders.delete("/v1/clients/" + clientId)
                .header("Authorization", "Bearer " + token))
        .andExpect(MockMvcResultMatchers.status().isNoContent());

    mvc.perform(
            MockMvcRequestBuilders.get("/v1/clients").header("Authorization", "Bearer " + token))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.items.length()").value(0));

    mvc.perform(
            MockMvcRequestBuilders.post("/v1/auth/logout")
                .header("Authorization", "Bearer " + token))
        .andExpect(MockMvcResultMatchers.status().isNoContent());

    mvc.perform(
            MockMvcRequestBuilders.get("/v1/workspace").header("Authorization", "Bearer " + token))
        .andExpect(MockMvcResultMatchers.status().isUnauthorized());
  }

  @Test
  void signupRejectsDuplicateEmail() throws Exception {
    String email = "dup+" + System.nanoTime() + "@example.com";
    String body =
        json.writeValueAsString(
            Map.of(
                "email", email,
                "password", "supersecret",
                "name", "First",
                "workspace_name", "First WS"));

    mvc.perform(
            MockMvcRequestBuilders.post("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(MockMvcResultMatchers.status().isCreated());

    mvc.perform(
            MockMvcRequestBuilders.post("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(MockMvcResultMatchers.status().isConflict())
        .andExpect(MockMvcResultMatchers.jsonPath("$.title").value("Email already in use"));
  }

  @Test
  void loginRejectsBadPassword() throws Exception {
    String email = "bad+" + System.nanoTime() + "@example.com";
    mvc.perform(
            MockMvcRequestBuilders.post("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "email", email,
                            "password", "supersecret",
                            "name", "Sam",
                            "workspace_name", "Sam Co"))))
        .andExpect(MockMvcResultMatchers.status().isCreated());

    mvc.perform(
            MockMvcRequestBuilders.post("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", "wrong-one"))))
        .andExpect(MockMvcResultMatchers.status().isUnauthorized());
  }
}
