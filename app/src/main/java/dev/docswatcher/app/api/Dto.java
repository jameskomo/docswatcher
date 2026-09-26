package dev.docswatcher.app.api;

import dev.docswatcher.app.model.FindingDoc;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Response shapes for the dashboard. Small records, no behaviour. */
public final class Dto {

  private Dto() {}

  public record Overview(String login, int repos, long contracts, Map<String, Long> findingsBySeverity, LocalDate nearestEffective, String knowledgeVersion) {}

  /** {@code provider} is where the repository lives: "github" or "gitlab". */
  public record RepoSummary(long id, String fullName, String defaultBranch, String lastScannedSha, boolean production, long openFindings, String provider) {}

  /**
   * What telemetry saw for the endpoint behind a finding. Absent when nothing has been
   * observed, which is the normal case: runtime observation is opt in.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Runtime(
      long callsPerDay,
      long totalCalls,
      int daysObserved,
      OffsetDateTime lastSeen,
      String deprecationHeader,
      String sunsetHeader) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record RepoFinding(long repoId, String repoFullName, FindingDoc finding, String changeTitle, Runtime runtime) {

    public RepoFinding(long repoId, String repoFullName, FindingDoc finding, String changeTitle) {
      this(repoId, repoFullName, finding, changeTitle, null);
    }
  }

  public record HorizonMonth(String month, List<RepoFinding> findings) {}

  public record MapNode(String provider, long contracts, long evidence, String worstSeverity, long openFindings) {}

  public record BlastRadius(String changeId, String title, LocalDate effective, int repos, List<RepoFinding> findings) {}

  public record SnoozeRequest(int days) {}

  /**
   * Names a finding in a request body rather than the path. Contract ids carry whatever the key
   * holds, and an endpoint key such as {@code POST /v1/sources} has a slash that no path segment
   * can carry through Tomcat, so the dashboard addresses findings this way. {@code days} is read
   * by snooze only.
   */
  public record FindingRef(String contract, String change, Integer days) {}

  public record Ack(String status, Object detail) {}
}
