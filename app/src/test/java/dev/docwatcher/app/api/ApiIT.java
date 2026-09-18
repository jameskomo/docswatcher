package dev.docwatcher.app.api;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.docwatcher.app.store.ContractStore;
import dev.docwatcher.app.store.FindingStore;
import dev.docwatcher.app.store.InstallationStore;
import dev.docwatcher.app.store.RepoStore;
import dev.docwatcher.app.store.ScanRunStore;
import dev.docwatcher.app.store.StoredContract;
import dev.docwatcher.app.support.FakeGitHubClient;
import dev.docwatcher.app.support.FakeScanEngine;
import dev.docwatcher.app.support.PostgresTest;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ApiIT extends PostgresTest {

  static final String AUTH = "Bearer test-token";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired InstallationStore installations;
  @Autowired RepoStore repos;
  @Autowired ContractStore contracts;
  @Autowired FindingStore findings;
  @Autowired ScanRunStore runs;
  @Autowired FakeGitHubClient github;

  @BeforeEach
  void seed() {
    jdbc.sql("delete from installation").update();
    github.reset();
    installations.upsert(5001, "acme");
    repos.upsert(9001, 5001, "acme/checkout-service", "main");
    repos.upsert(9002, 5001, "acme/notify", "main");
    repos.setLastScannedSha(9001, "abc123");
    repos.setLastScannedSha(9002, "def456");
    String evidence = "[{\"path\":\"config/models.yaml\",\"line\":2,\"column\":10,\"snippet\":\"model: gpt-4-turbo\",\"detector\":\"openai.literal.model-gpt\",\"layer\":\"literal\"}]";
    contracts.upsert(new StoredContract(9001, FakeScanEngine.CONTRACT_ID, "openai", "model", "gpt-4-turbo", "medium", evidence, null, "abc123", "abc123"));
    contracts.upsert(new StoredContract(9001, "stripe:endpoint:POST /v1/sources", "stripe", "endpoint", "POST /v1/sources", "high", evidence, "{\"sdk\":{\"ecosystem\":\"maven\",\"package\":\"com.stripe:stripe-java\",\"version\":\"29.0.0\"}}", "abc123", "abc123"));
    contracts.upsert(new StoredContract(9002, FakeScanEngine.CONTRACT_ID, "openai", "model", "gpt-4-turbo", "medium", evidence, null, "def456", "def456"));
    findings.insertOpen(9001, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID, "f1", "breaking", LocalDate.of(2026, 10, 23));
    findings.insertOpen(9002, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID, "f2", "breaking", LocalDate.of(2026, 10, 23));
    findings.insertOpen(9001, "stripe:endpoint:POST /v1/sources", "stripe-sources-api-deprecated", "f3", "warning", null);
  }

  @Test
  void rejectsMissingOrWrongToken() throws Exception {
    mvc.perform(get("/api/orgs/acme/overview")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/orgs/acme/overview").header("Authorization", "Bearer nope")).andExpect(status().isUnauthorized());
  }

  @Test
  void overviewCountsRepoContractsAndFindings() throws Exception {
    mvc.perform(get("/api/orgs/acme/overview").header("Authorization", AUTH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.repos").value(2))
        .andExpect(jsonPath("$.contracts").value(3))
        .andExpect(jsonPath("$.findingsBySeverity.breaking").value(2))
        .andExpect(jsonPath("$.findingsBySeverity.warning").value(1))
        .andExpect(jsonPath("$.nearestEffective").value("2026-10-23"));
  }

  @Test
  void inventoryHasSchemaShape() throws Exception {
    mvc.perform(get("/api/repos/9001/inventory").header("Authorization", AUTH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.schemaVersion").value("1"))
        .andExpect(jsonPath("$.repo.owner").value("acme"))
        .andExpect(jsonPath("$.repo.sha").value("abc123"))
        .andExpect(jsonPath("$.contracts", hasSize(2)))
        .andExpect(jsonPath("$.contracts[1].context.sdk.package").value("com.stripe:stripe-java"))
        .andExpect(jsonPath("$.contracts[0].evidence[0].line").value(2));
  }

  @Test
  void horizonGroupsByMonthWithUndatedLane() throws Exception {
    mvc.perform(get("/api/orgs/acme/horizon").header("Authorization", AUTH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].month").value("2026-10"))
        .andExpect(jsonPath("$[0].findings", hasSize(2)))
        .andExpect(jsonPath("$[1].month").value("undated"));
  }

  @Test
  void mapListsProvidersWithWorstSeverity() throws Exception {
    mvc.perform(get("/api/orgs/acme/map").header("Authorization", AUTH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(2)))
        .andExpect(jsonPath("$[0].provider").value("openai"))
        .andExpect(jsonPath("$[0].contracts").value(2))
        .andExpect(jsonPath("$[0].worstSeverity").value("breaking"))
        .andExpect(jsonPath("$[1].worstSeverity").value("warning"));
  }

  @Test
  void blastRadiusSpansRepos() throws Exception {
    mvc.perform(get("/api/orgs/acme/blast-radius/" + FakeScanEngine.CHANGE_ID).header("Authorization", AUTH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.repos").value(2))
        .andExpect(jsonPath("$.title").value("gpt-4-turbo shut down"))
        .andExpect(jsonPath("$.findings", hasSize(2)));
  }

  @Test
  void snoozeNotInProdFixAndRescan() throws Exception {
    String base = "/api/findings/9001/" + FakeScanEngine.CONTRACT_ID + "/" + FakeScanEngine.CHANGE_ID;
    mvc.perform(post(base + "/snooze").header("Authorization", AUTH).contentType("application/json").content("{\"days\":10}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("snoozed"));
    mvc.perform(post(base + "/not-in-prod").header("Authorization", AUTH)).andExpect(status().isOk());
    mvc.perform(post(base + "/fix").header("Authorization", AUTH))
        .andExpect(status().isOk()).andExpect(jsonPath("$.detail.finding.change").value(FakeScanEngine.CHANGE_ID))
        .andExpect(jsonPath("$.detail.guide").value("https://developers.openai.com/api/docs/deprecations"));
    mvc.perform(post("/api/repos/9001/rescan").header("Authorization", AUTH)).andExpect(status().isAccepted());
    mvc.perform(post("/api/repos/1/rescan").header("Authorization", AUTH)).andExpect(status().isNotFound());
  }

  @Test
  void setupWorkflowIsServed() throws Exception {
    mvc.perform(get("/api/setup/workflow").header("Authorization", AUTH))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("anthropics/claude-code-action@v1")));
  }

  @Test
  void flywayCreatedTheTables() throws Exception {
    Long count = jdbc.sql("select count(*) from information_schema.tables where table_schema = 'public' and table_name in ('installation','repo','contract','finding','scan_run','webhook_delivery')").query(Long.class).single();
    org.assertj.core.api.Assertions.assertThat(count).isEqualTo(6);
  }
}
