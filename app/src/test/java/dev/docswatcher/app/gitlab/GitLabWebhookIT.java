package dev.docswatcher.app.gitlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.docswatcher.app.store.FindingStore;
import dev.docswatcher.app.store.Repo;
import dev.docswatcher.app.store.RepoStore;
import dev.docswatcher.app.store.ScanRun;
import dev.docswatcher.app.store.ScanRunStore;
import dev.docswatcher.app.support.FakeScanEngine;
import dev.docswatcher.app.support.GitLabMock;
import dev.docswatcher.app.support.GitLabSeed;
import dev.docswatcher.app.support.PostgresTest;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * GitLab's webhooks: the secret token that authenticates them, the namespace that bounds them, and
 * what a push and an issue event do.
 */
@AutoConfigureMockMvc
class GitLabWebhookIT extends PostgresTest {

  static final long PROJECT = 901;

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired GitLabStore store;
  @Autowired TokenCipher cipher;
  @Autowired RepoStore repos;
  @Autowired ScanRunStore runs;
  @Autowired FindingStore findings;
  @Autowired GitLabMock gitlab;

  GitLabSeed.Seeded acme;
  GitLabSeed.Seeded globex;
  long repoId;

  @BeforeEach
  void seed() {
    jdbc.sql("delete from installation").update();
    gitlab.reset();
    acme = GitLabSeed.group(store, cipher, "acme", 55);
    globex = GitLabSeed.group(store, cipher, "globex", 66);
    repoId = store.addProject(acme.id(), PROJECT, "acme/api", "main").orElseThrow();
  }

  static String push(long projectId, String path, String ref, String after) {
    return """
        {"object_kind":"push","event_name":"push","ref":"%s","before":"0000000000000000000000000000000000000000","after":"%s",
         "checkout_sha":"%s","project_id":%d,
         "project":{"id":%d,"path_with_namespace":"%s","default_branch":"main","web_url":"https://gitlab.test/%s"}}
        """.formatted(ref, after, after, projectId, projectId, path, path);
  }

  static String issue(long projectId, String path, int iid, String action, long userId, String previousLabels, String currentLabels) {
    return """
        {"object_kind":"issue","event_type":"issue","user":{"id":%d,"username":"u%d"},
         "project":{"id":%d,"path_with_namespace":"%s"},
         "object_attributes":{"iid":%d,"action":"%s","state":"opened"},
         "changes":{"labels":{"previous":[%s],"current":[%s]}}}
        """.formatted(userId, userId, projectId, path, iid, action, previousLabels, currentLabels);
  }

  private ResultActions send(String token, String body) throws Exception {
    return send(token, body, UUID.randomUUID().toString());
  }

  private ResultActions send(String token, String body, String idempotencyKey) throws Exception {
    MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post("/webhooks/gitlab")
        .header("X-Gitlab-Event", "Push Hook")
        .header("Idempotency-Key", idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
    if (token != null) {
      request.header("X-Gitlab-Token", token);
    }
    return mvc.perform(request);
  }

  private long queued() {
    return jdbc.sql("select count(*) from scan_run where status = 'queued'").query(Long.class).single();
  }

  @Test
  void a_missing_or_wrong_token_is_refused_before_anything_is_read() throws Exception {
    String body = push(PROJECT, "acme/api", "refs/heads/main", "a".repeat(40));
    send(null, body).andExpect(status().isUnauthorized());
    send("", body).andExpect(status().isUnauthorized());
    send(acme.webhookToken() + "x", body).andExpect(status().isUnauthorized());
    send("x".repeat(1000), body).andExpect(status().isUnauthorized());
    assertThat(queued()).isZero();
  }

  @Test
  void the_webhook_token_is_stored_only_as_its_hash() {
    assertThat(jdbc.sql("select count(*) from gitlab_connection where position(convert_to(:t, 'UTF8') in webhook_token_hash) > 0")
        .param("t", acme.webhookToken()).query(Long.class).single()).isZero();
    assertThat(store.webhookTokenHash(acme.id())).isEqualTo(GitLabSeed.sha256(acme.webhookToken()));
  }

  @Test
  void a_push_to_the_default_branch_queues_a_scan_of_that_commit() throws Exception {
    String sha = "b".repeat(40);
    send(acme.webhookToken(), push(PROJECT, "acme/api", "refs/heads/main", sha)).andExpect(status().isAccepted()).andExpect(content().string("queued"));
    ScanRun run = runs.forRepo(repoId).getFirst();
    assertThat(run.sha()).isEqualTo(sha);
    assertThat(run.trigger()).isEqualTo(ScanRun.TRIGGER_PUSH);
  }

  @Test
  void a_push_elsewhere_or_a_branch_deletion_queues_nothing() throws Exception {
    send(acme.webhookToken(), push(PROJECT, "acme/api", "refs/heads/feature", "c".repeat(40))).andExpect(status().isAccepted());
    send(acme.webhookToken(), push(PROJECT, "acme/api", "refs/heads/main", "0".repeat(40))).andExpect(status().isAccepted());
    assertThat(queued()).isZero();
  }

  @Test
  void one_group_s_token_cannot_trigger_another_group_s_project() throws Exception {
    // globex's token, naming acme's project: ignored, and GitLab is not asked about it either.
    send(globex.webhookToken(), push(PROJECT, "acme/api", "refs/heads/main", "d".repeat(40))).andExpect(status().isAccepted());
    // globex's token, with a payload claiming acme's project now lives under globex. The stored path
    // decides, not the payload's: nothing is queued and nothing is renamed.
    send(globex.webhookToken(), push(PROJECT, "globex/api", "refs/heads/main", "d".repeat(40))).andExpect(status().isAccepted());
    openFinding();
    send(globex.webhookToken(), issue(PROJECT, "globex/api", 3, "close", 42, "", "")).andExpect(status().isAccepted());
    assertThat(queued()).isZero();
    assertThat(repos.find(repoId).orElseThrow().fullName()).isEqualTo("acme/api");
    assertThat(findingStatus()).isEqualTo("open");
    gitlab.server.verify();
  }

  @Test
  void a_rename_the_payload_reports_is_taken_from_gitlab() throws Exception {
    gitlab.server.expect(requestTo(GitLabMock.API + "/projects/" + PROJECT))
        .andRespond(withSuccess("{\"id\":901,\"path_with_namespace\":\"acme/renamed\",\"default_branch\":\"main\"}", MediaType.APPLICATION_JSON));
    send(acme.webhookToken(), push(PROJECT, "acme/renamed", "refs/heads/main", "9".repeat(40))).andExpect(status().isAccepted());
    gitlab.server.verify();
    assertThat(repos.find(repoId).orElseThrow().fullName()).isEqualTo("acme/renamed");
    assertThat(runs.forRepo(repoId)).hasSize(1);
  }

  @Test
  void a_retried_delivery_is_handled_once() throws Exception {
    String body = push(PROJECT, "acme/api", "refs/heads/main", "e".repeat(40));
    send(acme.webhookToken(), body, "retry-key").andExpect(status().isAccepted()).andExpect(content().string("queued"));
    send(acme.webhookToken(), body, "retry-key").andExpect(status().isAccepted()).andExpect(content().string("duplicate delivery"));
    assertThat(runs.forRepo(repoId)).hasSize(1);
  }

  @Test
  void a_project_new_to_the_group_is_confirmed_with_gitlab_then_scanned_whole() throws Exception {
    gitlab.server.expect(requestTo(GitLabMock.API + "/projects/902"))
        .andExpect(header("Authorization", "Bearer " + GitLabSeed.ACCESS_TOKEN))
        .andRespond(withSuccess("{\"id\":902,\"path_with_namespace\":\"acme/sub/new\",\"default_branch\":\"trunk\"}", MediaType.APPLICATION_JSON));
    send(acme.webhookToken(), push(902, "acme/sub/new", "refs/heads/trunk", "f".repeat(40))).andExpect(status().isAccepted());
    gitlab.server.verify();
    Repo adopted = store.findProject(902).orElseThrow();
    assertThat(adopted.fullName()).isEqualTo("acme/sub/new");
    assertThat(adopted.defaultBranch()).isEqualTo("trunk");
    assertThat(adopted.provider()).isEqualTo("gitlab");
    assertThat(adopted.id()).isNegative();
    assertThat(runs.forRepo(adopted.id())).singleElement().extracting(ScanRun::trigger).isEqualTo(ScanRun.TRIGGER_INSTALL);
  }

  @Test
  void gitlab_s_answer_about_a_new_project_outweighs_the_payload() throws Exception {
    // The payload claims acme/evil; GitLab says the project is globex/secret, outside acme.
    gitlab.server.expect(requestTo(GitLabMock.API + "/projects/903"))
        .andRespond(withSuccess("{\"id\":903,\"path_with_namespace\":\"globex/secret\",\"default_branch\":\"main\"}", MediaType.APPLICATION_JSON));
    send(acme.webhookToken(), push(903, "acme/evil", "refs/heads/main", "1".repeat(40))).andExpect(status().isAccepted());
    gitlab.server.verify();
    assertThat(store.findProject(903)).isEmpty();
    assertThat(queued()).isZero();
  }

  private void openFinding() {
    findings.insertOpen(repoId, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID, "f1", "breaking", LocalDate.of(2026, 10, 23));
    findings.setIssueNumber(repoId, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID, 3);
  }

  private String findingStatus() {
    return findings.find(repoId, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID).orElseThrow().status();
  }

  private void role(long userId, int level) {
    gitlab.server.expect(requestTo(GitLabMock.API + "/projects/" + PROJECT + "/members/all/" + userId))
        .andExpect(header("Authorization", "Bearer " + GitLabSeed.ACCESS_TOKEN))
        .andRespond(withSuccess("{\"id\":" + userId + ",\"access_level\":" + level + "}", MediaType.APPLICATION_JSON));
  }

  @Test
  void a_developer_closing_the_issue_marks_the_finding_not_affected_and_reopening_takes_it_back() throws Exception {
    openFinding();
    role(42, 30);
    send(acme.webhookToken(), issue(PROJECT, "acme/api", 3, "close", 42, "", "")).andExpect(status().isAccepted());
    assertThat(findingStatus()).isEqualTo("not_affected");
    gitlab.server.verify();
    gitlab.server.reset();
    role(42, 30);
    send(acme.webhookToken(), issue(PROJECT, "acme/api", 3, "reopen", 42, "", "")).andExpect(status().isAccepted());
    assertThat(findingStatus()).isEqualTo("open");
    gitlab.server.verify();
  }

  @Test
  void a_reporter_cannot_command_a_finding() throws Exception {
    openFinding();
    role(43, 20);
    send(acme.webhookToken(), issue(PROJECT, "acme/api", 3, "close", 43, "", "")).andExpect(status().isAccepted());
    assertThat(findingStatus()).isEqualTo("open");
    gitlab.server.verify();
  }

  @Test
  void docswatcher_s_own_close_is_not_a_person_s_verdict() throws Exception {
    openFinding();
    send(acme.webhookToken(), issue(PROJECT, "acme/api", 3, "close", GitLabSeed.BOT_USER, "", "")).andExpect(status().isAccepted());
    assertThat(findingStatus()).isEqualTo("open");
    gitlab.server.verify();
  }

  @Test
  void adding_the_snooze_label_snoozes_the_finding() throws Exception {
    openFinding();
    role(42, 40);
    send(acme.webhookToken(), issue(PROJECT, "acme/api", 3, "update", 42,
        "{\"title\":\"docswatcher\"}", "{\"title\":\"docswatcher\"},{\"title\":\"docswatcher:snooze-30d\"}")).andExpect(status().isAccepted());
    assertThat(findingStatus()).isEqualTo("snoozed");
    gitlab.server.verify();
  }

  @Test
  void an_update_that_adds_no_command_label_asks_gitlab_nothing() throws Exception {
    openFinding();
    send(acme.webhookToken(), issue(PROJECT, "acme/api", 3, "update", 42,
        "{\"title\":\"docswatcher:snooze-30d\"}", "{\"title\":\"docswatcher:snooze-30d\"},{\"title\":\"bug\"}")).andExpect(status().isAccepted());
    assertThat(findingStatus()).isEqualTo("open");
    gitlab.server.verify();
  }
}
