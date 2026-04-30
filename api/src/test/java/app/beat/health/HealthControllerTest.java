package app.beat.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class HealthControllerTest {

  @Test
  void healthzReturnsStatusOk() {
    Map<String, Object> body = new HealthController().healthz();
    assertThat(body).containsEntry("status", "ok");
    assertThat(body).containsKey("time");
  }
}
