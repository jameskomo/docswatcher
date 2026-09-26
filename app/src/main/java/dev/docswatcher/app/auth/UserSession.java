package dev.docswatcher.app.auth;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A signed-in person, and a snapshot of what GitHub or GitLab said they may see when they signed in.
 *
 * <p>The snapshot is the authorisation. It is taken once, at sign-in, from the person's own
 * token, and it expires with the session; the token itself is not kept (ADR 0008, ADR 0011).
 *
 * @param githubId the person's user id at {@code provider}; the name predates GitLab
 * @param provider "github" or "gitlab", whichever they signed in with
 */
public record UserSession(
    long githubId,
    String login,
    String name,
    String avatarUrl,
    List<OrgAccess> orgs,
    OffsetDateTime createdAt,
    OffsetDateTime expiresAt,
    String provider) {

  public static final String GITLAB = "gitlab";

  public UserSession {
    if (provider == null) {
      provider = "github";
    }
  }

  /** A session signed in with GitHub. */
  public UserSession(long githubId, String login, String name, String avatarUrl, List<OrgAccess> orgs, OffsetDateTime createdAt, OffsetDateTime expiresAt) {
    this(githubId, login, name, avatarUrl, orgs, createdAt, expiresAt, "github");
  }

  public boolean gitLab() {
    return GITLAB.equals(provider);
  }

  /** One installation of the App the person can reach, and the stored repositories in it they can see. */
  public record OrgAccess(long installationId, String login, List<RepoAccess> repos) {}

  /** A repository and the person's own permission on it: admin, maintain, write, triage or read. */
  public record RepoAccess(long id, String fullName, String permission) {}
}
