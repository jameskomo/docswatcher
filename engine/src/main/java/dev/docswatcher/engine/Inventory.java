package dev.docswatcher.engine;

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

  @JsonPropertyOrder({"filesScanned", "filesSkipped", "durationMs", "layers"})
  public record Stats(int filesScanned, int filesSkipped, long durationMs, List<String> layers) {}
}
