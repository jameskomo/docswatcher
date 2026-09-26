package dev.docswatcher.app.gitlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.docswatcher.app.scan.ScanWorker;
import dev.docswatcher.app.store.ContractStore;
import dev.docswatcher.app.store.FindingStore;
import dev.docswatcher.app.store.RepoStore;
import dev.docswatcher.app.store.ScanRun;
import dev.docswatcher.app.store.ScanRunStore;
import dev.docswatcher.app.support.FakeGitHubClient;
import dev.docswatcher.app.support.FakeScanEngine;
import dev.docswatcher.app.support.GitLabMock;
import dev.docswatcher.app.support.GitLabSeed;
import dev.docswatcher.app.support.LocalRepo;
import dev.docswatcher.app.support.PostgresTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A GitLab project scanned end to end: the same worker, engine and reconciliation as GitHub, with
 * the issue, its closing and the commit status going to a scripted GitLab.
 */
class GitLabScanIT extends PostgresTest {

  static final String API = GitLabMock.API;
  static final long PROJECT = 901;

  @Autowired ScanWorker worker;
  @Autowired ScanRunStore runs;
  @Autowired RepoStore repos;
  @Autowired ContractStore contracts;
  @Autowired FindingStore findings;
  @Autowired GitLabStore store;
  @Autowired TokenCipher cipher;
  @Autowired GitLabMock gitlab;
  @Autowired FakeGitHubClient github;
  @Autowired JdbcClient jdbc;

  LocalRepo source;
  long repoId;

  @BeforeEach
  void setUp() throws Exception {
    jdbc.sql("delete from installation").update();
    gitlab.reset();
    github.reset();
    GitLabSeed.Seeded connection = GitLabSeed.group(store, cipher, "acme", 55);
    repoId = store.addProject(connection.id(), PROJECT, "acme/platform/api", "main").orElseThrow();
    source = LocalRepo.create();
    gitlab.clones.put("acme/platform/api", source.uri());
  }

  @AfterEach
  void tearDown() {
    source.close();
  }

  @Test
  void a_finding_opens_a_gitlab_issue_fails_the_commit_status_and_closes_when_the_code_is_gone() throws Exception {
    String sha1 = source.commitFile("models.yaml", "model: gpt-4-turbo\n", "add model");
    gitlab.server.expect(requestTo(API + "/projects/" + PROJECT + "/issues"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer " + GitLabSeed.ACCESS_TOKEN))
        .andExpect(jsonPath("$.title", containsString("[DocsWatcher]")))
        .andExpect(jsonPath("$.labels").value("docswatcher,docswatcher:breaking"))
        // Evidence links point at the project on the GitLab instance, at the scanned commit.
        .andExpect(jsonPath("$.description", containsString("(https://gitlab.test/acme/platform/api/-/blob/" + sha1 + "/models.yaml#L1)")))
        // There is no fix dispatch on GitLab, so the issue does not offer one; the other actions stay.
        .andExpect(jsonPath("$.description", not(containsString("docswatcher:fix"))))
        .andExpect(jsonPath("$.description", containsString("docswatcher:not-affected")))
        .andRespond(withSuccess("{\"id\":1234,\"iid\":3}", MediaType.APPLICATION_JSON));
    gitlab.server.expect(requestTo(API + "/projects/" + PROJECT + "/statuses/" + sha1))
        .andExpect(jsonPath("$.state").value("failed"))
        .andExpect(jsonPath("$.name").value("DocsWatcher"))
        .andExpect(jsonPath("$.description", containsString("1 findings, 1 breaking")))
        .andExpect(jsonPath("$.target_url").value("https://docswatcher.test/#/app"))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    runs.enqueue(repoId, null, ScanRun.TRIGGER_INSTALL);
    assertThat(worker.drain()).isEqualTo(1);
    gitlab.server.verify();

    assertThat(runs.forRepo(repoId).getFirst().status()).isEqualTo(ScanRun.DONE);
    assertThat(repos.find(repoId).orElseThrow().lastScannedSha()).isEqualTo(sha1);
    var finding = findings.find(repoId, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID).orElseThrow();
    assertThat(finding.status()).isEqualTo("open");
    assertThat(finding.issueNumber()).isEqualTo(3);
    // Nothing went to GitHub.
    assertThat(github.calls).isEmpty();

    String sha2 = source.removeFile("models.yaml", "migrate away");
    gitlab.server.reset();
    gitlab.server.expect(requestTo(API + "/projects/" + PROJECT + "/issues/3/notes"))
        .andExpect(jsonPath("$.body", containsString(sha2)))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    gitlab.server.expect(requestTo(API + "/projects/" + PROJECT + "/issues/3"))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(jsonPath("$.state_event").value("close"))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    gitlab.server.expect(requestTo(API + "/projects/" + PROJECT + "/statuses/" + sha2))
        .andExpect(jsonPath("$.state").value("success"))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    runs.enqueue(repoId, sha2, ScanRun.TRIGGER_PUSH);
    assertThat(worker.drain()).isEqualTo(1);
    gitlab.server.verify();

    assertThat(findings.find(repoId, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID).orElseThrow().status()).isEqualTo("fixed");
    assertThat(contracts.currentForRepo(repoId)).isEmpty();
  }

  @Test
  void a_person_s_not_affected_verdict_survives_a_rescan_and_no_second_issue_is_opened() throws Exception {
    String sha1 = source.commitFile("models.yaml", "model: gpt-4-turbo\n", "add model");
    gitlab.server.expect(requestTo(API + "/projects/" + PROJECT + "/issues")).andRespond(withSuccess("{\"iid\":4}", MediaType.APPLICATION_JSON));
    gitlab.server.expect(requestTo(API + "/projects/" + PROJECT + "/statuses/" + sha1)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    runs.enqueue(repoId, null, ScanRun.TRIGGER_INSTALL);
    worker.drain();
    gitlab.server.verify();
    findings.setStatus(repoId, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID, "not_affected", null);

    gitlab.server.reset();
    gitlab.server.expect(requestTo(API + "/projects/" + PROJECT + "/statuses/" + sha1))
        .andExpect(jsonPath("$.state").value("success"))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    runs.enqueue(repoId, null, ScanRun.TRIGGER_MANUAL);
    worker.drain();
    gitlab.server.verify();
    assertThat(findings.find(repoId, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID).orElseThrow().status()).isEqualTo("not_affected");
  }

  @Test
  void a_scan_whose_connection_token_no_longer_decrypts_fails_the_run_without_calling_gitlab() throws Exception {
    source.commitFile("models.yaml", "model: gpt-4-turbo\n", "add model");
    jdbc.sql("update gitlab_connection set token_ciphertext = :c").param("c", new byte[] {1, 2, 3}).update();
    runs.enqueue(repoId, null, ScanRun.TRIGGER_INSTALL);
    worker.drain();
    ScanRun run = runs.forRepo(repoId).getFirst();
    assertThat(run.status()).isEqualTo(ScanRun.FAILED);
    assertThat(run.error()).contains("not in a known format").doesNotContain(GitLabSeed.ACCESS_TOKEN);
    gitlab.server.verify();
  }
}
