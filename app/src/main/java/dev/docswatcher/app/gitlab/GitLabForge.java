package dev.docswatcher.app.gitlab;

import dev.docswatcher.app.config.AppProperties;
import dev.docswatcher.app.forge.Forge;
import dev.docswatcher.app.github.GitHubClient;
import dev.docswatcher.app.scan.ScanRunner;
import dev.docswatcher.app.store.Repo;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * A GitLab project, as a {@link Forge}: cloned and answered with the access token of the
 * connection that holds it (docs/adr/0012-gitlab.md).
 *
 * <p>The verdict is a commit status named DocsWatcher, which GitLab shows on the commit and on
 * any merge request whose head it is. GitLab statuses have no neutral, so GitHub's neutral (open
 * findings that are not breaking, or an incomplete scan) is a success whose description says
 * what is open, as a neutral check run does not block a GitHub merge either.
 */
@Component
public class GitLabForge implements Forge {

  /** GitLab refuses a longer status description. */
  static final int MAX_DESCRIPTION = 255;

  private final GitLabApi api;
  private final GitLabStore store;
  private final TokenCipher cipher;
  private final String dashboard;

  public GitLabForge(GitLabApi api, GitLabStore store, TokenCipher cipher, AppProperties properties) {
    this.api = api;
    this.store = store;
    this.cipher = cipher;
    String origin = properties.web().origin();
    this.dashboard = origin == null || origin.isBlank() ? null : (origin.endsWith("/") ? origin : origin + "/") + "#/app";
  }

  @Override
  public String host() {
    return Repo.GITLAB;
  }

  /** The decrypted access token of the connection holding this project, for one call. */
  String token(Repo repo) {
    GitLabStore.Connection c = store.find(repo.installationId())
        .orElseThrow(() -> new IllegalStateException("GitLab connection " + repo.installationId() + " is gone"));
    return cipher.decrypt(c.tokenCiphertext(), c.id());
  }

  @Override
  public GitHubClient.CloneSource cloneSource(Repo repo) {
    // Any non-blank user name works with an access token over HTTPS.
    return new GitHubClient.CloneSource(api.cloneUri(repo.fullName()), "docswatcher", token(repo));
  }

  @Override
  public void reportScan(Repo repo, String sha, String conclusion, String title, String summary) {
    api.commitStatus(token(repo), store.projectId(repo.id()), sha, state(conclusion), ScanRunner.CHECK_NAME, truncate(title), dashboard);
  }

  static String state(String conclusion) {
    return "failure".equals(conclusion) ? "failed" : "success";
  }

  static String truncate(String description) {
    return description.length() <= MAX_DESCRIPTION ? description : description.substring(0, MAX_DESCRIPTION - 1) + "…";
  }

  @Override
  public int openIssue(Repo repo, String title, String body, List<String> labels) {
    return api.createIssue(token(repo), store.projectId(repo.id()), title, body, labels);
  }

  @Override
  public void closeIssue(Repo repo, int number, String comment) {
    api.closeIssue(token(repo), store.projectId(repo.id()), number, comment);
  }

  @Override
  public String blobBase(Repo repo, String sha) {
    return api.baseUrl() + "/" + repo.fullName() + "/-/blob/" + sha + "/";
  }

  /** No fix dispatch on GitLab yet: the issue does not offer one. */
  @Override
  public String fixLabel() {
    return null;
  }
}
