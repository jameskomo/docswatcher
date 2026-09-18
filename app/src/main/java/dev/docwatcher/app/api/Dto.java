package dev.docwatcher.app.api;

import dev.docwatcher.app.model.FindingDoc;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Response shapes for the dashboard. Small records, no behaviour. */
public final class Dto {

  private Dto() {}

  public record Overview(String login, int repos, long contracts, Map<String, Long> findingsBySeverity, LocalDate nearestEffective, String knowledgeVersion) {}

  public record RepoSummary(long id, String fullName, String defaultBranch, String lastScannedSha, boolean production, long openFindings) {}

  public record RepoFinding(long repoId, String repoFullName, FindingDoc finding, String changeTitle) {}

  public record HorizonMonth(String month, List<RepoFinding> findings) {}

  public record MapNode(String provider, long contracts, long evidence, String worstSeverity, long openFindings) {}

  public record BlastRadius(String changeId, String title, LocalDate effective, int repos, List<RepoFinding> findings) {}

  public record SnoozeRequest(int days) {}

  public record Ack(String status, Object detail) {}
}
