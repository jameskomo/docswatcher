package dev.docswatcher.app.forge;

import dev.docswatcher.app.github.GitHubClient;
import dev.docswatcher.app.store.Repo;
import java.util.List;

/**
 * Where a repository lives, as far as a scan is concerned: how to clone it, and how to say what
 * the scan found. GitHub answers with a check run and issues, GitLab with a commit status and
 * issues. The scan itself, the reconciliation and the wording are shared (docs/adr/0012-gitlab.md).
 */
public interface Forge {

  /** The host name a scan's inventory records: "github" or "gitlab". */
  String host();

  /** Credentials to clone this repository with, fresh enough to outlive the clone. */
  GitHubClient.CloneSource cloneSource(Repo repo);

  /**
   * The scan's verdict on the scanned commit. {@code conclusion} is GitHub's check-run vocabulary
   * (success, neutral, failure), which each forge maps onto its own.
   */
  void reportScan(Repo repo, String sha, String conclusion, String title, String summary);

  /** Opens an issue and returns its number (GitHub) or project-scoped iid (GitLab). */
  int openIssue(Repo repo, String title, String body, List<String> labels);

  void closeIssue(Repo repo, int number, String comment);

  /** The web address of a file at a commit, to which a line anchor is appended. */
  String blobBase(Repo repo, String sha);

  /**
   * The label that asks for a fix pull request, or null where this forge has no fix dispatch; the
   * issue then does not offer it.
   */
  String fixLabel();
}
