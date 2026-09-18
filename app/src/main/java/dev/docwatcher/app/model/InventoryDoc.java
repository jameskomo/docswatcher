package dev.docwatcher.app.model;

import java.util.List;

public record InventoryDoc(
    String schemaVersion,
    RepoRefDoc repo,
    String scannedAt,
    EngineInfo engine,
    Stats stats,
    List<ContractDoc> contracts) {

  public record EngineInfo(String name, String version, String knowledgeVersion) {}

  public record Stats(int filesScanned, int filesSkipped, long durationMs, List<String> layers) {}
}
