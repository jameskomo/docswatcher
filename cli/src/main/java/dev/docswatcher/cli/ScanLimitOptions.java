package dev.docswatcher.cli;

import dev.docswatcher.engine.Inventory;
import dev.docswatcher.engine.ScanLimits;
import java.io.PrintStream;
import java.time.Duration;
import picocli.CommandLine.Option;

/** The whole-scan limits, shared by scan and match. Defaults are {@link ScanLimits#DEFAULT}. */
final class ScanLimitOptions {

  @Option(names = "--max-files", paramLabel = "<n>",
      description = "Stop reading after this many files, and report the scan as incomplete. Default: 20000.")
  int maxFiles = ScanLimits.DEFAULT.maxFiles();

  @Option(names = "--max-total-mb", paramLabel = "<n>",
      description = "Stop reading once this many megabytes have been read, and report the scan as incomplete. Default: 200.")
  long maxTotalMb = ScanLimits.DEFAULT.maxBytes() / (1024 * 1024);

  @Option(names = "--max-seconds", paramLabel = "<n>",
      description = "Stop scanning further files after this many seconds, and report the scan as incomplete. Default: 600.")
  long maxSeconds = ScanLimits.DEFAULT.maxDuration().toSeconds();

  ScanLimits limits() {
    return new ScanLimits(maxFiles, maxTotalMb * 1024 * 1024, Duration.ofSeconds(maxSeconds));
  }

  /** Says on stderr that the scan is incomplete, so it is never mistaken for a full one. True when it was. */
  static boolean warnIfIncomplete(Inventory inv, PrintStream err) {
    Inventory.Incomplete incomplete = inv.stats().incomplete();
    if (incomplete == null) return false;
    err.println("docswatcher: warning: " + incomplete.describe()
        + " Options: --exclude, --max-files, --max-total-mb, --max-seconds.");
    return true;
  }
}
