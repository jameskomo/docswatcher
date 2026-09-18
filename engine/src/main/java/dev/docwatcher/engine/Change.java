package dev.docwatcher.engine;

import java.util.List;

/** A deprecation event from the knowledge base. Field names mirror the YAML. */
public record Change(
    String id,
    String provider,
    String kind,
    String severity,
    String title,
    String summary,
    List<Affect> affects,
    String announced,
    String effective,
    List<Source> sources,
    Migration migration,
    String status,
    String file) {

  public record Affect(String kind, String match) {}

  public record Source(String kind, String url, String observed, String note) {}

  public record Migration(String replacement, String guide, String effort, String notes) {}

  public boolean producesFindings() {
    return "active".equals(status) || "expired".equals(status);
  }
}
