package dev.docswatcher.engine;

import java.time.Duration;

/**
 * Whole-scan limits, on top of the per-file ones (1 MB read, 10 s of parsing and querying). Once
 * one is reached no further file is read, or searched by the call-site layer, and the inventory
 * says so in {@code stats.incomplete}. A scan is never cut short silently.
 *
 * @param maxFiles files read, after exclusions
 * @param maxBytes bytes read in total; every file read is held in memory until the scan ends
 * @param maxDuration wall-clock time for reading and all three layers. A file already being parsed
 *     finishes within its own 10 s budget, so a scan can overrun this by that much
 */
public record ScanLimits(int maxFiles, long maxBytes, Duration maxDuration) {

  /**
   * Generous for one service, conservative for a machine: 20,000 files, 200 MB, 10 minutes. A
   * large monorepo can reach them; `--exclude` or the CLI's limit options raise or narrow them.
   */
  public static final ScanLimits DEFAULT = new ScanLimits(20_000, 200L * 1024 * 1024, Duration.ofMinutes(10));

  public ScanLimits {
    if (maxFiles < 1 || maxBytes < 1 || maxDuration == null || maxDuration.isNegative() || maxDuration.isZero()) {
      throw new IllegalArgumentException("Scan limits must be positive: " + maxFiles + " files, " + maxBytes + " bytes, " + maxDuration);
    }
  }

  /** The names used in {@code stats.incomplete.limit}. */
  public static final String MAX_FILES = "maxFiles";
  public static final String MAX_BYTES = "maxBytes";
  public static final String MAX_DURATION = "maxDuration";
}
