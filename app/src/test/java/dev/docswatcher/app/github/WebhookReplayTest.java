package dev.docswatcher.app.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.docswatcher.app.store.FindingStore;
import dev.docswatcher.app.store.InstallationStore;
import dev.docswatcher.app.store.RepoStore;
import dev.docswatcher.app.store.ScanRun;
import dev.docswatcher.app.store.ScanRunStore;
import dev.docswatcher.app.support.FakeGitHubClient;
import dev.docswatcher.app.support.FakeScanEngine;
import dev.docswatcher.app.support.PostgresTest;
import dev.docswatcher.app.support.Webhooks;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class WebhookReplayTest extends PostgresTest {

  @Autowired MockMvc mvc;
  @Autowired FakeGitHubClient github;
  @Autowired InstallationStore installations;
  @Autowired RepoStore repos;
  @Autowired ScanRunStore runs;
  @Autowired FindingStore findings;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void clean() {
    jdbc.sql("delete from installation").update();
    jdbc.sql("delete from webhook_delivery").update();
    github.reset();
    github.installationRepos = List.of(
        new GitHubClient.InstallationRepo(9001, "acme/checkout-service", "main"),
        new GitHubClient.InstallationRepo(9002, "acme/notify", "main"),
        new GitHubClient.InstallationRepo(9003, "acme/billing", "develop"));
  }

  @Test
  void installationCreatedStoresInstallationAndQueuesOneScanPerRepo() throws Exception {
    Webhooks.post(mvc, "installation", Webhooks.payload("installation.created.json")).andExpect(status().isAccepted());

    assertThat(installations.find(5001)).isPresent().get().extracting("accountLogin").isEqualTo("acme");
    assertThat(repos.forInstallation(5001)).hasSize(3);
    assertThat(runs.forRepo(9001)).singleElement().extracting(ScanRun::trigger).isEqualTo(ScanRun.TRIGGER_INSTALL);
    assertThat(runs.forRepo(9002)).hasSize(1);
    assertThat(github.calls("listInstallationRepos")).hasSize(1);
  }

  @Test
  void pushToDefaultBranchQueuesScanAtTheSha() throws Exception {
    Webhooks.post(mvc, "installation", Webhooks.payload("installation.created.json"));
    Webhooks.post(mvc, "push", Webhooks.payload("push.default.json")).andExpect(status().isAccepted());

    List<ScanRun> r = runs.forRepo(9001);
    assertThat(r).hasSize(2);
    assertThat(r.getFirst().trigger()).isEqualTo(ScanRun.TRIGGER_PUSH);
    assertThat(r.getFirst().sha()).isEqualTo("2222222222222222222222222222222222222222");
  }

  @Test
  void pushToTheOrganisationsRecordsRepositoryRescansEveryRepository() throws Exception {
    github.installationRepos = List.of(
        new GitHubClient.InstallationRepo(9001, "acme/checkout-service", "main"),
        new GitHubClient.InstallationRepo(9004, "acme/.docswatcher", "main"));
    Webhooks.post(mvc, "installation", Webhooks.payload("installation.created.json"));
    Webhooks.post(mvc, "push", Webhooks.utf8("""
        {"ref": "refs/heads/main", "after": "3333333333333333333333333333333333333333",
         "repository": {"id": 9004, "name": ".docswatcher", "full_name": "acme/.docswatcher", "default_branch": "main"},
         "installation": {"id": 5001}}
        """)).andExpect(status().isAccepted());

    assertThat(runs.forRepo(9004).getFirst().trigger()).isEqualTo(ScanRun.TRIGGER_PUSH);
    assertThat(runs.forRepo(9001).getFirst().trigger()).isEqualTo(ScanRun.TRIGGER_ORG_RECORDS);
    assertThat(runs.forRepo(9001)).hasSize(2);
  }

  @Test
  void pushToOtherBranchQueuesNothing() throws Exception {
    Webhooks.post(mvc, "installation", Webhooks.payload("installation.created.json"));
    Webhooks.post(mvc, "push", Webhooks.payload("push.feature.json")).andExpect(status().isAccepted());
    assertThat(runs.forRepo(9001)).hasSize(1);
  }

  @Test
  void installationDeletedRemovesEverythingAndCancelsQueuedRuns() throws Exception {
    Webhooks.post(mvc, "installation", Webhooks.payload("installation.created.json"));
    Webhooks.post(mvc, "installation", Webhooks.payload("installation.deleted.json")).andExpect(status().isAccepted());
    assertThat(installations.find(5001)).isEmpty();
    assertThat(repos.find(9001)).isEmpty();
  }

  @Test
  void installationDeletedForgetsTheInstallationToken() throws Exception {
    Webhooks.post(mvc, "installation", Webhooks.payload("installation.created.json"));
    github.calls.clear();
    Webhooks.post(mvc, "installation", Webhooks.payload("installation.deleted.json")).andExpect(status().isAccepted());
    assertThat(github.calls("forgetInstallation")).singleElement().extracting(c -> c.args()[0]).isEqualTo(5001L);
  }

  @Test
  void installationSuspendedIsMarkedAndForgetsTheInstallationToken() throws Exception {
    Webhooks.post(mvc, "installation", Webhooks.payload("installation.created.json"));
    github.calls.clear();
    Webhooks.post(mvc, "installation", Webhooks.payload("installation.suspend.json")).andExpect(status().isAccepted());
    assertThat(installations.find(5001)).isPresent().get().extracting("suspendedAt").isNotNull();
    assertThat(github.calls("forgetInstallation")).singleElement().extracting(c -> c.args()[0]).isEqualTo(5001L);
  }

  /** The repository listing that follows must not run on a token minted under the old permissions. */
  @Test
  void newPermissionsForgetTheTokenBeforeListingRepositories() throws Exception {
    Webhooks.post(mvc, "installation", Webhooks.payload("installation.created.json"));
    github.calls.clear();
    Webhooks.post(mvc, "installation", Webhooks.payload("installation.new_permissions_accepted.json")).andExpect(status().isAccepted());
    assertThat(github.calls).extracting(FakeGitHubClient.Call::method).containsSubsequence("forgetInstallation", "listInstallationRepos");
  }

  @Test
  void repositoriesRemovedAreDroppedAndForgetTheInstallationToken() throws Exception {
    Webhooks.post(mvc, "installation", Webhooks.payload("installation.created.json"));
    github.calls.clear();
    Webhooks.post(mvc, "installation_repositories", Webhooks.payload("installation_repositories.removed.json")).andExpect(status().isAccepted());
    assertThat(repos.find(9002)).isEmpty();
    assertThat(repos.find(9001)).isPresent();
    assertThat(github.calls("forgetInstallation")).singleElement().extracting(c -> c.args()[0]).isEqualTo(5001L);
  }

  @Test
  void repositoriesAddedForgetTheTokenBeforeListingRepositories() throws Exception {
    Webhooks.post(mvc, "installation", Webhooks.payload("installation.created.json"));
    github.calls.clear();
    Webhooks.post(mvc, "installation_repositories", Webhooks.payload("installation_repositories.added.json")).andExpect(status().isAccepted());
    assertThat(github.calls).extracting(FakeGitHubClient.Call::method).containsSubsequence("forgetInstallation", "listInstallationRepos");
  }

  @Test
  void repositoriesAddedAreRegisteredAndQueued() throws Exception {
    Webhooks.post(mvc, "installation", Webhooks.payload("installation.created.json"));
    jdbc.sql("delete from repo where id = 9003").update();
    Webhooks.post(mvc, "installation_repositories", Webhooks.payload("installation_repositories.added.json")).andExpect(status().isAccepted());
    assertThat(repos.find(9003)).isPresent().get().extracting("defaultBranch").isEqualTo("develop");
    assertThat(runs.forRepo(9003)).hasSize(1);
  }

  @Test
  void invalidSignatureIsRejectedAndNothingIsStored() throws Exception {
    byte[] body = Webhooks.payload("installation.created.json");
    Webhooks.post(mvc, "installation", body, "sha256=deadbeef", UUID.randomUUID().toString()).andExpect(status().isUnauthorized());
    assertThat(installations.find(5001)).isEmpty();
  }

  @Test
  void replayedDeliveryIsAcknowledgedAndIgnored() throws Exception {
    byte[] body = Webhooks.payload("installation.created.json");
    String sig = WebhookSignature.compute(Webhooks.SECRET, body);
    Webhooks.post(mvc, "installation", body, sig, "delivery-1").andExpect(status().isAccepted());
    Webhooks.post(mvc, "installation", body, sig, "delivery-1").andExpect(status().isAccepted());
    assertThat(github.calls("listInstallationRepos")).hasSize(1);
    assertThat(runs.forRepo(9001)).hasSize(1);
  }

  @Test
  void fixLabelOnFindingIssueDispatchesWorkflowWithContext() throws Exception {
    Webhooks.post(mvc, "installation", Webhooks.payload("installation.created.json"));
    findings.insertOpen(9001, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID, "acme/checkout-service:x", "breaking", LocalDate.of(2026, 10, 23));
    findings.setIssueNumber(9001, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID, 101);

    Webhooks.post(mvc, "issues", Webhooks.payload("issues.labeled.fix.json")).andExpect(status().isAccepted());

    var dispatches = github.calls("repositoryDispatch");
    assertThat(dispatches).hasSize(1);
    assertThat(dispatches.getFirst().args()[1]).isEqualTo("acme/checkout-service");
    assertThat(dispatches.getFirst().args()[2]).isEqualTo("docswatcher-fix");
    assertThat(dispatches.getFirst().args()[3].toString()).contains("gpt-5.6-sol").contains("developers.openai.com");
  }

  /**
   * Applying a label is a triage capability on GitHub. The branches it reaches here spend the
   * installation's write authority, so the labeller's own permission is the gate. Before the
   * actor was checked, this payload produced a dispatch regardless of who sent it.
   */
  @Test
  void labelFromAnActorWithoutWritePermissionDoesNothing() throws Exception {
    Webhooks.post(mvc, "installation", Webhooks.payload("installation.created.json"));
    findings.insertOpen(9001, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID, "acme/checkout-service:x", "breaking", LocalDate.of(2026, 10, 23));
    findings.setIssueNumber(9001, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID, 101);
    github.permission = "triage";

    Webhooks.post(mvc, "issues", Webhooks.payload("issues.labeled.fix.json")).andExpect(status().isAccepted());

    assertThat(github.calls("repositoryDispatch")).isEmpty();
  }

  @Test
  void notAffectedLabelMarksTheFindingNotAffected() throws Exception {
    givenFindingWithIssue101();

    Webhooks.post(mvc, "issues", Webhooks.payload("issues.labeled.not-affected.json")).andExpect(status().isAccepted());

    assertThat(findingStatus()).isEqualTo("not_affected");
    assertThat(github.calls("repositoryDispatch")).isEmpty();
  }

  @Test
  void notAffectedLabelFromAnActorWithoutWritePermissionDoesNothing() throws Exception {
    givenFindingWithIssue101();
    github.permission = "triage";

    Webhooks.post(mvc, "issues", Webhooks.payload("issues.labeled.not-affected.json")).andExpect(status().isAccepted());

    assertThat(findingStatus()).isEqualTo("open");
  }

  /** Closing the issue by hand is the same verdict as the label, and reopening it takes it back. */
  @Test
  void closingTheIssueByHandMarksNotAffectedAndReopeningReopens() throws Exception {
    givenFindingWithIssue101();

    Webhooks.post(mvc, "issues", Webhooks.payload("issues.closed.person.json")).andExpect(status().isAccepted());
    assertThat(findingStatus()).isEqualTo("not_affected");

    Webhooks.post(mvc, "issues", Webhooks.payload("issues.reopened.person.json")).andExpect(status().isAccepted());
    assertThat(findingStatus()).isEqualTo("open");
  }

  @Test
  void closeFromAnActorWithoutWritePermissionDoesNothing() throws Exception {
    givenFindingWithIssue101();
    github.permission = "triage";

    Webhooks.post(mvc, "issues", Webhooks.payload("issues.closed.person.json")).andExpect(status().isAccepted());

    assertThat(findingStatus()).isEqualTo("open");
  }

  /** The App closes an issue when a finding's evidence disappears; its own close is not a verdict. */
  @Test
  void closeByTheAppItselfIsIgnored() throws Exception {
    givenFindingWithIssue101();

    Webhooks.post(mvc, "issues", Webhooks.payload("issues.closed.app.json")).andExpect(status().isAccepted());

    assertThat(findingStatus()).isEqualTo("open");
    assertThat(github.calls("collaboratorPermission")).isEmpty();
  }

  @Test
  void closingAnIssueOfAFixedFindingKeepsItFixed() throws Exception {
    givenFindingWithIssue101();
    findings.close(9001, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID);

    Webhooks.post(mvc, "issues", Webhooks.payload("issues.closed.person.json")).andExpect(status().isAccepted());

    assertThat(findingStatus()).isEqualTo("fixed");
  }

  @Test
  void reopeningASnoozedFindingsIssueLeavesTheSnooze() throws Exception {
    givenFindingWithIssue101();
    findings.setStatus(9001, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID, "snoozed", LocalDate.now().plusDays(30));

    Webhooks.post(mvc, "issues", Webhooks.payload("issues.reopened.person.json")).andExpect(status().isAccepted());

    assertThat(findingStatus()).isEqualTo("snoozed");
  }

  private void givenFindingWithIssue101() throws Exception {
    Webhooks.post(mvc, "installation", Webhooks.payload("installation.created.json"));
    findings.insertOpen(9001, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID, "acme/checkout-service:x", "breaking", LocalDate.of(2026, 10, 23));
    findings.setIssueNumber(9001, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID, 101);
    github.calls.clear();
  }

  private String findingStatus() {
    return findings.find(9001, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID).orElseThrow().status();
  }
}
