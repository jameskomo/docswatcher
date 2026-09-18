package dev.docwatcher.app.store;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record StoredFinding(
    long repoId,
    String contractId,
    String changeId,
    String id,
    String severity,
    LocalDate effective,
    String status,
    LocalDate snoozedUntil,
    Integer issueNumber,
    String fixPrUrl,
    OffsetDateTime openedAt,
    OffsetDateTime closedAt) {}
