package dev.docswatcher.app.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record InventoryDoc(
    String schemaVersion,
    RepoRefDoc repo,
    String scannedAt,
    EngineInfo engine,
    Stats stats,
    List<ContractDoc> contracts) {

  public record EngineInfo(String name, String version, String knowledgeVersion) {}

  /** {@code incomplete} is null for a complete scan; see the engine's Inventory.Stats. */
  public record Stats(int filesScanned, int filesSkipped, long durationMs, List<String> layers,
      @JsonInclude(JsonInclude.Include.NON_NULL) Incomplete incomplete) {

    @JsonCreator
    public Stats {}

    public Stats(int filesScanned, int filesSkipped, long durationMs, List<String> layers) {
      this(filesScanned, filesSkipped, durationMs, layers, null);
    }
  }

  /** The whole-scan limit that stopped a scan (maxFiles, maxBytes, maxDuration), its value, and the files left unscanned. */
  public record Incomplete(String limit, long max, int filesNotScanned) {}
}
