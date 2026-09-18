package dev.docwatcher.app.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FindingDoc(
    String id,
    String contract,
    String change,
    String severity,
    LocalDate effective,
    Integer daysRemaining,
    List<EvidenceDoc> evidence,
    String status,
    LocalDate snoozedUntil,
    String fixPr) {}
