package dev.docswatcher.app.store;

/**
 * A repository DocsWatcher scans: a GitHub repository, or a GitLab project.
 *
 * <p>{@code fullName} is {@code owner/name} on GitHub and the project's full path on GitLab, where
 * the owner can itself hold slashes ({@code group/subgroup/project}). The owner is therefore
 * everything before the last slash, which on GitHub is the same as before the first.
 */
public record Repo(long id, long installationId, String fullName, String defaultBranch, String lastScannedSha, boolean production, String provider) {

  public static final String GITHUB = "github";
  public static final String GITLAB = "gitlab";

  public Repo {
    if (provider == null) {
      provider = GITHUB;
    }
  }

  public String owner() {
    return fullName.substring(0, fullName.lastIndexOf('/'));
  }

  public String name() {
    return fullName.substring(fullName.lastIndexOf('/') + 1);
  }

  public boolean isGitLab() {
    return GITLAB.equals(provider);
  }
}
