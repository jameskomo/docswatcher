package dev.docswatcher.app.github;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Stands in for the real client when no GitHub App is configured.
 *
 * <p>Without this the application refuses to start at all, because four components
 * depend on {@link GitHubClient} and the real one only exists when an app id is set.
 * A deployment that only serves the public site and the dashboard API has no reason
 * to carry GitHub credentials, so the absence of them must not be fatal.
 *
 * <p>Every call fails loudly with an actionable message rather than silently doing
 * nothing, so a half-configured deployment cannot look like it is working.
 *
 * <p>The class is deliberately not named after its bean method. Spring registers a
 * configuration class under its own decapitalised name, so a class named
 * UnconfiguredGitHubClient holding a bean method of the same name collides with
 * itself and the context refuses to start.
 */
@Configuration
public class GitHubFallbackConfig {

  private static final Logger log = LoggerFactory.getLogger(GitHubFallbackConfig.class);

  private static final String MESSAGE =
      "GitHub is not configured. Set GITHUB_APP_ID, GITHUB_APP_PRIVATE_KEY and "
          + "GITHUB_WEBHOOK_SECRET to enable repository scanning, issues and fix dispatch.";

  @Bean
  @ConditionalOnMissingBean(GitHubClient.class)
  GitHubClient unconfiguredGitHubClient(
      @Value("${docswatcher.github.app-id:}") String appId,
      @Value("${docswatcher.github.private-key:}") String privateKey) {
    String missing =
        appId.isBlank() && privateKey.isBlank()
            ? "no app id and no private key"
            : appId.isBlank() ? "no app id" : "an app id but no private key";
    log.warn(
        "GitHub App incomplete ({}). The site and the dashboard API are available; "
            + "installation scanning, issues and fix pull requests are not.",
        missing);
    return new Unconfigured();
  }

  private static final class Unconfigured implements GitHubClient {

    private static IllegalStateException fail() {
      return new IllegalStateException(MESSAGE);
    }

    @Override
    public List<InstallationRepo> listInstallationRepos(long installationId) {
      throw fail();
    }

    @Override
    public CloneSource cloneSource(long installationId, String fullName) {
      throw fail();
    }

    @Override
    public void createCheckRun(long installationId, String fullName, String headSha, String name, String conclusion, String title, String summary) {
      throw fail();
    }

    @Override
    public int createIssue(long installationId, String fullName, String title, String body, List<String> labels) {
      throw fail();
    }

    @Override
    public void closeIssue(long installationId, String fullName, int issueNumber, String comment) {
      throw fail();
    }

    @Override
    public void addLabels(long installationId, String fullName, int issueNumber, List<String> labels) {
      throw fail();
    }

    @Override
    public void repositoryDispatch(long installationId, String fullName, String eventType, Map<String, Object> clientPayload) {
      throw fail();
    }

    @Override
    public String collaboratorPermission(long installationId, String fullName, String login) {
      // Unconfigured means no authority can be established, and no authority means no.
      return "none";
    }
  }
}
