package dev.docswatcher.app.config;

import dev.docswatcher.engine.ScanLimits;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Whole-scan limits for the worker, under {@code docswatcher.scan}. Unset values take the engine's
 * defaults ({@link ScanLimits#DEFAULT}). A scan a limit stops is recorded as incomplete: it never
 * closes a finding, and its check run says so.
 */
@ConfigurationProperties(prefix = "docswatcher.scan")
public record ScanLimitsProperties(Integer maxFiles, Long maxTotalMb, Long maxSeconds) {

  public ScanLimits limits() {
    ScanLimits d = ScanLimits.DEFAULT;
    return new ScanLimits(
        maxFiles == null ? d.maxFiles() : maxFiles,
        maxTotalMb == null ? d.maxBytes() : maxTotalMb * 1024 * 1024,
        maxSeconds == null ? d.maxDuration() : Duration.ofSeconds(maxSeconds));
  }
}
