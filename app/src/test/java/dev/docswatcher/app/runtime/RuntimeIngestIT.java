package dev.docswatcher.app.runtime;

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
import dev.docswatcher.app.support.PostgresTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** The OTLP ingest path, end to end, against a real database. */
@AutoConfigureMockMvc
class RuntimeIngestIT extends PostgresTest {

  private static final String AUTH = "Bearer test-token";
  private static final String TRACES = "/api/runtime/otlp/v1/traces";
  private static final String ASSISTANTS = "openai:endpoint:ANY /v1/assistants";
  private static final String COMPLETIONS = "openai:endpoint:POST /v1/chat/completions";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired InstallationStore installations;
  @Autowired RepoStore repos;
  @Autowired ContractStore contracts;
  @Autowired FindingStore findings;
  @Autowired RuntimeObservationStore observations;

  @BeforeEach
  void seed() {
    jdbc.sql("delete from installation").update();
    installations.upsert(7001, "acme");
    repos.upsert(7100, 7001, "acme/checkout", "main");
    // currentForRepo only returns contracts from the newest scan, so the repo's
    // last scanned sha has to match the contracts seeded below.
    repos.setLastScannedSha(7100, "s");
    String evidence = "[{\"path\":\"src/a.py\",\"line\":1,\"column\":1,\"snippet\":\"x\",\"detector\":\"d\",\"layer\":\"literal\"}]";
    contracts.upsert(new StoredContract(7100, ASSISTANTS, "openai", "endpoint", "ANY /v1/assistants", "high", evidence, null, "s", "s"));
    contracts.upsert(new StoredContract(7100, COMPLETIONS, "openai", "endpoint", "POST /v1/chat/completions", "high", evidence, null, "s", "s"));
  }

  private static String payload(String name) throws Exception {
    return new String(new ClassPathResource("otlp/" + name).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
  }

  private void send(String fixture, org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
    mvc.perform(post(TRACES).header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON).content(payload(fixture)))
        .andExpect(expected);
  }

  @Test
  void rejects_an_export_that_names_no_repository() throws Exception {
    send("traces.norepo.json", status().isBadRequest());
    assertThat(observations.forRepo(7100)).isEmpty();
  }

  @Test
  void requires_the_api_token() throws Exception {
    mvc.perform(post(TRACES).contentType(MediaType.APPLICATION_JSON).content(payload("traces.modern.json")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void records_a_header_bearing_span_and_attributes_it_to_a_contract() throws Exception {
    send("traces.modern.json", status().isAccepted());

    List<RuntimeObservation> forContract = observations.forContract(7100, ASSISTANTS);
    assertThat(forContract).hasSize(1);
    RuntimeObservation o = forContract.getFirst();
    assertThat(o.provider()).isEqualTo("openai");
    assertThat(o.host()).isEqualTo("api.openai.com");
    assertThat(o.method()).isEqualTo("POST");
    assertThat(o.sunsetHeader()).isEqualTo("Wed, 26 Aug 2026 00:00:00 GMT");
    assertThat(o.deprecationHeader()).isEqualTo("true");
    assertThat(o.callCount()).isEqualTo(1);
  }

  @Test
  void a_headerless_span_counts_only_when_it_matches_a_known_contract() throws Exception {
    send("traces.modern.json", status().isAccepted());

    assertThat(observations.forContract(7100, COMPLETIONS)).hasSize(1);
    // The internal host matches no provider and no contract, so it is not stored at all.
    assertThat(observations.forRepo(7100)).noneMatch(o -> o.host().contains("internal.acme.test"));
  }

  @Test
  void a_server_span_and_a_malformed_span_are_skipped_without_failing_the_export() throws Exception {
    send("traces.modern.json", status().isAccepted());
    // Five spans in the fixture, three of them usable in some way.
    assertThat(observations.forRepo(7100)).hasSize(2);
  }

  @Test
  void the_daily_counter_increments_rather_than_duplicating() throws Exception {
    send("traces.modern.json", status().isAccepted());
    send("traces.modern.json", status().isAccepted());
    send("traces.modern.json", status().isAccepted());

    List<RuntimeObservation> forContract = observations.forContract(7100, ASSISTANTS);
    assertThat(forContract).hasSize(1);
    assertThat(forContract.getFirst().callCount()).isEqualTo(3);
  }

  @Test
  void the_older_attribute_spellings_are_read_and_an_array_header_is_kept_whole() throws Exception {
    send("traces.legacy.json", status().isAccepted());

    RuntimeObservation o = observations.forContract(7100, ASSISTANTS).getFirst();
    assertThat(o.sunsetHeader()).isEqualTo("Wed, 26 Aug 2026 00:00:00 GMT, Thu, 27 Aug 2026 00:00:00 GMT");
  }

  @Test
  void a_header_that_arrives_later_never_erases_one_already_recorded() throws Exception {
    send("traces.modern.json", status().isAccepted());
    // The legacy export carries a sunset but no deprecation for the same endpoint.
    send("traces.legacy.json", status().isAccepted());

    RuntimeObservation o = observations.forContract(7100, ASSISTANTS).getFirst();
    assertThat(o.deprecationHeader()).isEqualTo("true");
  }

  @Test
  void the_findings_api_carries_the_runtime_block() throws Exception {
    findings.insertOpen(7100, ASSISTANTS, "openai-assistants-api-shutdown-2026", "f1", "breaking", null);
    send("traces.modern.json", status().isAccepted());

    mvc.perform(get("/api/repos/7100/findings").header("Authorization", AUTH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].runtime.callsPerDay").value(1))
        .andExpect(jsonPath("$[0].runtime.daysObserved").value(1))
        .andExpect(jsonPath("$[0].runtime.sunsetHeader").value("Wed, 26 Aug 2026 00:00:00 GMT"));
  }

  @Test
  void a_finding_with_no_telemetry_has_no_runtime_block() throws Exception {
    findings.insertOpen(7100, ASSISTANTS, "openai-assistants-api-shutdown-2026", "f1", "breaking", null);

    mvc.perform(get("/api/repos/7100/findings").header("Authorization", AUTH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].runtime").doesNotExist());
  }

  @Test
  void the_repository_runtime_endpoint_lists_observations() throws Exception {
    send("traces.modern.json", status().isAccepted());

    mvc.perform(get("/api/repos/7100/runtime").header("Authorization", AUTH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
  }
}
