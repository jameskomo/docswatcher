package dev.docswatcher.app.gitlab;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * GitLab: one instance per deployment, gitlab.com or a self-managed one (docs/adr/0012-gitlab.md).
 *
 * <p>The two secrets are read like the GitHub App's, from files the config tree maps onto these
 * properties: {@code /run/secrets/docswatcher.gitlab.client-secret} and
 * {@code /run/secrets/docswatcher.gitlab.token-key}.
 *
 * @param baseUrl the instance's web address, without a trailing slash; the API is under /api/v4
 * @param clientId the OAuth application's id, public
 * @param clientSecret the OAuth application's secret; sign-in answers 503 without it
 * @param tokenKey base64 of 32 random bytes, the AES-256 key the connections' access tokens are
 *     encrypted under; without it no group can be connected and none can be scanned
 * @param sessionTtl how long a session signed in with GitLab lasts
 */
@ConfigurationProperties(prefix = "docswatcher.gitlab")
public record GitLabProperties(
    @DefaultValue("https://gitlab.com") String baseUrl,
    String clientId,
    String clientSecret,
    String tokenKey,
    @DefaultValue("8h") Duration sessionTtl) {

  public GitLabProperties {
    if (baseUrl == null || baseUrl.isBlank()) {
      baseUrl = "https://gitlab.com";
    }
    while (baseUrl.endsWith("/")) {
      baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
    }
    if (sessionTtl == null) {
      sessionTtl = Duration.ofHours(8);
    }
  }

  public String apiBase() {
    return baseUrl + "/api/v4";
  }

  /** Both halves of the OAuth application present. */
  public boolean signInEnabled() {
    return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
  }
}
