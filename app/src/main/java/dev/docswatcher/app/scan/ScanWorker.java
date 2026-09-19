package dev.docswatcher.app.scan;

import dev.docswatcher.app.config.AppProperties;
import dev.docswatcher.app.store.ScanRun;
import dev.docswatcher.app.store.ScanRunStore;
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

  private final AppProperties.Worker config;
  private final ScanRunStore runs;
  private final ScanRunner runner;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final List<Thread> threads = new ArrayList<>();

  public ScanWorker(AppProperties properties, ScanRunStore runs, ScanRunner runner) {
    this.config = properties.worker();
    this.runs = runs;
    this.runner = runner;
  }

  @Override
  public void start() {
    if (!config.enabled() || !running.compareAndSet(false, true)) {
      return;
    }
    for (int i = 0; i < Math.max(1, config.threads()); i++) {
      threads.add(Thread.ofVirtual().name("scan-worker-" + i).start(this::loop));
    }
    log.info("Scan worker started with {} virtual threads", threads.size());
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
        runner.run(next.get());
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
