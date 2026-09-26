package dev.docswatcher.app.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One repository's runtime picture, as the dashboard draws it: what production called that is
 * going away, and what the code references that production has not been seen calling.
 *
 * @param lastReportAt when telemetry last arrived for this repository, from either an ingest
 *     token or a recorded call. Null means nothing has ever reported, and then the absence of
 *     observations says nothing about production.
 * @param deprecated calls that corroborate a finding or carried a Deprecation or Sunset header,
 *     most called first.
 * @param alsoObserved calls to endpoints the scanner tracks that nothing has deprecated.
 * @param notObserved open findings on endpoints production has not been seen calling.
 * @param notObservable open findings on something an HTTP span cannot show, such as a model name
 *     inside a request body. Counted, not listed as unseen, because "not seen" would be untrue.
 */
public record RuntimeSummary(
    long repoId,
    String repoFullName,
    OffsetDateTime lastReportAt,
    int activeTokens,
    List<Call> deprecated,
    List<Call> alsoObserved,
    List<FindingRef> notObserved,
    int notObservable) {

  /** One endpoint as production called it, summed over every day observed. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Call(
      String host,
      String method,
      String path,
      String provider,
      String contractId,
      long totalCalls,
      long callsPerDay,
      int daysObserved,
      OffsetDateTime firstSeen,
      OffsetDateTime lastSeen,
      String deprecationHeader,
      String sunsetHeader,
      List<FindingRef> findings) {}

  /** Enough of a finding to name it and find it in the findings list. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record FindingRef(String contract, String change, String changeTitle, String severity, LocalDate effective, String status) {}
}
