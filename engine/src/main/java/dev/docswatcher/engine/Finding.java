package dev.docswatcher.engine;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/** A contract matched against a change record. Derived, never authored. */
@JsonPropertyOrder({"id", "contract", "change", "severity", "effective", "daysRemaining", "evidence", "status", "snoozedUntil", "fixPr"})
public record Finding(
    String id,
    String contract,
    String change,
    String severity,
    String effective,
    Integer daysRemaining,
    List<Evidence> evidence,
    String status,
    String snoozedUntil,
    String fixPr) {

  /** The projection stored in a fixture's expected-findings.json. */
  @JsonPropertyOrder({"contract", "change"})
  public record Pair(String contract, String change) {}

  public Pair pair() {
    return new Pair(contract, change);
  }
}
