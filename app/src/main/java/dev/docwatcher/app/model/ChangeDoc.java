package dev.docwatcher.app.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;

/** The slice of a knowledge base change record the app needs. Unknown fields are ignored. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChangeDoc(
    String id,
    String provider,
    String kind,
    String severity,
    String title,
    String summary,
    LocalDate announced,
    LocalDate effective,
    Migration migration,
    String status) {

  public record Migration(String replacement, String guide, String effort, String notes) {}
}
