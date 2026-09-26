package dev.docswatcher.app.gitlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Every GitLab endpoint DocsWatcher calls, with the exact request it sends and how it reads the answer. */
class GitLabApiTest {

  static final String API = "https://gitlab.example/api/v4";
  static final String TOKEN = "glpat-t";

  MockRestServiceServer server;
  GitLabApi api;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    // A trailing slash on the configured base is tolerated.
    api = new GitLabApi(new GitLabProperties("https://gitlab.example/", "cid", "csecret", null, Duration.ofHours(8)), builder);
  }

  @Test
  void the_token_describes_itself() {
    server.expect(requestTo(API + "/personal_access_tokens/self"))
        .andExpect(header("Authorization", "Bearer " + TOKEN))
        .andRespond(withSuccess("{\"id\":1,\"scopes\":[\"api\",\"read_repository\"],\"active\":true,\"revoked\":false,\"user_id\":42,\"expires_at\":\"2027-01-31\"}", MediaType.APPLICATION_JSON));
    GitLabApi.TokenInfo info = api.tokenSelf(TOKEN);
    assertThat(info.userId()).isEqualTo(42);
    assertThat(info.scopes()).containsExactly("api", "read_repository");
    assertThat(info.active()).isTrue();
    assertThat(info.expiresAt()).isEqualTo(LocalDate.of(2027, 1, 31));
  }

  @Test
  void a_revoked_token_is_not_active() {
    server.expect(requestTo(API + "/personal_access_tokens/self"))
        .andRespond(withSuccess("{\"scopes\":[\"api\"],\"active\":true,\"revoked\":true,\"user_id\":42,\"expires_at\":null}", MediaType.APPLICATION_JSON));
    assertThat(api.tokenSelf(TOKEN).active()).isFalse();
  }

  @Test
  void a_subgroup_path_travels_as_one_encoded_segment_and_a_missing_one_is_empty() {
    server.expect(requestTo(API + "/groups/acme%2Fplatform?with_projects=false"))
        .andRespond(withSuccess("{\"id\":55,\"full_path\":\"acme/platform\"}", MediaType.APPLICATION_JSON));
    server.expect(requestTo(API + "/groups/nobody?with_projects=false")).andRespond(withStatus(HttpStatus.NOT_FOUND));
    assertThat(api.group(TOKEN, "acme/platform")).contains(new GitLabApi.Namespace("group", 55, "acme/platform"));
    assertThat(api.group(TOKEN, "nobody")).isEmpty();
  }

  @Test
  void a_group_lists_its_projects_across_pages_and_an_empty_repository_has_no_branch() {
    String page1 = LongStream.rangeClosed(1, 100)
        .mapToObj(i -> "{\"id\":" + i + ",\"path_with_namespace\":\"acme/p" + i + "\",\"default_branch\":\"main\"}")
        .collect(Collectors.joining(",", "[", "]"));
    HttpHeaders next = new HttpHeaders();
    next.add("X-Next-Page", "2");
    server.expect(requestTo(API + "/groups/55/projects?include_subgroups=true&with_shared=false&archived=false&simple=true&per_page=100&page=1"))
        .andRespond(withSuccess(page1, MediaType.APPLICATION_JSON).headers(next));
    server.expect(requestTo(API + "/groups/55/projects?include_subgroups=true&with_shared=false&archived=false&simple=true&per_page=100&page=2"))
        .andRespond(withSuccess("[{\"id\":101,\"path_with_namespace\":\"acme/empty\",\"default_branch\":null}]", MediaType.APPLICATION_JSON));
    List<GitLabApi.Project> projects = api.groupProjects(TOKEN, 55);
    assertThat(projects).hasSize(101);
    assertThat(projects.getLast()).isEqualTo(new GitLabApi.Project(101, "acme/empty", null));
    server.verify();
  }

  @Test
  void a_role_is_read_from_the_inherited_members_list_and_no_membership_is_zero() {
    server.expect(requestTo(API + "/groups/55/members/all/42")).andRespond(withSuccess("{\"id\":42,\"access_level\":40}", MediaType.APPLICATION_JSON));
    server.expect(requestTo(API + "/projects/9/members/all/43")).andRespond(withStatus(HttpStatus.NOT_FOUND));
    assertThat(api.accessLevel(TOKEN, "group", 55, 42)).isEqualTo(40);
    assertThat(api.accessLevel(TOKEN, "project", 9, 43)).isZero();
  }

  @Test
  void an_issue_is_opened_with_comma_joined_labels_and_its_iid_returned() {
    server.expect(requestTo(API + "/projects/9/issues"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.title").value("[DocsWatcher] x"))
        .andExpect(jsonPath("$.description").value("body"))
        .andExpect(jsonPath("$.labels").value("docswatcher,docswatcher:breaking"))
        .andRespond(withSuccess("{\"id\":90001,\"iid\":7}", MediaType.APPLICATION_JSON));
    assertThat(api.createIssue(TOKEN, 9, "[DocsWatcher] x", "body", List.of("docswatcher", "docswatcher:breaking"))).isEqualTo(7);
  }

  @Test
  void closing_an_issue_says_why_first() {
    server.expect(requestTo(API + "/projects/9/issues/7/notes"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.body").value("Resolved."))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    server.expect(requestTo(API + "/projects/9/issues/7"))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(jsonPath("$.state_event").value("close"))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    api.closeIssue(TOKEN, 9, 7, "Resolved.");
    server.verify();
  }

  @Test
  void a_commit_status_is_posted_on_the_sha() {
    server.expect(requestTo(API + "/projects/9/statuses/abc123"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.state").value("failed"))
        .andExpect(jsonPath("$.name").value("DocsWatcher"))
        .andExpect(jsonPath("$.description").value("1 findings"))
        .andExpect(jsonPath("$.target_url").value("https://docswatcher.test/#/app"))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    api.commitStatus(TOKEN, 9, "abc123", "failed", "DocsWatcher", "1 findings", "https://docswatcher.test/#/app");
    server.verify();
  }

  @Test
  void the_code_exchange_sends_the_verifier_and_a_refusal_is_an_error() {
    server.expect(requestTo("https://gitlab.example/oauth/token"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().formDataContains(Map.of("client_id", "cid", "client_secret", "csecret", "code", "c", "grant_type", "authorization_code",
            "redirect_uri", "https://site/auth/gitlab/callback", "code_verifier", "v")))
        .andRespond(withSuccess("{\"access_token\":\"gloas-user\",\"token_type\":\"Bearer\",\"expires_in\":7200}", MediaType.APPLICATION_JSON));
    assertThat(api.exchangeCode("c", "v", "https://site/auth/gitlab/callback")).isEqualTo("gloas-user");

    server.reset();
    server.expect(requestTo("https://gitlab.example/oauth/token"))
        .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":\"invalid_grant\"}").contentType(MediaType.APPLICATION_JSON));
    assertThatThrownBy(() -> api.exchangeCode("old", "v", "https://site/auth/gitlab/callback"))
        .isInstanceOf(GitLabApi.GitLabException.class);
  }

  @Test
  void membership_is_listed_at_a_minimum_role() {
    server.expect(requestTo(API + "/projects?membership=true&simple=true&min_access_level=30&per_page=100&page=1"))
        .andExpect(header("Authorization", "Bearer gloas-user"))
        .andRespond(withSuccess("[{\"id\":9},{\"id\":10}]", MediaType.APPLICATION_JSON));
    assertThat(api.projectIdsAtLeast("gloas-user", 30)).containsExactlyInAnyOrder(9L, 10L);
  }

  @Test
  void the_clone_address_comes_from_the_configured_instance() {
    assertThat(api.cloneUri("acme/platform/api")).isEqualTo("https://gitlab.example/acme/platform/api.git");
  }
}
