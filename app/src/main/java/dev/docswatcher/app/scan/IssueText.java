package dev.docswatcher.app.scan;

import dev.docswatcher.app.model.ChangeDoc;
import dev.docswatcher.app.model.EvidenceDoc;
import dev.docswatcher.app.model.FindingDoc;
import dev.docswatcher.app.model.InventoryDoc;
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
    return checkTitle(contracts, findings, null);
  }

  /** An incomplete scan says so in the title, where it is seen without opening the check. */
  public static String checkTitle(int contracts, List<FindingDoc> findings, InventoryDoc.Incomplete incomplete) {
    String prefix = incomplete == null ? "" : "Incomplete scan: ";
    long breaking = findings.stream().filter(f -> "breaking".equals(f.severity())).count();
    if (findings.isEmpty()) {
      return prefix + contracts + " external contracts, nothing pending" + (incomplete == null ? "" : " in the files read");
    }
    return prefix + findings.size() + " findings, " + breaking + " breaking, across " + contracts + " external contracts";
  }

  public static String checkSummary(List<FindingDoc> findings) {
    return checkSummary(findings, null);
  }

  public static String checkSummary(List<FindingDoc> findings, InventoryDoc.Incomplete incomplete) {
    StringBuilder b = new StringBuilder();
    if (incomplete != null) {
      b.append("**This scan is incomplete.** ").append(describe(incomplete))
          .append(" Findings from earlier scans stay open, and nothing was closed. The next complete scan settles them.\n\n");
    }
    if (findings.isEmpty()) {
      return b.append("No known deprecation affects ").append(incomplete == null ? "this repository." : "the files that were read.").toString();
    }
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
    return checkConclusion(findings, production, null);
  }

  /** An incomplete scan is never a success: without a breaking finding it is neutral. */
  public static String checkConclusion(List<FindingDoc> findings, boolean production, InventoryDoc.Incomplete incomplete) {
    if (!production) {
      return "success";
    }
    if (findings.stream().anyMatch(f -> "breaking".equals(f.severity()))) {
      return "failure";
    }
    return findings.isEmpty() && incomplete == null ? "success" : "neutral";
  }

  static String describe(InventoryDoc.Incomplete incomplete) {
    String what = switch (incomplete.limit()) {
      case "maxFiles" -> "the " + incomplete.max() + "-file limit";
      case "maxBytes" -> "the " + (incomplete.max() / (1024 * 1024)) + " MB limit";
      case "maxDuration" -> "the " + (incomplete.max() / 1000) + "-second limit";
      default -> "a scan limit";
    };
    int n = incomplete.filesNotScanned();
    return "It stopped at " + what + " with " + n + (n == 1 ? " file" : " files")
        + " not scanned, so anything in them is not reported. Exclude paths that need no scan (.docswatcherignore), or raise `docswatcher.scan` limits.";
  }

  /**
   * The check run's title when the repository's or organisation's own API records could not be used
   * (docs/19-your-own-apis.md). Said first, so it is not mistaken for a clean result.
   */
  public static String checkTitle(int contracts, List<FindingDoc> findings, InventoryDoc.Incomplete incomplete, List<String> ownProblems) {
    String title = checkTitle(contracts, findings, incomplete);
    if (ownProblems.isEmpty()) {
      return title;
    }
    return "Your own API records were not used (" + ownProblems.size() + (ownProblems.size() == 1 ? " error" : " errors") + ") · " + title;
  }

  /** The summary, followed by what is wrong with the own API records and what might be. */
  public static String checkSummary(List<FindingDoc> findings, InventoryDoc.Incomplete incomplete, List<String> ownProblems, List<String> ownWarnings) {
    StringBuilder b = new StringBuilder(checkSummary(findings, incomplete));
    if (!ownProblems.isEmpty()) {
      b.append("\n**Your own API records were not used.** This scan matched the bundled knowledge base only. Fix these, "
          + "or run `docswatcher validate` on the records:\n");
      appendLines(b, ownProblems);
    }
    if (!ownWarnings.isEmpty()) {
      b.append("\n**Warnings about your own API records**\n");
      appendLines(b, ownWarnings);
    }
    return b.toString();
  }

  /** A check that would pass is neutral while the own records are broken: it did not check what they describe. */
  public static String checkConclusion(List<FindingDoc> findings, boolean production, InventoryDoc.Incomplete incomplete, List<String> ownProblems) {
    String conclusion = checkConclusion(findings, production, incomplete);
    return "success".equals(conclusion) && !ownProblems.isEmpty() ? "neutral" : conclusion;
  }

  private static void appendLines(StringBuilder b, List<String> lines) {
    int shown = 0;
    for (String line : lines) {
      if (shown++ == MAX_EVIDENCE_ROWS) {
        b.append("- ...and ").append(lines.size() - MAX_EVIDENCE_ROWS).append(" more\n");
        break;
      }
      b.append("- ").append(codeSpan(line)).append("\n");
    }
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
