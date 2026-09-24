package dev.docswatcher.app.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.docswatcher.app.config.AppProperties;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Installation tokens are cached, but never past their expiry and never past the installation's own changes. */
class RestGitHubClientTokenTest {

  private static final String API = "https://api.github.test";

  private MockRestServiceServer server;
  private RestGitHubClient client;

  @BeforeEach
  void setUp() throws Exception {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    client = new RestGitHubClient(properties(), builder);
  }

  @Test
  void aTokenFarFromExpiryIsReused() {
    expectMint(5001, "tok-1", Instant.now().plus(Duration.ofHours(1)));

    assertThat(token(5001)).isEqualTo("tok-1");
    assertThat(token(5001)).isEqualTo("tok-1");
    server.verify();
  }

  @Test
  void aTokenInsideTheRefreshMarginIsReplacedBeforeItExpires() {
    Instant soon = Instant.now().plus(RestGitHubClient.REFRESH_MARGIN).minus(Duration.ofSeconds(30));
    expectMint(5001, "tok-1", soon);
    expectMint(5001, "tok-2", Instant.now().plus(Duration.ofHours(1)));

    assertThat(token(5001)).isEqualTo("tok-1");
    assertThat(token(5001)).isEqualTo("tok-2");
    server.verify();
  }

  @Test
  void forgettingAnInstallationMintsAFreshToken() {
    expectMint(5001, "tok-1", Instant.now().plus(Duration.ofHours(1)));
    expectMint(5001, "tok-2", Instant.now().plus(Duration.ofHours(1)));

    assertThat(token(5001)).isEqualTo("tok-1");
    client.forgetInstallation(5001);
    assertThat(token(5001)).isEqualTo("tok-2");
    server.verify();
  }

  @Test
  void forgettingOneInstallationLeavesAnotherCached() {
    expectMint(5001, "a-1", Instant.now().plus(Duration.ofHours(1)));
    expectMint(6001, "b-1", Instant.now().plus(Duration.ofHours(1)));
    expectMint(5001, "a-2", Instant.now().plus(Duration.ofHours(1)));

    assertThat(token(5001)).isEqualTo("a-1");
    assertThat(token(6001)).isEqualTo("b-1");
    client.forgetInstallation(5001);
    assertThat(token(6001)).isEqualTo("b-1");
    assertThat(token(5001)).isEqualTo("a-2");
    server.verify();
  }

  @Test
  void aResponseWithoutAnExpiryIsUsedOnceAndNotCached() {
    server.expect(once(), requestTo(API + "/app/installations/5001/access_tokens"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{\"token\":\"tok-1\"}", MediaType.APPLICATION_JSON));
    expectMint(5001, "tok-2", Instant.now().plus(Duration.ofHours(1)));

    assertThat(token(5001)).isEqualTo("tok-1");
    assertThat(token(5001)).isEqualTo("tok-2");
    server.verify();
  }

  private String token(long installationId) {
    return client.cloneSource(installationId, "acme/app").password();
  }

  private void expectMint(long installationId, String token, Instant expiresAt) {
    server.expect(once(), requestTo(API + "/app/installations/" + installationId + "/access_tokens"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{\"token\":\"" + token + "\",\"expires_at\":\"" + expiresAt + "\"}", MediaType.APPLICATION_JSON));
  }

  private static AppProperties properties() throws Exception {
    // A key made for this test alone; it signs JWTs that nothing verifies.
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    String pem = "-----BEGIN PRIVATE KEY-----\n"
        + Base64.getMimeEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
        + "\n-----END PRIVATE KEY-----\n";
    return new AppProperties(new AppProperties.GitHub("1", pem, "secret", API, "fix", "snooze", "not-in-prod"), null, null, null, null);
  }
}
