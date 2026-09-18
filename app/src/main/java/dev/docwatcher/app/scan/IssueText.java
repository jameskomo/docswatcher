package dev.docwatcher.app.scan;

import dev.docwatcher.app.model.ChangeDoc;
import dev.docwatcher.app.model.EvidenceDoc;
import dev.docwatcher.app.model.FindingDoc;
import dev.docwatcher.app.store.Repo;
import java.util.List;
import java.util.Optional;

/** Renders the issue and check-run text. Kept separate so the wording is testable and easy to change. */
public final class IssueText {

  private IssueText() {}

  public static String issueTitle(FindingDoc f, Optional<ChangeDoc> change) {
    String what = change.map(ChangeDoc::title).orElse(f.change());
    return "[DocWatcher] " + what + " affects " + contractKey(f.contract());
  }

  public static String issueBody(Repo repo, String sha, FindingDoc f, Optional<ChangeDoc> change, String fixLabel, String snoozeLabel, String notInProdLabel) {
    StringBuilder b = new StringBuilder();
    b.append("**Severity:** ").append(f.severity()).append("\n");
    if (f.effective() != null) {
      b.append("**Effective:** ").append(f.effective()).append(" (").append(f.daysRemaining()).append(" days)\n");
    } else {
      b.append("**Effective:** no date published\n");
    }
    change.ifPresent(c -> {
      b.append("\n").append(c.summary() == null ? "" : c.summary().strip()).append("\n");
      if (c.migration() != null) {
        b.append("\n**Migration**\n");
        if (c.migration().replacement() != null) {
          b.append("- Replacement: `").append(c.migration().replacement()).append("`\n");
        }
        if (c.migration().guide() != null) {
          b.append("- Guide: ").append(c.migration().guide()).append("\n");
        }
        if (c.migration().effort() != null) {
          b.append("- Effort: ").append(c.migration().effort()).append("\n");
        }
        if (c.migration().notes() != null) {
          b.append("\n").append(c.migration().notes().strip()).append("\n");
        }
      }
    });
    b.append("\n**Where**\n");
    for (EvidenceDoc e : f.evidence()) {
      b.append("- [").append(e.path()).append(":").append(e.line()).append("](https://github.com/")
          .append(repo.fullName()).append("/blob/").append(sha).append("/").append(e.path()).append("#L").append(e.line())
          .append(") `").append(e.snippet()).append("`\n");
    }
    b.append("\n**Actions**: add the label `").append(fixLabel).append("` to open a fix PR, `")
        .append(snoozeLabel).append("` to snooze, or `").append(notInProdLabel).append("` if this code does not run in production.\n");
    b.append("\n<!-- docwatcher-finding: ").append(f.id()).append(" -->\n");
    return b.toString();
  }

  public static String checkTitle(int contracts, List<FindingDoc> findings) {
    long breaking = findings.stream().filter(f -> "breaking".equals(f.severity())).count();
    if (findings.isEmpty()) {
      return contracts + " external contracts, nothing pending";
    }
    return findings.size() + " findings, " + breaking + " breaking, across " + contracts + " external contracts";
  }

  public static String checkSummary(List<FindingDoc> findings) {
    if (findings.isEmpty()) {
      return "No known deprecation affects this repository.";
    }
    StringBuilder b = new StringBuilder();
    for (FindingDoc f : findings) {
      b.append("- ").append(f.severity()).append(" | ").append(f.change()).append(" | ").append(contractKey(f.contract()));
      if (f.effective() != null) {
        b.append(" | ").append(f.effective());
      }
      b.append("\n");
    }
    return b.toString();
  }

  public static String checkConclusion(List<FindingDoc> findings, boolean production) {
    if (!production || findings.isEmpty()) {
      return "success";
    }
    return findings.stream().anyMatch(f -> "breaking".equals(f.severity())) ? "failure" : "neutral";
  }

  static String contractKey(String contractId) {
    int i = contractId.indexOf(':');
    int j = contractId.indexOf(':', i + 1);
    return j > 0 ? contractId.substring(j + 1) : contractId;
  }
}
