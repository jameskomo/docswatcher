package dev.docswatcher.app.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.docswatcher.app.store.InstallationStore;
import dev.docswatcher.app.store.RepoStore;
import dev.docswatcher.app.support.GitHubUserMock;
import dev.docswatcher.app.support.PostgresTest;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The whole OAuth round trip against a scripted GitHub: the redirect out, the state check on the
 * way back, the code exchange with its PKCE verifier, and the access snapshot that becomes the
 * session. GitHub is a MockRestServiceServer behind the real {@code GitHubUserApi}.
 */
@AutoConfigureMockMvc
class SignInIT extends PostgresTest {

  static final String WEB = "https://github.test";
  static final String API = "https://api.github.test";
  static final String SITE = "https://docswatcher.test";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired InstallationStore installations;
  @Autowired RepoStore repos;
  @Autowired GitHubUserMock github;

  @BeforeEach
  void seed() {
    jdbc.sql("delete from installation").update();
    jdbc.sql("delete from user_session").update();
    github.server.reset();
    installations.upsert(5001, "acme");
    repos.upsert(9001, 5001, "acme/checkout-service", "main");
    repos.upsert(9002, 5001, "acme/notify", "main");
  }

  /** Starts a sign-in and returns the state GitHub would echo back, plus the cookie that remembers it. */
  private record Started(String state, String challenge, Cookie cookie) {}

  private Started start() throws Exception {
    MockHttpServletResponse res = mvc.perform(get("/auth/github/login")).andExpect(status().isFound()).andReturn().getResponse();
    Map<String, List<String>> query = UriComponentsBuilder.fromUri(URI.create(res.getRedirectedUrl())).build().getQueryParams();
    String setCookie = res.getHeader("Set-Cookie");
    String value = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
    return new Started(query.get("state").getFirst(), query.get("code_challenge").getFirst(), new Cookie(Cookies.OAUTH, value));
  }

  @Test
  void login_redirects_to_github_with_state_and_pkce_in_a_host_only_cookie() throws Exception {
    MockHttpServletResponse res = mvc.perform(get("/auth/github/login")).andExpect(status().isFound()).andReturn().getResponse();
    URI location = URI.create(res.getRedirectedUrl());
    assertThat(location.getHost()).isEqualTo("github.test");
    assertThat(location.getPath()).isEqualTo("/login/oauth/authorize");
    Map<String, List<String>> q = UriComponentsBuilder.fromUri(location).build().getQueryParams();
    assertThat(q.get("client_id")).containsExactly("Iv23test");
    assertThat(q.get("redirect_uri").getFirst()).contains("docswatcher.test").contains("callback");
    assertThat(q.get("code_challenge_method")).containsExactly("S256");
    assertThat(q.get("state").getFirst()).hasSizeGreaterThanOrEqualTo(43);

    String cookie = res.getHeader("Set-Cookie");
    assertThat(cookie).startsWith(Cookies.OAUTH + "=").contains("HttpOnly").contains("Secure").contains("SameSite=Lax").contains("Path=/");
    // The cookie holds the state and the verifier; the verifier hashes to the challenge sent out.
    String value = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
    String[] parts = value.split("\\.");
    assertThat(parts[0]).isEqualTo(q.get("state").getFirst());
    assertThat(AuthController.challenge(parts[1])).isEqualTo(q.get("code_challenge").getFirst());
  }

  @Test
  void callback_exchanges_the_code_and_starts_a_session_scoped_to_what_github_allows() throws Exception {
    Started s = start();
    github.server.expect(requestTo(WEB + "/login/oauth/access_token"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().formDataContains(Map.of("code", "the-code", "client_id", "Iv23test", "client_secret", "oauth-test-secret")))
        .andExpect(request -> {
          String body = ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString();
          String verifier = s.cookie().getValue().split("\\.")[1];
          assertThat(body).contains("code_verifier=" + verifier);
        })
        .andRespond(withSuccess("{\"access_token\":\"ghu_user\",\"token_type\":\"bearer\"}", MediaType.APPLICATION_JSON));
    github.server.expect(requestTo(API + "/user"))
        .andExpect(header("Authorization", "Bearer ghu_user"))
        .andExpect(header("X-GitHub-Api-Version", "2026-03-10"))
        .andRespond(withSuccess("{\"id\":42,\"login\":\"octo\",\"name\":\"Octo Cat\",\"avatar_url\":\"https://avatars.test/42\"}", MediaType.APPLICATION_JSON));
    github.server.expect(requestTo(API + "/user/installations?per_page=100&page=1"))
        .andRespond(withSuccess("{\"total_count\":2,\"installations\":[{\"id\":5001,\"account\":{\"login\":\"acme\"}},{\"id\":7777,\"account\":{\"login\":\"elsewhere\"}}]}", MediaType.APPLICATION_JSON));
    // 7777 is an installation this deployment never heard from, so its repositories are not asked for.
    github.server.expect(requestTo(API + "/user/installations/5001/repositories?per_page=100&page=1"))
        .andRespond(withSuccess("{\"total_count\":2,\"repositories\":[{\"id\":9001,\"full_name\":\"acme/checkout-service\",\"permissions\":{\"admin\":false,\"maintain\":false,\"push\":true,\"triage\":true,\"pull\":true}},{\"id\":9555,\"full_name\":\"acme/not-stored\",\"permissions\":{\"pull\":true}}]}", MediaType.APPLICATION_JSON));

    MockHttpServletResponse res = mvc.perform(get("/auth/github/callback").param("code", "the-code").param("state", s.state()).cookie(s.cookie()))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", SITE + "/#/app"))
        .andReturn().getResponse();
    github.server.verify();

    List<String> cookies = res.getHeaders("Set-Cookie");
    assertThat(cookies).anySatisfy(c -> assertThat(c).startsWith(Cookies.OAUTH + "=;").contains("Max-Age=0"));
    String session = cookies.stream().filter(c -> c.startsWith(Cookies.SESSION + "=")).findFirst().orElseThrow();
    assertThat(session).contains("HttpOnly").contains("Secure").contains("SameSite=Lax").contains("Max-Age=28800");
    Cookie cookie = new Cookie(Cookies.SESSION, session.substring(session.indexOf('=') + 1, session.indexOf(';')));

    mvc.perform(get("/auth/me").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.signedIn").value(true))
        .andExpect(jsonPath("$.user.login").value("octo"))
        .andExpect(jsonPath("$.orgs.length()").value(1))
        .andExpect(jsonPath("$.orgs[0].login").value("acme"))
        .andExpect(jsonPath("$.orgs[0].repos").value(1));

    // Neither the session value nor the GitHub token is in the database; only the value's hash is.
    assertThat(jdbc.sql("select count(*) from user_session where access::text like '%ghu_user%'").query(Long.class).single()).isZero();
    assertThat(jdbc.sql("select count(*) from user_session where token_hash = convert_to(:v, 'UTF8')").param("v", cookie.getValue()).query(Long.class).single()).isZero();

    // The session is a member of acme and sees the one repository GitHub listed.
    mvc.perform(get("/api/orgs/acme/repos").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].fullName").value("acme/checkout-service"));
  }

  @Test
  void a_state_that_does_not_match_the_cookie_is_refused_before_github_is_called() throws Exception {
    Started s = start();
    MockHttpServletResponse res = mvc.perform(get("/auth/github/callback").param("code", "c").param("state", "forged").cookie(s.cookie()))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", SITE + "/#/app?signin=failed"))
        .andReturn().getResponse();
    assertThat(res.getHeaders("Set-Cookie")).noneMatch(c -> c.startsWith(Cookies.SESSION));
    github.server.verify();
  }

  @Test
  void a_callback_without_the_state_cookie_is_refused() throws Exception {
    Started s = start();
    mvc.perform(get("/auth/github/callback").param("code", "c").param("state", s.state()))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", SITE + "/#/app?signin=failed"));
    github.server.verify();
  }

  @Test
  void cancelling_on_github_returns_to_the_dashboard_signed_out() throws Exception {
    Started s = start();
    mvc.perform(get("/auth/github/callback").param("error", "access_denied").param("state", s.state()).cookie(s.cookie()))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", SITE + "/#/app?signin=denied"));
  }

  @Test
  void a_refused_code_exchange_starts_no_session() throws Exception {
    Started s = start();
    github.server.expect(requestTo(WEB + "/login/oauth/access_token"))
        .andRespond(withSuccess("{\"error\":\"bad_verification_code\"}", MediaType.APPLICATION_JSON));
    MockHttpServletResponse res = mvc.perform(get("/auth/github/callback").param("code", "old").param("state", s.state()).cookie(s.cookie()))
        .andExpect(header().string("Location", SITE + "/#/app?signin=failed"))
        .andReturn().getResponse();
    assertThat(res.getHeaders("Set-Cookie")).noneMatch(c -> c.startsWith(Cookies.SESSION));
    assertThat(jdbc.sql("select count(*) from user_session").query(Long.class).single()).isZero();
  }

  @Test
  void github_failing_mid_sign_in_starts_no_session() throws Exception {
    Started s = start();
    github.server.expect(requestTo(WEB + "/login/oauth/access_token"))
        .andRespond(withSuccess("{\"access_token\":\"ghu_user\"}", MediaType.APPLICATION_JSON));
    github.server.expect(requestTo(API + "/user")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
    mvc.perform(get("/auth/github/callback").param("code", "c").param("state", s.state()).cookie(s.cookie()))
        .andExpect(header().string("Location", SITE + "/#/app?signin=failed"));
    assertThat(jdbc.sql("select count(*) from user_session").query(Long.class).single()).isZero();
  }

  @Test
  void me_answers_signed_out_without_an_error() throws Exception {
    mvc.perform(get("/auth/me"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.signedIn").value(false))
        .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(get("/auth/me").cookie(new Cookie(Cookies.SESSION, "made-up")))
        .andExpect(jsonPath("$.signedIn").value(false));
  }
}
