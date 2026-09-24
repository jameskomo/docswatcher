package dev.docswatcher.app.scan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.docswatcher.app.engine.ScanEngine;
import dev.docswatcher.app.github.GitHubClient;
import dev.docswatcher.app.store.ContractStore;
import dev.docswatcher.app.store.FindingStore;
import dev.docswatcher.app.store.InstallationStore;
import dev.docswatcher.app.store.RepoStore;
import dev.docswatcher.app.store.ScanRun;
import dev.docswatcher.app.store.ScanRunStore;
import dev.docswatcher.app.support.FakeGitHubClient;
import dev.docswatcher.app.support.FakeScanEngine;
import dev.docswatcher.app.support.LocalRepo;
import dev.docswatcher.app.support.PostgresTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class ScanRunnerIT extends PostgresTest {

  @Autowired ScanWorker worker;
  @Autowired ScanRunStore runs;
  @Autowired RepoStore repos;
  @Autowired InstallationStore installations;
  @Autowired ContractStore contracts;
  @Autowired FindingStore findings;
  @Autowired FakeGitHubClient github;
  @Autowired ScanEngine engine;
  @Autowired JdbcClient jdbc;

  LocalRepo source;

  @BeforeEach
  void setUp() throws Exception {
    jdbc.sql("delete from installation").update();
    github.reset();
    source = LocalRepo.create();
    github.cloneSource = new GitHubClient.CloneSource(source.uri(), null, null);
    installations.upsert(5001, "acme");
    repos.upsert(9001, 5001, "acme/checkout-service", "main");
  }

  @AfterEach
  void tearDown() {
    source.close();
  }

  @Test
  void scanStoresContractsOpensFindingWithIssueThenClosesItWhenTheCodeIsGone() throws Exception {
    String sha1 = source.commitFile("models.yaml", "model: gpt-4-turbo\n", "add model");
    runs.enqueue(9001, null, ScanRun.TRIGGER_INSTALL);

    assertThat(worker.drain()).isEqualTo(1);

    ScanRun done = runs.forRepo(9001).getFirst();
    assertThat(done.status()).isEqualTo(ScanRun.DONE);
    assertThat(done.knowledgeVersion()).isEqualTo("test");
    assertThat(repos.find(9001).orElseThrow().lastScannedSha()).isEqualTo(sha1);
    assertThat(contracts.currentForRepo(9001)).singleElement().extracting("id").isEqualTo(FakeScanEngine.CONTRACT_ID);
    var finding = findings.find(9001, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID).orElseThrow();
    assertThat(finding.status()).isEqualTo("open");
    assertThat(finding.issueNumber()).isEqualTo(101);
    assertThat(github.calls("createIssue")).hasSize(1);
    assertThat(github.calls("createIssue").getFirst().args()[3].toString()).contains("models.yaml:1").contains("docswatcher:fix");
    var check = github.calls("createCheckRun").getFirst();
    assertThat(check.args()[2]).isEqualTo(sha1);
    assertThat(check.args()[4]).isEqualTo("failure");

    String sha2 = source.removeFile("models.yaml", "migrate away");
    runs.enqueue(9001, sha2, ScanRun.TRIGGER_PUSH);
    assertThat(worker.drain()).isEqualTo(1);

    assertThat(findings.find(9001, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID).orElseThrow().status()).isEqualTo("fixed");
    assertThat(github.calls("closeIssue")).hasSize(1);
    assertThat(contracts.currentForRepo(9001)).isEmpty();
    assertThat(github.calls("createCheckRun").get(1).args()[4]).isEqualTo("success");
  }

  @Test
  void snoozeSurvivesRescan() throws Exception {
    source.commitFile("models.yaml", "model: gpt-4-turbo\n", "add model");
    runs.enqueue(9001, null, ScanRun.TRIGGER_INSTALL);
    worker.drain();
    findings.setStatus(9001, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID, "snoozed", java.time.LocalDate.now().plusDays(30));

    runs.enqueue(9001, null, ScanRun.TRIGGER_MANUAL);
    worker.drain();

    assertThat(findings.find(9001, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID).orElseThrow().status()).isEqualTo("snoozed");
    assertThat(github.calls("createIssue")).hasSize(1);
  }

  /** A person's not-affected verdict outlives rescans and stops the finding from failing the check. */
  @Test
  void notAffectedSurvivesRescanAndNoLongerFailsTheCheck() throws Exception {
    source.commitFile("models.yaml", "model: gpt-4-turbo\n", "add model");
    runs.enqueue(9001, null, ScanRun.TRIGGER_INSTALL);
    worker.drain();
    assertThat(github.calls("createIssue").getFirst().args()[3].toString()).contains("docswatcher:not-affected");
    findings.setStatus(9001, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID, "not_affected", null);

    runs.enqueue(9001, null, ScanRun.TRIGGER_MANUAL);
    worker.drain();

    assertThat(findings.find(9001, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID).orElseThrow().status()).isEqualTo("not_affected");
    assertThat(github.calls("createIssue")).hasSize(1);
    assertThat(github.calls("closeIssue")).isEmpty();
    assertThat(github.calls("createCheckRun").get(1).args()[4]).isEqualTo("success");
  }

  @Test
  void rematchRunsMatcherOverStoredContractsWithoutCloning() throws Exception {
    source.commitFile("models.yaml", "model: gpt-4-turbo\n", "add model");
    runs.enqueue(9001, null, ScanRun.TRIGGER_INSTALL);
    worker.drain();
    int scansBefore = ((FakeScanEngine) engine).scans;
    github.calls.clear();

    runs.enqueue(9001, null, ScanRun.TRIGGER_REMATCH);
    worker.drain();

    assertThat(((FakeScanEngine) engine).scans).isEqualTo(scansBefore);
    assertThat(github.calls("cloneSource")).isEmpty();
    assertThat(runs.forRepo(9001).getFirst().status()).isEqualTo(ScanRun.DONE);
    assertThat(findings.find(9001, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID).orElseThrow().status()).isEqualTo("open");
  }

  @Test
  void cloneFailureMarksRunFailed() {
    github.cloneSource = new GitHubClient.CloneSource("file:///definitely/not/a/repo", null, null);
    runs.enqueue(9001, null, ScanRun.TRIGGER_INSTALL);
    worker.drain();
    ScanRun run = runs.forRepo(9001).getFirst();
    assertThat(run.status()).isEqualTo(ScanRun.FAILED);
    assertThat(run.error()).isNotBlank();
  }
}
