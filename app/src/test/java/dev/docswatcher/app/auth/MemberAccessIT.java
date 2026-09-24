package dev.docswatcher.app.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.docswatcher.app.store.ContractStore;
import dev.docswatcher.app.store.FindingStore;
import dev.docswatcher.app.store.InstallationStore;
import dev.docswatcher.app.store.RepoStore;
import dev.docswatcher.app.store.StoredContract;
import dev.docswatcher.app.support.FakeScanEngine;
import dev.docswatcher.app.support.PostgresTest;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * What a signed-in member can reach through the API, and what they cannot. Two organisations:
 * acme, where the member can read one repository and write another, and globex, which they
 * cannot see at all. The owner's token keeps working across all of it.
 */
@AutoConfigureMockMvc
class MemberAccessIT extends PostgresTest {

  static final String SITE = "https://docswatcher.test";
  static final String EVIDENCE = "[{\"path\":\"app.py\",\"line\":3,\"column\":1,\"snippet\":\"model='gpt-4-turbo'\",\"detector\":\"d\",\"layer\":\"literal\"}]";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired InstallationStore installations;
  @Autowired RepoStore repos;
  @Autowired ContractStore contracts;
  @Autowired FindingStore findings;
  @Autowired SessionStore sessions;

  Cookie member;

  @BeforeEach
  void seed() {
    jdbc.sql("delete from installation").update();
    jdbc.sql("delete from user_session").update();
    installations.upsert(5001, "acme");
    installations.upsert(6001, "globex");
    for (long[] r : new long[][] {{9001, 5001}, {9002, 5001}, {9003, 5001}, {9101, 6001}}) {
      String name = (r[1] == 5001 ? "acme/" : "globex/") + "repo" + r[0];
      repos.upsert(r[0], r[1], name, "main");
      repos.setLastScannedSha(r[0], "sha" + r[0]);
      contracts.upsert(new StoredContract(r[0], FakeScanEngine.CONTRACT_ID, "openai", "model", "gpt-4-turbo", "high", EVIDENCE, null, "sha" + r[0], "sha" + r[0]));
      findings.insertOpen(r[0], FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID, "f" + r[0], "breaking", LocalDate.of(2026, 10, 23));
    }
    // Reads 9001, writes 9002, and cannot see 9003 although it is in the same organisation.
    member = session(List.of(new UserSession.OrgAccess(5001, "acme", List.of(
        new UserSession.RepoAccess(9001, "acme/repo9001", "read"),
        new UserSession.RepoAccess(9002, "acme/repo9002", "write")))), Duration.ofHours(1));
  }

  private Cookie session(List<UserSession.OrgAccess> orgs, Duration ttl) {
    return new Cookie(Cookies.SESSION, sessions.create(42, "octo", "Octo", null, orgs, ttl));
  }

  private MockHttpServletRequestBuilder action(String path) {
    return post(path).cookie(member).header("Origin", SITE).contentType("application/json")
        .content("{\"contract\":\"" + FakeScanEngine.CONTRACT_ID + "\",\"change\":\"" + FakeScanEngine.CHANGE_ID + "\",\"days\":7}");
  }

  @Test
  void organisation_answers_count_only_the_members_repositories() throws Exception {
    mvc.perform(get("/api/orgs/acme/overview").cookie(member))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.repos").value(2))
        .andExpect(jsonPath("$.contracts").value(2))
        .andExpect(jsonPath("$.findingsBySeverity.breaking").value(2));
    mvc.perform(get("/api/orgs/acme/repos").cookie(member)).andExpect(jsonPath("$.length()").value(2));
    mvc.perform(get("/api/orgs/acme/map").cookie(member)).andExpect(jsonPath("$[0].contracts").value(2));
    mvc.perform(get("/api/orgs/acme/horizon").cookie(member)).andExpect(jsonPath("$[0].findings.length()").value(2));
    mvc.perform(get("/api/orgs/acme/blast-radius/" + FakeScanEngine.CHANGE_ID).cookie(member))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.repos").value(2));
  }

  @Test
  void the_owner_token_still_sees_everything() throws Exception {
    mvc.perform(get("/api/orgs/acme/overview").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.repos").value(3));
    mvc.perform(get("/api/orgs/globex/overview").header("Authorization", "Bearer test-token")).andExpect(status().isOk());
    mvc.perform(get("/api/early-access").header("Authorization", "Bearer test-token")).andExpect(status().isOk());
    mvc.perform(post("/api/repos/9003/rescan").header("Authorization", "Bearer test-token")).andExpect(status().isAccepted());
  }

  @Test
  void a_wrong_token_is_refused_even_with_a_valid_session_beside_it() throws Exception {
    mvc.perform(get("/api/orgs/acme/overview").header("Authorization", "Bearer nope").cookie(member))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void an_organisation_the_member_cannot_see_is_not_found() throws Exception {
    for (String path : List.of("overview", "repos", "map", "horizon", "blast-radius/" + FakeScanEngine.CHANGE_ID)) {
      mvc.perform(get("/api/orgs/globex/" + path).cookie(member)).andExpect(status().isNotFound());
      mvc.perform(get("/api/orgs/nobody/" + path).cookie(member)).andExpect(status().isNotFound());
    }
  }

  @Test
  void organisation_logins_match_without_regard_to_case() throws Exception {
    mvc.perform(get("/api/orgs/ACME/repos").cookie(member)).andExpect(status().isOk());
  }

  @Test
  void a_repository_the_member_cannot_see_is_not_found_even_inside_their_organisation() throws Exception {
    mvc.perform(get("/api/repos/9001/findings").cookie(member)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    mvc.perform(get("/api/repos/9001/inventory").cookie(member)).andExpect(status().isOk());
    for (String repo : List.of("9003", "9101", "12345")) {
      mvc.perform(get("/api/repos/" + repo + "/findings").cookie(member)).andExpect(status().isNotFound());
      mvc.perform(get("/api/repos/" + repo + "/inventory").cookie(member)).andExpect(status().isNotFound());
      mvc.perform(get("/api/repos/" + repo + "/runtime").cookie(member)).andExpect(status().isNotFound());
      mvc.perform(action("/api/repos/" + repo + "/findings/snooze")).andExpect(status().isNotFound());
    }
    mvc.perform(post("/api/findings/9101/" + FakeScanEngine.CONTRACT_ID + "/" + FakeScanEngine.CHANGE_ID + "/not-in-prod")
        .cookie(member).header("Origin", SITE)).andExpect(status().isNotFound());
  }

  @Test
  void acting_needs_write_access_to_the_repository() throws Exception {
    mvc.perform(action("/api/repos/9001/findings/snooze")).andExpect(status().isForbidden());
    mvc.perform(action("/api/repos/9001/rescan")).andExpect(status().isForbidden());
    mvc.perform(action("/api/repos/9002/findings/snooze"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("snoozed"))
        .andExpect(jsonPath("$.detail").value(LocalDate.now().plusDays(7).toString()));
    assertThat(findings.find(9002, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID).orElseThrow().status()).isEqualTo("snoozed");
    mvc.perform(action("/api/repos/9002/findings/not-in-prod")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("not_in_prod"));
    mvc.perform(action("/api/repos/9002/findings/fix")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("dispatched"));
    mvc.perform(action("/api/repos/9002/rescan")).andExpect(status().isAccepted());
  }

  @Test
  void a_finding_named_in_the_body_that_does_not_exist_is_not_found() throws Exception {
    mvc.perform(post("/api/repos/9002/findings/snooze").cookie(member).header("Origin", SITE).contentType("application/json")
        .content("{\"contract\":\"stripe:endpoint:POST /v1/sources\",\"change\":\"nope\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void a_members_state_change_must_come_from_the_site_itself() throws Exception {
    mvc.perform(post("/api/repos/9002/rescan").cookie(member)).andExpect(status().isForbidden());
    mvc.perform(post("/api/repos/9002/rescan").cookie(member).header("Origin", "https://evil.test")).andExpect(status().isForbidden());
    mvc.perform(post("/api/repos/9002/rescan").cookie(member).header("Sec-Fetch-Site", "same-origin")).andExpect(status().isAccepted());
  }

  @Test
  void owner_only_endpoints_are_closed_to_members() throws Exception {
    mvc.perform(get("/api/early-access").cookie(member)).andExpect(status().isForbidden());
    mvc.perform(post("/api/runtime/otlp/v1/traces").cookie(member).header("Origin", SITE).contentType("application/json").content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/setup/workflow").cookie(member)).andExpect(status().isOk());
  }

  @Test
  void an_expired_or_signed_out_session_is_no_session() throws Exception {
    Cookie expired = session(List.of(new UserSession.OrgAccess(5001, "acme", List.of())), Duration.ofSeconds(-1));
    mvc.perform(get("/api/orgs/acme/overview").cookie(expired)).andExpect(status().isUnauthorized());

    mvc.perform(post("/auth/logout").cookie(member)).andExpect(status().isForbidden());
    mvc.perform(post("/auth/logout").cookie(member).header("Origin", SITE))
        .andExpect(status().isNoContent())
        .andExpect(result -> assertThat(result.getResponse().getHeader("Set-Cookie")).startsWith(Cookies.SESSION + "=;").contains("Max-Age=0"));
    mvc.perform(get("/api/orgs/acme/overview").cookie(member)).andExpect(status().isUnauthorized());
    mvc.perform(get("/auth/me").cookie(member)).andExpect(jsonPath("$.signedIn").value(false));
  }

  @Test
  void a_member_with_no_installations_is_signed_in_but_sees_nothing() throws Exception {
    Cookie nobody = session(List.of(), Duration.ofHours(1));
    mvc.perform(get("/auth/me").cookie(nobody)).andExpect(jsonPath("$.signedIn").value(true)).andExpect(jsonPath("$.orgs.length()").value(0));
    mvc.perform(get("/api/orgs/acme/overview").cookie(nobody)).andExpect(status().isNotFound());
  }
}
