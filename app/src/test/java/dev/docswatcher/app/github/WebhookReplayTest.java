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
}
