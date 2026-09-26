package dev.docswatcher.app.scan;

import dev.docswatcher.app.config.AppProperties;
import dev.docswatcher.app.config.ScanIsolationProperties;
import dev.docswatcher.app.config.ScanLimitsProperties;
import dev.docswatcher.app.store.ScanRun;
import dev.docswatcher.app.store.ScanRunStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** N virtual threads polling the scan_run table. No queue service; the table is the queue. */
@Component
public class ScanWorker implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(ScanWorker.class);

  /** How often runs left running are looked for, besides once at startup. */
  static final Duration RECLAIM_EVERY = Duration.ofMinutes(5);

  private final AppProperties.Worker config;
  private final ScanRunStore runs;
  private final ScanRunner runner;
  private final Duration reclaimAfter;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final List<Thread> threads = new ArrayList<>();

  public ScanWorker(AppProperties properties, ScanRunStore runs, ScanRunner runner, ScanLimitsProperties limits,
      ScanIsolationProperties isolation) {
    this.config = properties.worker();
    this.runs = runs;
    this.runner = runner;
    this.reclaimAfter = isolation.reclaimAfter(limits.limits(), Duration.ofSeconds(config.cloneTimeoutSeconds()));
  }

  @Override
  public void start() {
    if (!config.enabled() || !running.compareAndSet(false, true)) {
      return;
    }
    for (int i = 0; i < Math.max(1, config.threads()); i++) {
      threads.add(Thread.ofVirtual().name("scan-worker-" + i).start(this::loop));
    }
    threads.add(Thread.ofVirtual().name("scan-reclaim").start(this::reclaimLoop));
    log.info("Scan worker started with {} virtual threads; runs still running after {} min are reclaimed",
        threads.size() - 1, reclaimAfter.toMinutes());
  }

  /**
   * Fails, and queues again once, the runs left running by a process that stopped: a restart
   * mid-scan, or a crash that took the whole app down. Without this they would stay running forever,
   * since claimNext only takes queued runs.
   */
  public ScanRunStore.Reclaimed reclaimAbandoned() {
    ScanRunStore.Reclaimed r = runs.reclaimAbandoned(reclaimAfter);
    if (r.failed() > 0) {
      log.warn("Reclaimed {} scan runs left running for over {} min; {} queued again", r.failed(), reclaimAfter.toMinutes(), r.requeued());
    }
    return r;
  }

  private void reclaimLoop() {
    while (running.get()) {
      try {
        reclaimAbandoned();
      } catch (Exception e) {
        log.warn("Reclaiming abandoned scan runs failed: {}", e.toString());
      }
      try {
        Thread.sleep(RECLAIM_EVERY);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private void loop() {
    while (running.get()) {
      Optional<ScanRun> next;
      try {
        next = runs.claimNext();
      } catch (Exception e) {
        log.warn("claimNext failed: {}", e.toString());
        next = Optional.empty();
      }
      if (next.isPresent()) {
        try {
          runner.run(next.get());
        } catch (Throwable t) {
          // The loop was guarded against a failing claim but not against a failing run, and
          // ScanRunner catches Exception rather than Throwable. An Error from the engine walking
          // an attacker-chosen repository therefore ended this virtual thread for the life of the
          // process, while running stayed true and isRunning() kept reporting healthy.
          log.error("scan run {} died; worker continues", next.get().id(), t);
        }
      } else {
        try {
          Thread.sleep(config.pollMs());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  /** Runs everything queued right now on the calling thread. Used by tests. */
  public int drain() {
    int n = 0;
    Optional<ScanRun> next;
    while ((next = runs.claimNext()).isPresent()) {
      runner.run(next.get());
      n++;
    }
    return n;
  }

  @Override
  public void stop() {
    running.set(false);
    threads.forEach(Thread::interrupt);
    threads.clear();
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }
}
