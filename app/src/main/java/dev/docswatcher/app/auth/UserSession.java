package dev.docswatcher.app.auth;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A signed-in person, and a snapshot of what GitHub said they may see when they signed in.
 *
 * <p>The snapshot is the authorisation. It is taken once, at sign-in, from the person's own
 * token, and it expires with the session; the token itself is not kept (ADR 0008).
 */
public record UserSession(
    long githubId,
    String login,
    String name,
    String avatarUrl,
    List<OrgAccess> orgs,
    OffsetDateTime createdAt,
    OffsetDateTime expiresAt) {

  /** One installation of the App the person can reach, and the stored repositories in it they can see. */
  public record OrgAccess(long installationId, String login, List<RepoAccess> repos) {}

  /** A repository and the person's own permission on it: admin, maintain, write, triage or read. */
  public record RepoAccess(long id, String fullName, String permission) {}
}
