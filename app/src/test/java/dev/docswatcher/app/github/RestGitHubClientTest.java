package dev.docswatcher.app.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.docswatcher.app.config.AppProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The client against a mocked api.github.com. Every request must pin the REST API version: an
 * unpinned request gets whatever GitHub's default is that day, and a retired version is a 410.
 */
class RestGitHubClientTest {

  private static final String API = "https://api.github.test";

  private MockRestServiceServer server;
  private RestGitHubClient client;

  /** The builder the application gets, so URI encoding and converters are the production ones. */
  private static RestClient.Builder bootBuilder() {
    RestClient.Builder[] builder = new RestClient.Builder[1];
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
        .run(context -> builder[0] = context.getBean(RestClient.Builder.class));
    return builder[0];
  }

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = bootBuilder();
    server = MockRestServiceServer.bindTo(builder).build();
    AppProperties props =
        new AppProperties(
            new AppProperties.GitHub("4998498", GitHubAppJwtTest.PKCS1, "", API, "docswatcher:fix", "docswatcher:snooze", "docswatcher:not-in-prod"),
            null, null, null, null);
    client = new RestGitHubClient(props, builder);
  }

  private void expectToken() {
    server
        .expect(ExpectedCount.once(), requestTo(API + "/app/installations/7/access_tokens"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("X-GitHub-Api-Version", "2026-03-10"))
        .andExpect(header("Accept", "application/vnd.github+json"))
        .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
            .body("{\"token\":\"ghs_test\",\"expires_at\":\"2999-01-01T00:00:00Z\"}"));
  }

  private void expectPermission(String json) {
    expectToken();
    server
        .expect(requestTo(API + "/repos/o/r/collaborators/alice/permission"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-GitHub-Api-Version", "2026-03-10"))
        .andExpect(header("Authorization", "Bearer ghs_test"))
        .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
  }

  @Test
  void the_pinned_version_is_2026_03_10() {
    assertThat(RestGitHubClient.API_VERSION).isEqualTo("2026-03-10");
  }

  @Test
  void every_request_carries_the_version_header() {
    expectToken();
    server
        .expect(requestTo(API + "/installation/repositories?per_page=100&page=1"))
        .andExpect(header("X-GitHub-Api-Version", "2026-03-10"))
        .andRespond(withSuccess(
            "{\"total_count\":1,\"repositories\":[{\"id\":42,\"full_name\":\"o/r\",\"default_branch\":\"main\"}]}",
            MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(API + "/repos/o/r/issues"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("X-GitHub-Api-Version", "2026-03-10"))
        // 2026-03-10 dropped the singular "assignee" field from issues; we only read "number".
        .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
            .body("{\"number\":12,\"assignees\":[]}"));
    server
        .expect(requestTo(API + "/repos/o/r/issues/12/comments"))
        .andExpect(header("X-GitHub-Api-Version", "2026-03-10"))
        .andRespond(withStatus(HttpStatus.CREATED));
    server
        .expect(requestTo(API + "/repos/o/r/issues/12"))
        .andExpect(method(HttpMethod.PATCH))
        .andExpect(header("X-GitHub-Api-Version", "2026-03-10"))
        .andRespond(withSuccess("{\"number\":12}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(API + "/repos/o/r/issues/12/labels"))
        .andExpect(header("X-GitHub-Api-Version", "2026-03-10"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(API + "/repos/o/r/check-runs"))
        .andExpect(header("X-GitHub-Api-Version", "2026-03-10"))
        .andRespond(withStatus(HttpStatus.CREATED));
    server
        .expect(requestTo(API + "/repos/o/r/dispatches"))
        .andExpect(header("X-GitHub-Api-Version", "2026-03-10"))
        .andRespond(withStatus(HttpStatus.NO_CONTENT));

    assertThat(client.listInstallationRepos(7)).containsExactly(new GitHubClient.InstallationRepo(42, "o/r", "main"));
    assertThat(client.createIssue(7, "o/r", "t", "b", List.of("docswatcher"))).isEqualTo(12);
    client.closeIssue(7, "o/r", 12, "fixed");
    client.addLabels(7, "o/r", 12, List.of("x"));
    client.createCheckRun(7, "o/r", "abc", "DocsWatcher", "success", "t", "s");
    client.repositoryDispatch(7, "o/r", "docswatcher", java.util.Map.of());
    server.verify();
  }

  @ParameterizedTest
  @ValueSource(strings = {"admin", "maintain", "write", "triage", "read", "none"})
  void a_documented_permission_is_returned_as_is(String permission) {
    expectPermission("{\"permission\":\"" + permission + "\",\"role_name\":\"" + permission + "\"}");
    assertThat(client.collaboratorPermission(7, "o/r", "alice")).isEqualTo(permission);
    server.verify();
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "{\"permission\":\"superuser\"}",
    "{\"permission\":\"ADMIN\"}",
    "{\"permission\":null}",
    "{\"permission\":1}",
    "{\"permission\":{\"admin\":true}}",
    "{\"role_name\":\"admin\"}",
    "{}"
  })
  void anything_unexpected_is_no_permission(String json) {
    expectPermission(json);
    assertThat(client.collaboratorPermission(7, "o/r", "alice")).isEqualTo("none");
    server.verify();
  }

  @Test
  void the_repository_name_reaches_github_as_two_path_segments() {
    // One "{repo}" variable holding "o/r" was sent as "/repos/o%2Fr/...", which GitHub 404s.
    assertThat(RestGitHubClient.repo("o/r", 12)).containsExactly("o", "r", 12);
  }

  @ParameterizedTest
  @ValueSource(strings = {"o", "/r", "o/", "o/r/x", ""})
  void a_name_that_is_not_owner_slash_repo_is_rejected(String fullName) {
    assertThatThrownBy(() -> RestGitHubClient.repo(fullName)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_malformed_name_is_no_permission_and_calls_nothing() {
    assertThat(client.collaboratorPermission(7, "o/r/x", "alice")).isEqualTo("none");
    server.verify();
  }

  @Test
  void a_failed_lookup_is_no_permission() {
    expectToken();
    server.expect(requestTo(API + "/repos/o/r/collaborators/alice/permission")).andRespond(withServerError());
    assertThat(client.collaboratorPermission(7, "o/r", "alice")).isEqualTo("none");
  }

  @Test
  void a_retired_api_version_is_no_permission() {
    // What GitHub answers once a pinned version is no longer supported.
    expectToken();
    server.expect(requestTo(API + "/repos/o/r/collaborators/alice/permission")).andRespond(withStatus(HttpStatus.GONE));
    assertThat(client.collaboratorPermission(7, "o/r", "alice")).isEqualTo("none");
  }
}
