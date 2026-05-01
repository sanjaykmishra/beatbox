package app.beat.integration;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for {@code @SpringBootTest} integration tests that need a Postgres database.
 *
 * <p>Why this exists rather than the per-class {@code @Testcontainers} + {@code @Container}
 * pattern: Spring's {@code @SpringBootTest} caches ApplicationContexts by configuration. With a
 * per-class container, each IT spins up its own Postgres on a fresh ephemeral port — but Spring
 * sees the configuration as identical across classes and reuses the cached context (Hikari pool,
 * DataSource, etc.) from the first run. When the next IT runs, the cached pool tries to connect to
 * the now-stopped first container's port and fails with "Connection refused" until Hikari times
 * out, which makes CI slow and flaky.
 *
 * <p>Solution: a single Postgres started in a static initializer at JVM init, never stopped. All
 * ITs share the same container, so Spring's cached context's DataSource stays valid for the whole
 * run. Tests still isolate via signup-scoped workspaces.
 */
abstract class IntegrationTestBase {

  /** Started once per JVM. {@code @ServiceConnection} wires its URL into the Spring DataSource. */
  @ServiceConnection static final PostgreSQLContainer<?> POSTGRES;

  static {
    POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    if (DockerClientFactory.instance().isDockerAvailable()) {
      POSTGRES.start();
    }
  }

  @DynamicPropertySource
  static void commonDynamicProps(DynamicPropertyRegistry r) {
    // Disable R2 in tests so the upload service skips its boot-time validation.
    r.add("beat.r2.account-id", () -> "");
  }

  /**
   * Used by {@code @EnabledIf("dockerAvailable")} on subclasses. The class will skip rather than
   * fail when Docker isn't installed (e.g. local laptop without Docker Desktop).
   */
  static boolean dockerAvailable() {
    try {
      return DockerClientFactory.instance().isDockerAvailable();
    } catch (Throwable t) {
      return false;
    }
  }
}
