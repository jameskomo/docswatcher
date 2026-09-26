package dev.docswatcher.app.auth;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Who is asking, as far as the API is concerned. Exactly three kinds.
 *
 * <p>The {@link Owner} holds the shared API token: the deployment's operator and its automation.
 * It sees everything, as the token always has. A {@link Member} signed in with GitHub and sees
 * only what GitHub said they could see: repositories they can reach through an installation of
 * this App. An {@link Ingest} caller holds one repository's ingest token and can only post that
 * repository's telemetry. Every API request has one of them, set by {@code ApiTokenFilter}.
 */
public sealed interface Viewer permits Viewer.Owner, Viewer.Member, Viewer.Ingest {

  /** The request attribute the filter stores the viewer under. */
  String ATTRIBUTE = Viewer.class.getName();

  Owner OWNER = new Owner();

  /** Levels at which acting on a repository's findings is allowed; the same bar the label path sets. */
  Set<String> WRITE_OR_ABOVE = Set.of("write", "maintain", "admin");

  boolean canSeeOrg(String login);

  boolean canRead(long repoId);

  boolean canWrite(long repoId);

  /**
   * Whether an organisation-wide setting may be changed: write or above on at least one of the
   * organisation's repositories the viewer can see. The bar a repository action sets, applied to
   * the organisation the repository belongs to.
   */
  boolean canWriteOrg(String login);

  /** True for the owner: no filtering at all, so queries can skip the per-repository check. */
  default boolean seesEverything() {
    return false;
  }

  record Owner() implements Viewer {
    @Override public boolean canSeeOrg(String login) { return true; }
    @Override public boolean canRead(long repoId) { return true; }
    @Override public boolean canWrite(long repoId) { return true; }
    @Override public boolean canWriteOrg(String login) { return true; }
    @Override public boolean seesEverything() { return true; }
  }

  record Member(UserSession session, Map<Long, String> permissions, Set<String> orgs) implements Viewer {

    public static Member of(UserSession session) {
      Map<Long, String> permissions = new HashMap<>();
      Set<String> orgs = new java.util.HashSet<>();
      for (UserSession.OrgAccess org : session.orgs()) {
        orgs.add(org.login().toLowerCase(Locale.ROOT));
        for (UserSession.RepoAccess repo : org.repos()) {
          permissions.put(repo.id(), repo.permission());
        }
      }
      return new Member(session, Map.copyOf(permissions), Set.copyOf(orgs));
    }

    /** GitHub logins are case-insensitive; a URL typed in another case is the same organisation. */
    @Override
    public boolean canSeeOrg(String login) {
      return login != null && orgs.contains(login.toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean canRead(long repoId) {
      String p = permissions.get(repoId);
      return p != null && !"none".equals(p);
    }

    @Override
    public boolean canWrite(long repoId) {
      return WRITE_OR_ABOVE.contains(permissions.get(repoId));
    }

    @Override
    public boolean canWriteOrg(String login) {
      for (UserSession.OrgAccess org : session.orgs()) {
        if (org.login().equalsIgnoreCase(login) && org.repos().stream().anyMatch(r -> WRITE_OR_ABOVE.contains(r.permission()))) {
          return true;
        }
      }
      return false;
    }
  }

  /**
   * A telemetry exporter holding one repository's ingest token (docs/13-runtime-observation.md).
   * It reads nothing and acts on nothing: {@link AccessInterceptor} admits it only to a handler
   * marked {@link IngestAccess}, and the ingest pins every span to {@link #repoId()}.
   */
  record Ingest(long repoId, long tokenId) implements Viewer {
    @Override public boolean canSeeOrg(String login) { return false; }
    @Override public boolean canRead(long id) { return false; }
    @Override public boolean canWrite(long id) { return false; }
    @Override public boolean canWriteOrg(String login) { return false; }
  }
}
