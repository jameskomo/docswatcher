package dev.docswatcher.app.config;

import dev.docswatcher.engine.ScanLimits;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where scans run, under {@code docswatcher.scan} beside the limits (docs/adr/0010-scans-in-their-own-process.md).
 *
 * @param isolation {@code process} (the default) runs each scan in a process of its own, so a crash
 *     or a runaway scan cannot take the web server with it; {@code in-process} runs it in the web
 *     server's JVM, which saves a process start per scan
 * @param process the scan process's limits; unset values take the defaults below
 * @param reclaimAfterSeconds how long a run may stay {@code running} before it is taken to be
 *     abandoned; unset, the scan timeout plus two clone timeouts plus ten minutes
 */
@ConfigurationProperties(prefix = "docswatcher.scan")
public record ScanIsolationProperties(Isolation isolation, Process process, Long reclaimAfterSeconds) {

  public enum Isolation {
    PROCESS,
    IN_PROCESS
  }

  /** The default maximum heap of a scan process, in MB. */
  public static final int DEFAULT_HEAP_MB = 1024;

  /** Beyond the scan's own time limit: JVM start, knowledge load, one file's parse budget, matching. */
  public static final Duration TIMEOUT_MARGIN = Duration.ofSeconds(60);

  /**
   * @param heapMb the scan process's maximum heap ({@code -Xmx})
   * @param timeoutSeconds wall-clock time before the scan process is killed; unset, the scan's
   *     {@code max-seconds} plus {@link #TIMEOUT_MARGIN}
   * @param jvmOptions added to the scan process's JVM options
   */
  public record Process(Integer heapMb, Long timeoutSeconds, List<String> jvmOptions) {}

  public Isolation isolationOrDefault() {
    return isolation == null ? Isolation.PROCESS : isolation;
  }

  public int heapMb() {
    return process == null || process.heapMb() == null ? DEFAULT_HEAP_MB : process.heapMb();
  }

  public List<String> jvmOptions() {
    return process == null || process.jvmOptions() == null ? List.of() : process.jvmOptions();
  }

  /** How long one scan may take, whichever isolation runs it, before it is given up on. */
  public Duration timeout(ScanLimits limits) {
    return process == null || process.timeoutSeconds() == null
        ? limits.maxDuration().plus(TIMEOUT_MARGIN)
        : Duration.ofSeconds(process.timeoutSeconds());
  }

  /** How long a run may stay running before the worker takes it to be abandoned. */
  public Duration reclaimAfter(ScanLimits limits, Duration cloneTimeout) {
    return reclaimAfterSeconds != null
        ? Duration.ofSeconds(reclaimAfterSeconds)
        : timeout(limits).plus(cloneTimeout.multipliedBy(2)).plus(Duration.ofMinutes(10));
  }
}
