package dev.docswatcher.app.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.docswatcher.app.gitlab.GitLabStore;
import dev.docswatcher.app.gitlab.TokenCipher;
import dev.docswatcher.app.store.ContractStore;
import dev.docswatcher.app.store.FindingStore;
import dev.docswatcher.app.store.InstallationStore;
import dev.docswatcher.app.store.RepoStore;
import dev.docswatcher.app.store.StoredContract;
import dev.docswatcher.app.support.FakeScanEngine;
import dev.docswatcher.app.support.GitLabMock;
import dev.docswatcher.app.support.GitLabSeed;
import dev.docswatcher.app.support.PostgresTest;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Sign-in with GitLab against a scripted gitlab.test, and what the session it starts may reach:
 * the connected projects GitLab says the person is a member of, at the role GitLab gives them.
 */
@AutoConfigureMockMvc
class GitLabSignInIT extends PostgresTest {

  static final String BASE = GitLabMock.BASE;
  static final String API = GitLabMock.API;
  static final String SITE = "https://docswatcher.test";
  static final String EVIDENCE = "[{\"path\":\"app.py\",\"line\":3,\"column\":1,\"snippet\":\"model='gpt-4-turbo'\",\"detector\":\"d\",\"layer\":\"literal\"}]";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired GitLabMock gitlab;
  @Autowired GitLabStore store;
  @Autowired TokenCipher cipher;
  @Autowired InstallationStore installations;
  @Autowired RepoStore repos;
  @Autowired ContractStore contracts;
  @Autowired FindingStore findings;

  long api;
  long web;
  long infra;
  long other;

  @BeforeEach
  void seed() {
    jdbc.sql("delete from installation").update();
    jdbc.sql("delete from user_session").update();
    gitlab.reset();
    GitLabSeed.Seeded acme = GitLabSeed.group(store, cipher, "acme", 55);
    GitLabSeed.Seeded globex = GitLabSeed.group(store, cipher, "globex", 66);
    api = store.addProject(acme.id(), 901, "acme/api", "main").orElseThrow();
    web = store.addProject(acme.id(), 902, "acme/platform/web", "main").orElseThrow();
    infra = store.addProject(acme.id(), 903, "acme/infra", "main").orElseThrow();
    other = store.addProject(globex.id(), 904, "globex/secret", "main").orElseThrow();
    for (long r : new long[] {api, web, infra, other}) {
      contracts.upsert(new StoredContract(r, FakeScanEngine.CONTRACT_ID, "openai", "model", "gpt-4-turbo", "high", EVIDENCE, null, "s", "s"));
      findings.insertOpen(r, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID, "f" + r, "breaking", LocalDate.of(2026, 10, 23));
    }
    // A GitHub organisation of the same name, which a GitLab session must not reach.
    installations.upsert(5001, "acme");
    repos.upsert(9001, 5001, "acme/api", "main");
  }

  private record Started(String state, Cookie cookie) {}

  private Started start() throws Exception {
    MockHttpServletResponse res = mvc.perform(get("/auth/gitlab/login")).andExpect(status().isFound()).andReturn().getResponse();
    Map<String, List<String>> query = UriComponentsBuilder.fromUri(URI.create(res.getRedirectedUrl())).build().getQueryParams();
    String setCookie = res.getHeader("Set-Cookie");
    return new Started(query.get("state").getFirst(), new Cookie(Cookies.OAUTH_GITLAB, setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'))));
  }

  @Test
  void login_redirects_to_gitlab_for_read_api_with_state_and_pkce() throws Exception {
    MockHttpServletResponse res = mvc.perform(get("/auth/gitlab/login")).andExpect(status().isFound()).andReturn().getResponse();
    URI location = URI.create(res.getRedirectedUrl());
    assertThat(location.getHost()).isEqualTo("gitlab.test");
    assertThat(location.getPath()).isEqualTo("/oauth/authorize");
    Map<String, List<String>> q = UriComponentsBuilder.fromUri(location).build().getQueryParams();
    assertThat(q.get("client_id")).containsExactly("gitlab-test-client");
    assertThat(q.get("response_type")).containsExactly("code");
    assertThat(q.get("scope")).containsExactly("read_api");
    assertThat(q.get("code_challenge_method")).containsExactly("S256");
    assertThat(q.get("redirect_uri").getFirst()).isEqualTo(SITE + "/auth/gitlab/callback");
    String cookie = res.getHeader("Set-Cookie");
    assertThat(cookie).startsWith(Cookies.OAUTH_GITLAB + "=").contains("HttpOnly").contains("Secure").contains("SameSite=Lax");
    String[] parts = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';')).split("\\.");
    assertThat(parts[0]).isEqualTo(q.get("state").getFirst());
    assertThat(AuthController.challenge(parts[1])).isEqualTo(q.get("code_challenge").getFirst());
  }

  @Test
  void callback_starts_a_gitlab_session_scoped_to_the_connected_projects_the_person_can_read() throws Exception {
    Started s = start();
    String verifier = s.cookie().getValue().split("\\.")[1];
    gitlab.server.expect(requestTo(BASE + "/oauth/token"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().formDataContains(Map.of("code", "the-code", "client_id", "gitlab-test-client", "client_secret", "gitlab-oauth-test-secret",
            "grant_type", "authorization_code", "code_verifier", verifier, "redirect_uri", SITE + "/auth/gitlab/callback")))
        .andRespond(withSuccess("{\"access_token\":\"gloas-user\",\"refresh_token\":\"r\",\"expires_in\":7200}", MediaType.APPLICATION_JSON));
    gitlab.server.expect(requestTo(API + "/user"))
        .andExpect(header("Authorization", "Bearer gloas-user"))
        .andRespond(withSuccess("{\"id\":42,\"username\":\"tanuki\",\"name\":\"Tanuki\",\"avatar_url\":\"https://gitlab.test/a.png\"}", MediaType.APPLICATION_JSON));
    // Reporter or above on api and web (and on a project DocsWatcher does not hold); Developer on api only.
    gitlab.server.expect(requestTo(API + "/projects?membership=true&simple=true&min_access_level=20&per_page=100&page=1"))
        .andRespond(withSuccess("[{\"id\":901},{\"id\":902},{\"id\":999}]", MediaType.APPLICATION_JSON));
    gitlab.server.expect(requestTo(API + "/projects?membership=true&simple=true&min_access_level=30&per_page=100&page=1"))
        .andRespond(withSuccess("[{\"id\":901}]", MediaType.APPLICATION_JSON));
    gitlab.server.expect(requestTo(API + "/projects?membership=true&simple=true&min_access_level=40&per_page=100&page=1"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    MockHttpServletResponse res = mvc.perform(get("/auth/gitlab/callback").param("code", "the-code").param("state", s.state()).cookie(s.cookie()))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", SITE + "/#/app"))
        .andReturn().getResponse();
    gitlab.server.verify();
    assertThat(res.getHeaders("Set-Cookie")).anySatisfy(c -> assertThat(c).startsWith(Cookies.OAUTH_GITLAB + "=;").contains("Max-Age=0"));
    String session = res.getHeaders("Set-Cookie").stream().filter(c -> c.startsWith(Cookies.SESSION + "=")).findFirst().orElseThrow();
    Cookie cookie = new Cookie(Cookies.SESSION, session.substring(session.indexOf('=') + 1, session.indexOf(';')));

    mvc.perform(get("/auth/me").cookie(cookie))
        .andExpect(jsonPath("$.signedIn").value(true))
        .andExpect(jsonPath("$.provider").value("gitlab"))
        .andExpect(jsonPath("$.providers.gitlab").value(true))
        .andExpect(jsonPath("$.user.login").value("tanuki"))
        .andExpect(jsonPath("$.orgs.length()").value(1))
        .andExpect(jsonPath("$.orgs[0].login").value("acme"))
        .andExpect(jsonPath("$.orgs[0].repos").value(2));
    assertThat(jdbc.sql("select count(*) from user_session where access::text like '%gloas%'").query(Long.class).single()).isZero();

    // The organisation view counts only what GitLab lets them read: not acme/infra, not GitHub's acme/api.
    mvc.perform(get("/api/orgs/acme/repos").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].fullName").value("acme/api"))
        .andExpect(jsonPath("$[0].provider").value("gitlab"))
        .andExpect(jsonPath("$[1].fullName").value("acme/platform/web"));
    mvc.perform(get("/api/orgs/acme/overview").cookie(cookie)).andExpect(jsonPath("$.repos").value(2)).andExpect(jsonPath("$.findingsBySeverity.breaking").value(2));
    mvc.perform(get("/api/orgs/globex/repos").cookie(cookie)).andExpect(status().isNotFound());
    mvc.perform(get("/api/repos/" + infra + "/findings").cookie(cookie)).andExpect(status().isNotFound());
    mvc.perform(get("/api/repos/9001/findings").cookie(cookie)).andExpect(status().isNotFound());
    mvc.perform(get("/api/repos/" + web + "/findings").cookie(cookie)).andExpect(status().isOk());

    // Reporter on web reads but does not act; Developer on api acts.
    String ref = "{\"contract\":\"" + FakeScanEngine.CONTRACT_ID + "\",\"change\":\"" + FakeScanEngine.CHANGE_ID + "\"}";
    mvc.perform(post("/api/repos/" + web + "/findings/snooze").cookie(cookie).header("Origin", SITE).contentType("application/json").content(ref))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/repos/" + api + "/findings/snooze").cookie(cookie).header("Origin", SITE).contentType("application/json").content(ref))
        .andExpect(status().isOk());
    // A fix pull request is GitHub's; a GitLab project says so rather than failing.
    mvc.perform(post("/api/repos/" + api + "/findings/fix").cookie(cookie).header("Origin", SITE).contentType("application/json").content(ref))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value("unsupported"));
  }

  @Test
  void a_github_sign_in_s_state_does_not_finish_a_gitlab_one() throws Exception {
    MockHttpServletResponse github = mvc.perform(get("/auth/github/login")).andReturn().getResponse();
    String state = UriComponentsBuilder.fromUri(URI.create(github.getRedirectedUrl())).build().getQueryParams().get("state").getFirst();
    String value = github.getHeader("Set-Cookie").substring(github.getHeader("Set-Cookie").indexOf('=') + 1, github.getHeader("Set-Cookie").indexOf(';'));
    mvc.perform(get("/auth/gitlab/callback").param("code", "c").param("state", state).cookie(new Cookie(Cookies.OAUTH, value)))
        .andExpect(header().string("Location", SITE + "/#/app?signin=failed"));
    gitlab.server.verify();
  }

  @Test
  void a_forged_state_is_refused_and_cancelling_is_not_an_error() throws Exception {
    Started s = start();
    mvc.perform(get("/auth/gitlab/callback").param("code", "c").param("state", "forged").cookie(s.cookie()))
        .andExpect(header().string("Location", SITE + "/#/app?signin=failed"));
    mvc.perform(get("/auth/gitlab/callback").param("error", "access_denied").param("state", s.state()).cookie(s.cookie()))
        .andExpect(header().string("Location", SITE + "/#/app?signin=denied"));
    gitlab.server.verify();
    assertThat(jdbc.sql("select count(*) from user_session").query(Long.class).single()).isZero();
  }

  @Test
  void signed_in_with_nothing_connected_gitlab_is_not_asked_about_projects() throws Exception {
    jdbc.sql("delete from installation where provider = 'gitlab'").update();
    Started s = start();
    gitlab.server.expect(requestTo(BASE + "/oauth/token")).andRespond(withSuccess("{\"access_token\":\"gloas-user\"}", MediaType.APPLICATION_JSON));
    gitlab.server.expect(requestTo(API + "/user")).andRespond(withSuccess("{\"id\":42,\"username\":\"tanuki\"}", MediaType.APPLICATION_JSON));
    mvc.perform(get("/auth/gitlab/callback").param("code", "c").param("state", s.state()).cookie(s.cookie()))
        .andExpect(header().string("Location", SITE + "/#/app"));
    gitlab.server.verify();
  }

  @Test
  void me_says_which_providers_can_sign_in() throws Exception {
    mvc.perform(get("/auth/me"))
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.providers.github").value(true))
        .andExpect(jsonPath("$.providers.gitlab").value(true))
        .andExpect(jsonPath("$.signedIn").value(false));
  }
}
