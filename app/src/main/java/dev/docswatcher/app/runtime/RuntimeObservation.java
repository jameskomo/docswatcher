package dev.docswatcher.app.runtime;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record RuntimeObservation(
    long repoId,
    LocalDate observedDate,
    String host,
    String method,
    String path,
    String provider,
    String contractId,
    String deprecationHeader,
    String sunsetHeader,
    long callCount,
    OffsetDateTime firstSeen,
    OffsetDateTime lastSeen) {}
