package dev.docswatcher.app.gitlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.docswatcher.app.auth.Cookies;
import dev.docswatcher.app.auth.SessionStore;
import dev.docswatcher.app.auth.UserSession;
import dev.docswatcher.app.store.ScanRun;
import dev.docswatcher.app.support.GitLabMock;
import dev.docswatcher.app.support.GitLabSeed;
import dev.docswatcher.app.support.PostgresTest;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Connecting a GitLab group with a maintainer's access token, and who may see and remove it. */
@AutoConfigureMockMvc
class GitLabConnectIT extends PostgresTest {

  static final String API = GitLabMock.API;
  static final String SITE = "https://docswatcher.test";
  static final String TOKEN = "glpat-group-token-for-acme";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired GitLabMock gitlab;
  @Autowired GitLabStore store;
  @Autowired TokenCipher cipher;
  @Autowired SessionStore sessions;
  @Autowired ObjectMapper mapper;

  @BeforeEach
  void setUp() {
    jdbc.sql("delete from installation").update();
    jdbc.sql("delete from user_session").update();
    gitlab.reset();
  }

  private MockHttpServletRequestBuilder asOwner(MockHttpServletRequestBuilder request) {
    return request.header("Authorization", "Bearer test-token");
  }

  private MockHttpServletRequestBuilder connect(String namespace, String token) {
    return post("/api/gitlab/connections").contentType(MediaType.APPLICATION_JSON)
        .content("{\"namespace\":\"" + namespace + "\",\"token\":\"" + token + "\"}");
  }

  private void tokenIs(String scopes, int accessLevel) {
    gitlab.server.expect(requestTo(API + "/personal_access_tokens/self"))
        .andExpect(header("Authorization", "Bearer " + TOKEN))
        .andRespond(withSuccess("{\"scopes\":[" + scopes + "],\"active\":true,\"revoked\":false,\"user_id\":5150,\"expires_at\":\"2027-03-01\"}",
            MediaType.APPLICATION_JSON));
    gitlab.server.expect(requestTo(API + "/groups/acme?with_projects=false"))
        .andRespond(withSuccess("{\"id\":55,\"full_path\":\"acme\"}", MediaType.APPLICATION_JSON));
    gitlab.server.expect(requestTo(API + "/groups/55/members/all/5150"))
        .andRespond(withSuccess("{\"id\":5150,\"access_level\":" + accessLevel + "}", MediaType.APPLICATION_JSON));
  }

  private void projects() {
    gitlab.server.expect(requestTo(API + "/groups/55/projects?include_subgroups=true&with_shared=false&archived=false&simple=true&per_page=100&page=1"))
        .andRespond(withSuccess("""
            [{"id":901,"path_with_namespace":"acme/api","default_branch":"main"},
             {"id":902,"path_with_namespace":"acme/platform/web","default_branch":"trunk"},
             {"id":903,"path_with_namespace":"acme/empty","default_branch":null}]
            """, MediaType.APPLICATION_JSON));
  }

  @Test
  void a_maintainer_token_connects_the_group_stores_the_token_encrypted_and_queues_a_scan_per_project() throws Exception {
    tokenIs("\"api\",\"read_repository\"", 40);
    projects();
    String body = mvc.perform(asOwner(connect("acme", TOKEN)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.login").value("acme"))
        .andExpect(jsonPath("$.kind").value("group"))
        .andExpect(jsonPath("$.namespace").value("acme"))
        .andExpect(jsonPath("$.projects").value(2))
        .andExpect(jsonPath("$.tokenExpiresAt").value("2027-03-01"))
        .andExpect(jsonPath("$.webhookUrl").value(SITE + "/webhooks/gitlab"))
        .andReturn().getResponse().getContentAsString();
    gitlab.server.verify();
    JsonNode answer = mapper.readTree(body);
    String webhookToken = answer.path("webhookToken").asString();
    assertThat(webhookToken).hasSizeGreaterThanOrEqualTo(43);
    assertThat(body).doesNotContain(TOKEN);
    long id = answer.path("id").asLong();
    assertThat(id).isNegative();

    // The access token is encrypted; neither it nor the webhook token is in the database as text.
    assertThat(jdbc.sql("select count(*) from gitlab_connection where position(convert_to(:t, 'UTF8') in token_ciphertext) > 0")
        .param("t", TOKEN).query(Long.class).single()).isZero();
    GitLabStore.Connection c = store.find(id).orElseThrow();
    assertThat(cipher.decrypt(c.tokenCiphertext(), id)).isEqualTo(TOKEN);
    assertThat(store.webhookTokenHash(id)).isEqualTo(GitLabSeed.sha256(webhookToken));
    assertThat(c.tokenUserId()).isEqualTo(5150);

    // Each project with a branch is a GitLab repository with a first scan queued; the empty one waits.
    assertThat(store.findProject(901).orElseThrow().fullName()).isEqualTo("acme/api");
    assertThat(store.findProject(902).orElseThrow().defaultBranch()).isEqualTo("trunk");
    assertThat(store.findProject(903)).isEmpty();
    assertThat(jdbc.sql("select count(*) from scan_run where trigger = :t").param("t", ScanRun.TRIGGER_INSTALL).query(Long.class).single()).isEqualTo(2);

    // Connecting again replaces the token, keeps the webhook token, and adds only what is new.
    gitlab.server.reset();
    tokenIs("\"api\"", 50);
    projects();
    mvc.perform(asOwner(connect("acme", TOKEN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.webhookToken").doesNotExist());
    assertThat(store.webhookTokenHash(id)).isEqualTo(GitLabSeed.sha256(webhookToken));
    assertThat(jdbc.sql("select count(*) from scan_run").query(Long.class).single()).isEqualTo(2);
  }

  @Test
  void a_token_below_maintainer_is_refused() throws Exception {
    tokenIs("\"api\"", 30);
    mvc.perform(asOwner(connect("acme", TOKEN)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("The token's role on acme is below Maintainer."));
    assertThat(jdbc.sql("select count(*) from gitlab_connection").query(Long.class).single()).isZero();
  }

  @Test
  void a_token_without_the_api_scope_is_refused() throws Exception {
    gitlab.server.expect(requestTo(API + "/personal_access_tokens/self"))
        .andRespond(withSuccess("{\"scopes\":[\"read_api\"],\"active\":true,\"user_id\":5150}", MediaType.APPLICATION_JSON));
    mvc.perform(asOwner(connect("acme", TOKEN))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(
        "The token needs the api scope, to open issues and set commit statuses."));
  }

  @Test
  void a_token_gitlab_does_not_accept_is_refused_without_echoing_it() throws Exception {
    gitlab.server.expect(requestTo(API + "/personal_access_tokens/self")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
    String body = mvc.perform(asOwner(connect("acme", TOKEN))).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
    assertThat(body).contains("GitLab did not accept this token.").doesNotContain(TOKEN);
  }

  @Test
  void a_path_that_is_not_a_gitlab_path_is_refused_before_gitlab_is_asked() throws Exception {
    mvc.perform(asOwner(connect("../admin", TOKEN))).andExpect(status().isBadRequest());
    mvc.perform(asOwner(connect("acme", ""))).andExpect(status().isBadRequest());
    gitlab.server.verify();
  }

  @Test
  void signed_out_nobody_connects() throws Exception {
    mvc.perform(connect("acme", TOKEN)).andExpect(status().isUnauthorized());
  }

  private Cookie member(String provider, long userId, List<UserSession.OrgAccess> orgs) {
    return new Cookie(Cookies.SESSION, sessions.create(userId, "person" + userId, null, null, orgs, Duration.ofHours(1), provider));
  }

  @Test
  void members_see_only_their_organisations_connections_and_only_a_gitlab_maintainer_disconnects() throws Exception {
    GitLabSeed.Seeded acme = GitLabSeed.group(store, cipher, "acme", 55);
    GitLabSeed.Seeded globex = GitLabSeed.group(store, cipher, "globex", 66);
    long repo = store.addProject(acme.id(), 901, "acme/api", "main").orElseThrow();
    List<UserSession.OrgAccess> seesAcme = List.of(new UserSession.OrgAccess(acme.id(), "acme", List.of(new UserSession.RepoAccess(repo, "acme/api", "write"))));

    Cookie developer = member("gitlab", 42, seesAcme);
    mvc.perform(get("/api/gitlab/connections").cookie(developer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].namespace").value("acme"))
        .andExpect(jsonPath("$[0].webhookToken").doesNotExist());
    mvc.perform(asOwner(get("/api/gitlab/connections"))).andExpect(jsonPath("$.length()").value(2));

    // GitLab says 42 is a Developer on acme: not enough to disconnect it.
    gitlab.server.expect(requestTo(API + "/groups/55/members/all/42"))
        .andExpect(header("Authorization", "Bearer " + GitLabSeed.ACCESS_TOKEN))
        .andRespond(withSuccess("{\"access_level\":30}", MediaType.APPLICATION_JSON));
    mvc.perform(post("/api/gitlab/connections/" + acme.id() + "/disconnect").cookie(developer).header("Origin", SITE))
        .andExpect(status().isForbidden());
    // globex is not theirs to see, so it does not exist for them; a GitHub session is no GitLab maintainer.
    mvc.perform(post("/api/gitlab/connections/" + globex.id() + "/disconnect").cookie(developer).header("Origin", SITE))
        .andExpect(status().isNotFound());
    mvc.perform(post("/api/gitlab/connections/" + acme.id() + "/disconnect").cookie(member("github", 42, seesAcme)).header("Origin", SITE))
        .andExpect(status().isNotFound());
    // From another site, a member's POST never arrives.
    mvc.perform(post("/api/gitlab/connections/" + acme.id() + "/disconnect").cookie(developer).header("Origin", "https://evil.test"))
        .andExpect(status().isForbidden());

    gitlab.server.verify();
    gitlab.server.reset();
    Cookie maintainer = member("gitlab", 43, seesAcme);
    gitlab.server.expect(requestTo(API + "/groups/55/members/all/43")).andRespond(withSuccess("{\"access_level\":40}", MediaType.APPLICATION_JSON));
    mvc.perform(post("/api/gitlab/connections/" + acme.id() + "/disconnect").cookie(maintainer).header("Origin", SITE))
        .andExpect(status().isNoContent());
    gitlab.server.verify();
    assertThat(store.find(acme.id())).isEmpty();
    assertThat(store.findProject(901)).isEmpty();
    assertThat(jdbc.sql("select count(*) from repo where id = :r").param("r", repo).query(Long.class).single()).isZero();

    mvc.perform(asOwner(post("/api/gitlab/connections/" + globex.id() + "/disconnect"))).andExpect(status().isNoContent());
  }
}
