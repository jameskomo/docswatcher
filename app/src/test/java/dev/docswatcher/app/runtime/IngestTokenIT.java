package dev.docswatcher.app.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.docswatcher.app.auth.Cookies;
import dev.docswatcher.app.auth.SessionStore;
import dev.docswatcher.app.auth.UserSession;
import dev.docswatcher.app.store.ContractStore;
import dev.docswatcher.app.store.FindingStore;
import dev.docswatcher.app.store.InstallationStore;
import dev.docswatcher.app.store.RepoStore;
import dev.docswatcher.app.store.StoredContract;
import dev.docswatcher.app.support.FakeScanEngine;
import dev.docswatcher.app.support.PostgresTest;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Ingest tokens: who can make one, and what one can do. Two organisations, acme and globex, each
 * with a repository. A token belongs to one repository and posts that repository's telemetry,
 * whatever the payload claims, and nothing else.
 */
@AutoConfigureMockMvc
class IngestTokenIT extends PostgresTest {

  static final String SITE = "https://docswatcher.test";
  static final String OWNER = "Bearer test-token";
  static final String TRACES = "/api/runtime/otlp/v1/traces";
  static final String ASSISTANTS = "openai:endpoint:ANY /v1/assistants";
  static final String THREADS = "openai:endpoint:ANY /v1/threads";
  static final String EVIDENCE = "[{\"path\":\"src/a.py\",\"line\":1,\"column\":1,\"snippet\":\"x\",\"detector\":\"d\",\"layer\":\"literal\"}]";

  static final long ACME = 9201;
  static final long ACME_OTHER = 9202;
  static final long GLOBEX = 9301;

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired InstallationStore installations;
  @Autowired RepoStore repos;
  @Autowired ContractStore contracts;
  @Autowired FindingStore findings;
  @Autowired SessionStore sessions;
  @Autowired RuntimeObservationStore observations;
  @Autowired IngestTokenStore tokens;

  Cookie writer;
  Cookie reader;

  @BeforeEach
  void seed() {
    jdbc.sql("delete from installation").update();
    jdbc.sql("delete from user_session").update();
    installations.upsert(5201, "acme");
    installations.upsert(6301, "globex");
    repo(ACME, 5201, "acme/checkout");
    repo(ACME_OTHER, 5201, "acme/billing");
    repo(GLOBEX, 6301, "globex/checkout");
    writer = member("write");
    reader = member("read");
  }

  private void repo(long id, long installation, String name) {
    repos.upsert(id, installation, name, "main");
    repos.setLastScannedSha(id, "s");
    contracts.upsert(new StoredContract(id, ASSISTANTS, "openai", "endpoint", "ANY /v1/assistants", "high", EVIDENCE, null, "s", "s"));
  }

  /** A member of acme with the given permission on acme/checkout, and none on the rest. */
  private Cookie member(String permission) {
    List<UserSession.OrgAccess> orgs = List.of(new UserSession.OrgAccess(5201, "acme", List.of(new UserSession.RepoAccess(ACME, "acme/checkout", permission))));
    return new Cookie(Cookies.SESSION, sessions.create(42, "octo-" + permission, null, null, orgs, Duration.ofHours(1)));
  }

  private static String payload(String name) throws Exception {
    return new String(new ClassPathResource("otlp/" + name).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
  }

  private ResultActions export(String bearer, String fixture) throws Exception {
    return export(post(TRACES), bearer, fixture);
  }

  private ResultActions export(MockHttpServletRequestBuilder request, String bearer, String fixture) throws Exception {
    return mvc.perform(request.header("Authorization", "Bearer " + bearer).contentType(MediaType.APPLICATION_JSON).content(payload(fixture)));
  }

  private MockHttpServletRequestBuilder asMember(MockHttpServletRequestBuilder request, Cookie who) {
    return request.cookie(who).header("Origin", SITE).contentType(MediaType.APPLICATION_JSON);
  }

  private String createToken(long repoId) throws Exception {
    String body = mvc.perform(asMember(post("/api/repos/" + repoId + "/runtime/tokens"), writer).content("{\"label\":\"checkout prod\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return mapper.readTree(body).get("secret").asString();
  }

  @Test
  void a_member_with_write_access_creates_a_token_whose_secret_is_shown_once() throws Exception {
    String body = mvc.perform(asMember(post("/api/repos/" + ACME + "/runtime/tokens"), writer).content("{\"label\":\"  checkout\\nprod  \"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.token.label").value("checkout prod"))
        .andExpect(jsonPath("$.token.createdBy").value("octo-write"))
        .andReturn().getResponse().getContentAsString();
    JsonNode created = mapper.readTree(body);
    String secret = created.get("secret").asString();
    assertThat(secret).startsWith("dwi_").hasSizeGreaterThan(40);
    assertThat(created.get("token").get("prefix").asString()).isEqualTo(secret.substring(0, 10));

    // Listing shows the prefix, never the secret or its hash.
    String list = mvc.perform(get("/api/repos/" + ACME + "/runtime/tokens").cookie(reader))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andReturn().getResponse().getContentAsString();
    assertThat(list).doesNotContain(secret).doesNotContain("hash").doesNotContain("secret");
    // Stored as a hash only.
    assertThat(jdbc.sql("select count(*) from runtime_ingest_token where token_hash = :h").param("h", IngestTokenStore.hash(secret)).query(Integer.class).single()).isEqualTo(1);
  }

  @Test
  void creating_or_revoking_needs_write_access_and_the_sites_own_origin() throws Exception {
    mvc.perform(asMember(post("/api/repos/" + ACME + "/runtime/tokens"), reader).content("{}")).andExpect(status().isForbidden());
    mvc.perform(post("/api/repos/" + ACME + "/runtime/tokens").cookie(writer).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isForbidden());
    // A repository the member cannot see, in their organisation or another, does not exist to them.
    for (long other : new long[] {ACME_OTHER, GLOBEX}) {
      mvc.perform(asMember(post("/api/repos/" + other + "/runtime/tokens"), writer).content("{}")).andExpect(status().isNotFound());
      mvc.perform(get("/api/repos/" + other + "/runtime/tokens").cookie(writer)).andExpect(status().isNotFound());
      mvc.perform(get("/api/repos/" + other + "/runtime/summary").cookie(writer)).andExpect(status().isNotFound());
    }
    long id = tokens.create(ACME, "t", "owner").token().id();
    mvc.perform(asMember(post("/api/repos/" + ACME + "/runtime/tokens/" + id + "/revoke"), reader)).andExpect(status().isForbidden());
  }

  @Test
  void a_token_posts_its_own_repositorys_telemetry_without_naming_it() throws Exception {
    String secret = createToken(ACME);
    // The payload names no repository at all; the token does.
    export(secret, "traces.norepo.json")
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.recorded").value(1))
        .andExpect(jsonPath("$.repo").value("acme/checkout"));
    assertThat(observations.forContract(ACME, ASSISTANTS)).hasSize(1);
    assertThat(tokens.lastUsed(ACME)).isPresent();
  }

  @Test
  void a_token_for_one_organisation_cannot_post_for_another() throws Exception {
    String globex = tokens.create(GLOBEX, "globex", "owner").secret();

    // The payload claims acme/checkout. The token is globex's, so the claim is not believed.
    export(globex, "traces.modern.json").andExpect(status().isBadRequest());
    // Asking for acme on the URL is refused outright.
    export(post(TRACES + "?repo=acme/checkout"), globex, "traces.modern.json").andExpect(status().isForbidden());

    assertThat(observations.forRepo(ACME)).isEmpty();
    assertThat(observations.forRepo(GLOBEX)).isEmpty();
  }

  @Test
  void a_token_for_one_repository_cannot_post_for_its_sibling() throws Exception {
    String billing = tokens.create(ACME_OTHER, "billing", "owner").secret();
    export(billing, "traces.modern.json").andExpect(status().isBadRequest());
    export(post(TRACES + "?repo=acme/checkout"), billing, "traces.modern.json").andExpect(status().isForbidden());
    assertThat(observations.forRepo(ACME)).isEmpty();
  }

  @Test
  void a_revoked_token_is_refused() throws Exception {
    IngestTokenStore.Created created = tokens.create(ACME, "t", "owner");
    export(created.secret(), "traces.norepo.json").andExpect(status().isAccepted());

    mvc.perform(asMember(post("/api/repos/" + ACME + "/runtime/tokens/" + created.token().id() + "/revoke"), writer))
        .andExpect(status().isNoContent());
    export(created.secret(), "traces.norepo.json").andExpect(status().isUnauthorized());
    mvc.perform(get("/api/repos/" + ACME + "/runtime/tokens").cookie(writer)).andExpect(jsonPath("$.length()").value(0));
    // Revoking twice, or through another repository's path, finds nothing.
    mvc.perform(asMember(post("/api/repos/" + ACME + "/runtime/tokens/" + created.token().id() + "/revoke"), writer)).andExpect(status().isNotFound());
  }

  @Test
  void a_token_cannot_be_revoked_through_another_repositorys_path() throws Exception {
    IngestTokenStore.Created billing = tokens.create(ACME_OTHER, "billing", "owner");
    mvc.perform(asMember(post("/api/repos/" + ACME + "/runtime/tokens/" + billing.token().id() + "/revoke"), writer)).andExpect(status().isNotFound());
    export(billing.secret(), "traces.norepo.json").andExpect(status().isAccepted());
  }

  @Test
  void an_ingest_token_opens_nothing_but_the_ingest_route() throws Exception {
    String secret = tokens.create(ACME, "t", "owner").secret();
    mvc.perform(get("/api/repos/" + ACME + "/findings").header("Authorization", "Bearer " + secret)).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/repos/" + ACME + "/runtime/summary").header("Authorization", "Bearer " + secret)).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/repos/" + ACME + "/rescan").header("Authorization", "Bearer " + secret)).andExpect(status().isUnauthorized());
  }

  @Test
  void an_unknown_token_is_refused_and_the_owner_token_still_works() throws Exception {
    export("dwi_not-a-real-token", "traces.norepo.json").andExpect(status().isUnauthorized());
    export("test-token", "traces.modern.json").andExpect(status().isAccepted()).andExpect(jsonPath("$.repo").value("acme/checkout"));
    mvc.perform(post("/api/repos/" + GLOBEX + "/runtime/tokens").header("Authorization", OWNER).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.token.createdBy").value("owner"))
        .andExpect(jsonPath("$.token.label").value("Ingest token"));
  }

  @Test
  void the_summary_splits_what_production_calls_from_what_it_never_does() throws Exception {
    contracts.upsert(new StoredContract(ACME, THREADS, "openai", "endpoint", "ANY /v1/threads", "high", EVIDENCE, null, "s", "s"));
    contracts.upsert(new StoredContract(ACME, FakeScanEngine.CONTRACT_ID, "openai", "model", "gpt-4-turbo", "high", EVIDENCE, null, "s", "s"));
    findings.insertOpen(ACME, ASSISTANTS, "openai-assistants-api-shutdown-2026", "f1", "breaking", LocalDate.of(2026, 8, 26));
    findings.insertOpen(ACME, THREADS, "openai-assistants-api-shutdown-2026", "f2", "breaking", LocalDate.of(2026, 8, 26));
    findings.insertOpen(ACME, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID, "f3", "breaking", FakeScanEngine.EFFECTIVE);

    mvc.perform(get("/api/repos/" + ACME + "/runtime/summary").cookie(reader))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lastReportAt").doesNotExist())
        .andExpect(jsonPath("$.deprecated.length()").value(0));

    String secret = createToken(ACME);
    export(secret, "traces.modern.json").andExpect(status().isAccepted());
    export(secret, "traces.modern.json").andExpect(status().isAccepted());

    mvc.perform(get("/api/repos/" + ACME + "/runtime/summary").cookie(reader))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.repoFullName").value("acme/checkout"))
        .andExpect(jsonPath("$.activeTokens").value(1))
        .andExpect(jsonPath("$.lastReportAt").exists())
        .andExpect(jsonPath("$.deprecated.length()").value(1))
        .andExpect(jsonPath("$.deprecated[0].method").value("POST"))
        .andExpect(jsonPath("$.deprecated[0].path").value("/v1/assistants"))
        .andExpect(jsonPath("$.deprecated[0].provider").value("openai"))
        .andExpect(jsonPath("$.deprecated[0].totalCalls").value(2))
        .andExpect(jsonPath("$.deprecated[0].callsPerDay").value(2))
        .andExpect(jsonPath("$.deprecated[0].sunsetHeader").value("Wed, 26 Aug 2026 00:00:00 GMT"))
        .andExpect(jsonPath("$.deprecated[0].findings[0].contract").value(ASSISTANTS))
        .andExpect(jsonPath("$.deprecated[0].findings[0].change").value("openai-assistants-api-shutdown-2026"))
        // Tracked by the scanner but nothing announced: observed, not deprecated.
        .andExpect(jsonPath("$.alsoObserved.length()").value(0))
        .andExpect(jsonPath("$.notObserved.length()").value(1))
        .andExpect(jsonPath("$.notObserved[0].contract").value(THREADS))
        // A model name lives in a request body, which no span carries: not claimed as unseen.
        .andExpect(jsonPath("$.notObservable").value(1));
  }
}
