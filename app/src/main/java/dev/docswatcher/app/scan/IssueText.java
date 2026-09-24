package dev.docswatcher.app.scan;

import dev.docswatcher.app.model.ChangeDoc;
import dev.docswatcher.app.model.EvidenceDoc;
import dev.docswatcher.app.model.FindingDoc;
import dev.docswatcher.app.store.Repo;
import java.util.List;
import java.util.Optional;

/** Renders the issue and check-run text. Kept separate so the wording is testable and easy to change. */
public final class IssueText {

  private IssueText() {}

  public static String issueTitle(FindingDoc f, Optional<ChangeDoc> change) {
    String what = change.map(ChangeDoc::title).orElse(f.change());
    return "[DocsWatcher] " + what + " affects " + contractKey(f.contract());
  }

  public static String issueBody(Repo repo, String sha, FindingDoc f, Optional<ChangeDoc> change, String fixLabel, String snoozeLabel, String notInProdLabel, String notAffectedLabel) {
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
    int shown = 0;
    for (EvidenceDoc e : f.evidence()) {
      if (shown++ == MAX_EVIDENCE_ROWS) {
        b.append("- ...and ").append(f.evidence().size() - MAX_EVIDENCE_ROWS).append(" more\n");
        break;
      }
      b.append("- [").append(mdText(e.path())).append(":").append(e.line()).append("](https://github.com/")
          .append(repo.fullName()).append("/blob/").append(sha).append("/").append(urlPath(e.path())).append("#L").append(e.line())
          .append(") ").append(codeSpan(e.snippet())).append("\n");
    }
    b.append("\n**Actions**: add the label `").append(fixLabel).append("` to open a fix PR, `")
        .append(snoozeLabel).append("` to snooze, `").append(notInProdLabel).append("` if this code does not run in production, or `")
        .append(notAffectedLabel).append("` if the change does not affect it (closing this issue says the same; reopening it takes that back).\n");
    b.append("\n<!-- docswatcher-finding: ").append(f.id()).append(" -->\n");
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
      b.append("- ").append(f.severity()).append(" | ").append(mdText(f.change())).append(" | ").append(mdText(contractKey(f.contract())));
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

  /** No more than this many evidence rows are rendered, so one repository cannot author a whole issue body. */
  private static final int MAX_EVIDENCE_ROWS = 20;

  /**
   * Renders repository-derived text as a code span it cannot escape from.
   *
   * <p>The snippet is the verbatim matched source line of a repository DocsWatcher does not
   * control, and it used to be placed between two single backticks. Under the CommonMark rule
   * GitHub applies to issue bodies, one backtick inside the snippet closes that span and the rest
   * of the attacker-chosen line becomes live markdown in a document published under this App's
   * identity, next to the Actions block that tells a maintainer which label to apply. The fence is
   * therefore always longer than the longest backtick run in the value, and control characters go
   * first so nothing can break the list item either.
   */
  static String codeSpan(String raw) {
    String t = raw == null ? "" : raw.replaceAll("\\p{Cntrl}", " ");
    int longest = 0;
    int run = 0;
    for (int i = 0; i < t.length(); i++) {
      run = t.charAt(i) == '`' ? run + 1 : 0;
      longest = Math.max(longest, run);
    }
    String fence = "`".repeat(longest + 1);
    String pad = t.startsWith("`") || t.endsWith("`") || t.isBlank() ? " " : "";
    return fence + pad + t + pad + fence;
  }

  /** Escapes the markdown punctuation that can break out of link text or open a construct. */
  static String mdText(String raw) {
    if (raw == null) {
      return "";
    }
    return raw.replaceAll("\\p{Cntrl}", " ").replaceAll("([\\\\`*_\\[\\]()<>#+\\-!|])", "\\\\$1");
  }

  /** Percent-encodes each path segment so a path cannot terminate the blob URL early. */
  static String urlPath(String path) {
    StringBuilder out = new StringBuilder();
    for (String seg : (path == null ? "" : path).split("/", -1)) {
      if (out.length() > 0) {
        out.append('/');
      }
      out.append(java.net.URLEncoder.encode(seg, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"));
    }
    return out.toString();
  }
}
