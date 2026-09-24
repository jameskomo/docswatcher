package dev.docswatcher.app.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.docswatcher.app.auth.OAuthProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GitHubUserApiTest {

  static final String API = "https://api.github.test";

  MockRestServiceServer server;
  GitHubUserApi api;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    api = new GitHubUserApi(new OAuthProperties("id", "secret", "https://github.test", API, Duration.ofHours(8)), builder);
  }

  @Test
  void repositories_follow_pages_until_the_total_is_reached() {
    String page1 = IntStream.range(0, 100)
        .mapToObj(i -> "{\"id\":" + i + ",\"full_name\":\"o/r" + i + "\",\"permissions\":{\"pull\":true}}")
        .collect(Collectors.joining(",", "{\"total_count\":101,\"repositories\":[", "]}"));
    server.expect(requestTo(API + "/user/installations/7/repositories?per_page=100&page=1")).andRespond(withSuccess(page1, MediaType.APPLICATION_JSON));
    server.expect(requestTo(API + "/user/installations/7/repositories?per_page=100&page=2"))
        .andRespond(withSuccess("{\"total_count\":101,\"repositories\":[{\"id\":100,\"full_name\":\"o/last\",\"permissions\":{\"admin\":true,\"pull\":true}}]}", MediaType.APPLICATION_JSON));
    List<GitHubUserApi.UserRepo> repos = api.repositories("t", 7);
    assertThat(repos).hasSize(101);
    assertThat(repos.getLast()).isEqualTo(new GitHubUserApi.UserRepo(100, "o/last", "admin"));
    server.verify();
  }

  @Test
  void permission_is_the_highest_level_granted() {
    assertThat(GitHubUserApi.permission(Map.of("pull", true, "triage", true, "push", true))).isEqualTo("write");
    assertThat(GitHubUserApi.permission(Map.of("pull", true, "maintain", true))).isEqualTo("maintain");
    assertThat(GitHubUserApi.permission(Map.of("pull", true))).isEqualTo("read");
    assertThat(GitHubUserApi.permission(Map.of("pull", false))).isEqualTo("none");
  }

  @Test
  void a_github_error_is_a_sign_in_failure_not_a_crash() {
    server.expect(requestTo(API + "/user")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
    assertThatThrownBy(() -> api.user("t")).isInstanceOf(GitHubUserApi.GitHubAuthException.class);
  }
}
