package dev.docswatcher.app.scan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.docswatcher.app.engine.ScanEngine;
import dev.docswatcher.app.github.GitHubClient;
import dev.docswatcher.app.model.InventoryDoc;
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
    ((FakeScanEngine) engine).incomplete = null;
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

  /**
   * A scan a limit stopped did not read every file, so a contract it did not see may be in one it
   * skipped. It keeps the finding open, closes no issue, and says it is incomplete on the check.
   */
  @Test
  void anIncompleteScanClosesNothingAndSaysSoOnTheCheck() throws Exception {
    source.commitFile("models.yaml", "model: gpt-4-turbo\n", "add model");
    runs.enqueue(9001, null, ScanRun.TRIGGER_INSTALL);
    worker.drain();

    String sha2 = source.removeFile("models.yaml", "the scan will not see it");
    ((FakeScanEngine) engine).incomplete = new InventoryDoc.Incomplete("maxFiles", 20000, 1234);
    runs.enqueue(9001, sha2, ScanRun.TRIGGER_PUSH);
    worker.drain();

    assertThat(findings.find(9001, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID).orElseThrow().status()).isEqualTo("open");
    assertThat(github.calls("closeIssue")).isEmpty();
    assertThat(contracts.currentForRepo(9001)).singleElement().extracting("id").isEqualTo(FakeScanEngine.CONTRACT_ID);
    var check = github.calls("createCheckRun").get(1);
    assertThat(check.args()[5].toString()).startsWith("Incomplete scan: ");
    assertThat(check.args()[6].toString()).contains("**This scan is incomplete.**").contains("20000-file limit with 1234 files not scanned");
    assertThat(runs.forRepo(9001).getFirst().statsJson()).contains("filesNotScanned").contains("1234");

    ((FakeScanEngine) engine).incomplete = null;
    runs.enqueue(9001, sha2, ScanRun.TRIGGER_MANUAL);
    worker.drain();
    assertThat(findings.find(9001, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID).orElseThrow().status()).isEqualTo("fixed");
  }

  @Test
  void anIncompleteScanWithNothingFoundIsNeutralNotSuccess() throws Exception {
    source.commitFile("README.md", "nothing here\n", "init");
    ((FakeScanEngine) engine).incomplete = new InventoryDoc.Incomplete("maxDuration", 600000, 3);
    runs.enqueue(9001, null, ScanRun.TRIGGER_INSTALL);
    worker.drain();
    var check = github.calls("createCheckRun").getFirst();
    assertThat(check.args()[4]).isEqualTo("neutral");
    assertThat(check.args()[6].toString()).contains("600-second limit").contains("the files that were read");
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
  void theOrganisationsSharedRecordsReachEveryRepositoryAndABrokenCopyNeverClosesTheirFindings() throws Exception {
    FakeScanEngine fake = (FakeScanEngine) engine;
    fake.sharedSeen.clear();
    try (LocalRepo org = LocalRepo.create()) {
      org.commitFile(".docswatcher/providers/internal-orders/provider.yaml", "id: internal-orders\nname: Orders service\n", "records");
      repos.upsert(9002, 5001, "acme/.docswatcher", "main");
      github.cloneSources.put("acme/.docswatcher", new GitHubClient.CloneSource(org.uri(), null, null));
      source.commitFile("orders.ts", "fetch('/v1/orders')\n", "call orders");

      runs.enqueue(9001, null, ScanRun.TRIGGER_INSTALL);
      assertThat(worker.drain()).isEqualTo(1);

      assertThat(fake.sharedSeen).containsExactly("acme/.docswatcher/.docswatcher");
      var finding = findings.find(9001, FakeScanEngine.OWN_CONTRACT_ID, FakeScanEngine.OWN_CHANGE_ID).orElseThrow();
      assertThat(finding.status()).isEqualTo("open");
      var issue = github.calls("createIssue").getFirst();
      assertThat(issue.args()[2].toString()).isEqualTo("[DocsWatcher] Orders API v1 is switched off affects ANY /v1/orders");
      assertThat(issue.args()[3].toString()).contains("Replacement: `POST /v2/orders`");

      // A broken copy of the records: the scan still runs, says why they were not used, and leaves
      // their findings open instead of closing them as fixed.
      org.commitFile(".docswatcher/INVALID", "x", "break the records");
      github.calls.clear();
      runs.enqueue(9001, null, ScanRun.TRIGGER_MANUAL);
      worker.drain();
      assertThat(runs.forRepo(9001).getFirst().status()).isEqualTo(ScanRun.DONE);
      assertThat(findings.find(9001, FakeScanEngine.OWN_CONTRACT_ID, FakeScanEngine.OWN_CHANGE_ID).orElseThrow().status()).isEqualTo("open");
      assertThat(github.calls("closeIssue")).isEmpty();
      var check = github.calls("createCheckRun").getFirst();
      assertThat(check.args()[4]).isEqualTo("neutral");
      assertThat(check.args()[5].toString()).startsWith("Your own API records were not used (1 error)");
      assertThat(check.args()[6].toString()).contains("name missing");

      // A rematch reads the bundled knowledge only, so it leaves them alone too.
      runs.enqueue(9001, null, ScanRun.TRIGGER_REMATCH);
      worker.drain();
      assertThat(findings.find(9001, FakeScanEngine.OWN_CONTRACT_ID, FakeScanEngine.OWN_CHANGE_ID).orElseThrow().status()).isEqualTo("open");

      // Records that are read and no longer match close it.
      org.removeFile(".docswatcher/INVALID", "fix the records");
      source.removeFile("orders.ts", "migrate to v2");
      runs.enqueue(9001, null, ScanRun.TRIGGER_MANUAL);
      worker.drain();
      assertThat(findings.find(9001, FakeScanEngine.OWN_CONTRACT_ID, FakeScanEngine.OWN_CHANGE_ID).orElseThrow().status()).isEqualTo("fixed");
    }
  }

  @Test
  void anUnreadableRecordsRepositoryIsReportedAndTheScanStillRuns() throws Exception {
    source.commitFile("app.ts", "export {};\n", "code");
    repos.upsert(9002, 5001, "acme/.docswatcher", "main");
    github.cloneSources.put("acme/.docswatcher", new GitHubClient.CloneSource("file:///definitely/not/a/repo", null, null));
    runs.enqueue(9001, null, ScanRun.TRIGGER_INSTALL);
    worker.drain();
    assertThat(runs.forRepo(9001).getFirst().status()).isEqualTo(ScanRun.DONE);
    var check = github.calls("createCheckRun").getFirst();
    assertThat(check.args()[6].toString()).contains("acme/.docswatcher could not be read");
  }

  @Test
  void theRecordsRepositoryItselfIsScannedWithoutCloningItTwice() throws Exception {
    repos.upsert(9002, 5001, "acme/.docswatcher", "main");
    github.cloneSources.put("acme/.docswatcher", new GitHubClient.CloneSource(source.uri(), null, null));
    source.commitFile(".docswatcher/providers/internal-orders/provider.yaml", "id: internal-orders\n", "records");
    runs.enqueue(9002, null, ScanRun.TRIGGER_INSTALL);
    worker.drain();
    assertThat(runs.forRepo(9002).getFirst().status()).isEqualTo(ScanRun.DONE);
    assertThat(github.calls("cloneSource")).hasSize(1);
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
