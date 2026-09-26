package dev.docswatcher.app.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.docswatcher.app.config.AppProperties;
import dev.docswatcher.app.engine.EngineScanEngine;
import dev.docswatcher.app.engine.ProcessScanEngine;
import dev.docswatcher.app.github.GitHubClient;
import dev.docswatcher.app.store.ContractStore;
import dev.docswatcher.app.store.FindingStore;
import dev.docswatcher.app.store.InstallationStore;
import dev.docswatcher.app.store.RepoStore;
import dev.docswatcher.app.store.ScanRun;
import dev.docswatcher.app.store.ScanRunStore;
import dev.docswatcher.app.support.FakeGitHubClient;
import dev.docswatcher.app.support.LocalRepo;
import dev.docswatcher.app.support.PostgresTest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * A scan that ends badly fails its run and nothing else, and a run left running by a process that
 * stopped is failed and queued again once instead of staying running forever.
 */
@AutoConfigureMockMvc
class ScanIsolationIT extends PostgresTest {

  @Autowired AppProperties properties;
  @Autowired ScanWorker worker;
  @Autowired ScanRunStore runs;
  @Autowired RepoStore repos;
  @Autowired InstallationStore installations;
  @Autowired ContractStore contracts;
  @Autowired FindingStore findings;
  @Autowired FakeGitHubClient github;
  @Autowired dev.docswatcher.app.forge.Forges forges;
  @Autowired GitCloner cloner;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcClient jdbc;
  @Autowired MockMvc mvc;

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

  /** A scan process that dies the way a native crash does: at once, by a signal. */
  public static final class CrashingScan {
    public static void main(String[] args) {
      Runtime.getRuntime().halt(139);
    }
  }

  @Test
  void a_crashed_scan_process_fails_its_run_and_the_app_keeps_serving() throws Exception {
    source.commitFile("models.yaml", "model: gpt-4-turbo\n", "add model");
    ProcessScanEngine crashing = new ProcessScanEngine(new EngineScanEngine(properties, mapper),
        new ProcessScanEngine.Settings(CrashingScan.class.getName(), 128, Duration.ofMinutes(1), List.of()));
    ScanRunner runner = new ScanRunner(properties, crashing, forges, cloner, repos, contracts, findings, runs, mapper);
    long id = runs.enqueue(9001, null, ScanRun.TRIGGER_INSTALL);

    runner.run(runs.claimNext().orElseThrow());

    ScanRun failed = runs.find(id).orElseThrow();
    assertThat(failed.status()).isEqualTo(ScanRun.FAILED);
    assertThat(failed.error()).isEqualTo("The scan process crashed (signal 11)");
    assertThat(failed.finishedAt()).isNotNull();
    assertThat(github.calls("createCheckRun")).isEmpty();
    assertThat(contracts.currentForRepo(9001)).isEmpty();
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    // The worker's own engine still scans.
    runs.enqueue(9001, null, ScanRun.TRIGGER_MANUAL);
    assertThat(worker.drain()).isEqualTo(1);
    assertThat(runs.forRepo(9001).getFirst().status()).isEqualTo(ScanRun.DONE);
  }

  private long startedLongAgo(String sha, String trigger) {
    long id = runs.enqueue(9001, sha, trigger);
    runs.claimNext().orElseThrow();
    jdbc.sql("update scan_run set started_at = now() - interval '3 days' where id = :id").param("id", id).update();
    return id;
  }

  @Test
  void a_run_left_running_is_failed_and_queued_again_once() {
    long orphan = startedLongAgo("abc", ScanRun.TRIGGER_PUSH);
    long live = runs.enqueue(9001, "def", ScanRun.TRIGGER_PUSH);
    runs.claimNext().orElseThrow();

    assertThat(worker.reclaimAbandoned()).isEqualTo(new ScanRunStore.Reclaimed(1, 1));

    ScanRun reclaimed = runs.find(orphan).orElseThrow();
    assertThat(reclaimed.status()).isEqualTo(ScanRun.FAILED);
    assertThat(reclaimed.error()).startsWith(ScanRunStore.ABANDONED).endsWith("it was queued again");
    assertThat(runs.find(live).orElseThrow().status()).as("a run inside its time is left alone").isEqualTo(ScanRun.RUNNING);
    ScanRun retry = runs.forRepo(9001).stream().filter(r -> r.status().equals(ScanRun.QUEUED)).findFirst().orElseThrow();
    assertThat(retry.sha()).isEqualTo("abc");
    assertThat(retry.trigger()).isEqualTo(ScanRun.TRIGGER_PUSH);

    // The retry is abandoned too: it is failed, and not queued a third time.
    runs.claimNext().orElseThrow();
    jdbc.sql("update scan_run set started_at = now() - interval '3 days' where id = :id").param("id", retry.id()).update();
    assertThat(worker.reclaimAbandoned()).isEqualTo(new ScanRunStore.Reclaimed(1, 0));
    assertThat(runs.find(retry.id()).orElseThrow().error()).endsWith("it was already a retry, so it was not queued again");
    assertThat(runs.forRepo(9001)).noneMatch(r -> r.status().equals(ScanRun.QUEUED));
    assertThat(worker.reclaimAbandoned()).isEqualTo(new ScanRunStore.Reclaimed(0, 0));
  }

  @Test
  void a_run_left_running_is_not_queued_again_when_its_repository_has_one_queued() {
    long orphan = startedLongAgo(null, ScanRun.TRIGGER_INSTALL);
    runs.enqueue(9001, null, ScanRun.TRIGGER_MANUAL);

    assertThat(worker.reclaimAbandoned()).isEqualTo(new ScanRunStore.Reclaimed(1, 0));
    assertThat(runs.find(orphan).orElseThrow().error()).endsWith("another scan of this repository was already queued");
    assertThat(runs.forRepo(9001).stream().filter(r -> r.status().equals(ScanRun.QUEUED)).count()).isEqualTo(1);
  }
}
