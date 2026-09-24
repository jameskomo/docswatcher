package dev.docswatcher.engine;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/** The scan result. One per repo per commit. */
@JsonPropertyOrder({"schemaVersion", "repo", "scannedAt", "engine", "stats", "contracts"})
public record Inventory(
    String schemaVersion,
    RepoRef repo,
    String scannedAt,
    EngineInfo engine,
    Stats stats,
    List<Contract> contracts) {

  @JsonPropertyOrder({"name", "version", "knowledgeVersion"})
  public record EngineInfo(String name, String version, String knowledgeVersion) {}

  /**
   * {@code incomplete} is absent from a complete scan's JSON, and present when a {@link ScanLimits}
   * limit stopped the scan: the contracts are then those found before the stop, not the repository's.
   */
  @JsonPropertyOrder({"filesScanned", "filesSkipped", "durationMs", "layers", "incomplete"})
  public record Stats(int filesScanned, int filesSkipped, long durationMs, List<String> layers,
      @JsonInclude(JsonInclude.Include.NON_NULL) Incomplete incomplete) {

    @JsonCreator
    public Stats {}

    public Stats(int filesScanned, int filesSkipped, long durationMs, List<String> layers) {
      this(filesScanned, filesSkipped, durationMs, layers, null);
    }
  }

  /**
   * Which limit stopped the scan, its value (files, bytes, or milliseconds), and how many files
   * were not scanned: not read at all or, when time ran out after reading, not reached by the
   * call-site layer.
   */
  @JsonPropertyOrder({"limit", "max", "filesNotScanned"})
  public record Incomplete(String limit, long max, int filesNotScanned) {

    /** One sentence for people: what stopped, and what to do about it. */
    public String describe() {
      String what = switch (limit) {
        case ScanLimits.MAX_FILES -> "the " + max + "-file limit";
        case ScanLimits.MAX_BYTES -> "the " + (max / (1024 * 1024)) + " MB limit";
        case ScanLimits.MAX_DURATION -> "the " + (max / 1000) + "-second limit";
        default -> "the " + limit + " limit";
      };
      return "Scan incomplete: stopped at " + what + ", " + filesNotScanned + (filesNotScanned == 1 ? " file" : " files")
          + " not scanned. Nothing in them is reported. Exclude paths that need no scan, or raise the limit.";
    }
  }
}
