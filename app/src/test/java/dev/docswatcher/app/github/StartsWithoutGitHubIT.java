package dev.docswatcher.app.github;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The application must start with no GitHub App configured, because a deployment that
 * serves only the public site and the dashboard API has no reason to carry credentials.
 *
 * <p>This deliberately does not extend PostgresTest: that base class imports the test
 * fakes, and a fake GitHubClient would satisfy the dependency and hide the very failure
 * this guards against. A unit test of the fallback bean is also not enough, because the
 * real failure was a bean name collision that only appears once Spring boots.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "docswatcher.github.app-id=",
    "docswatcher.github.private-key=",
    "docswatcher.github.webhook-secret=",
    "docswatcher.worker.enabled=false",
    // The test profile disables the engine so other tests can use a fake. Enable the
    // real one here: this test exists to boot the production wiring, not a stand-in.
    "docswatcher.engine.enabled=true",
})
class StartsWithoutGitHubIT {

  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired GitHubClient client;

  @Test
  void context_loads_and_the_fallback_client_is_wired() {
    assertThat(client).isNotNull();
    assertThat(client.getClass().getSimpleName()).isEqualTo("Unconfigured");
  }
}
