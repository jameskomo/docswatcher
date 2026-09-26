package dev.docswatcher.app.gitlab;

import dev.docswatcher.app.auth.SessionStore;
import dev.docswatcher.app.auth.Viewer;
import dev.docswatcher.app.config.AppProperties;
import dev.docswatcher.app.store.ScanRun;
import dev.docswatcher.app.store.ScanRunStore;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Connecting a GitLab group or project with an access token a maintainer created for it
 * (docs/adr/0011-gitlab.md).
 *
 * <p>The token is its own proof of authority. GitLab is asked whose it is, whether it carries the
 * api scope, and what role its user holds on the namespace; below Maintainer, the connection is
 * refused. Whoever holds such a token can already open issues and set statuses in that namespace,
 * which is all DocsWatcher will do with it, so connecting spends no authority the caller lacks.
 */
@Service
public class GitLabConnectionService {

  private static final Logger log = LoggerFactory.getLogger(GitLabConnectionService.class);

  /** GitLab's path characters, with slashes between segments. */
  private static final Pattern PATH = Pattern.compile("[A-Za-z0-9_.][A-Za-z0-9_.\\-]*(/[A-Za-z0-9_.][A-Za-z0-9_.\\-]*)*");

  /** A refused connection: the status to answer with, and a message safe to show the person. */
  public static class Refused extends RuntimeException {
    private final HttpStatus status;

    Refused(HttpStatus status, String message) {
      super(message);
      this.status = status;
    }

    public HttpStatus status() {
      return status;
    }
  }

  /**
   * A connection as the dashboard shows it. {@code webhookToken} is present once, in the answer to
   * the request that created the connection; DocsWatcher keeps only its hash.
   */
  public record Connected(
      long id,
      String login,
      String kind,
      String namespace,
      int projects,
      LocalDate tokenExpiresAt,
      String connectedBy,
      OffsetDateTime createdAt,
      String webhookUrl,
      String webhookToken) {}

  private final GitLabApi api;
  private final GitLabStore store;
  private final TokenCipher cipher;
  private final ScanRunStore runs;
  private final String webhookUrl;

  public GitLabConnectionService(GitLabApi api, GitLabStore store, TokenCipher cipher, ScanRunStore runs, AppProperties properties) {
    this.api = api;
    this.store = store;
    this.cipher = cipher;
    this.runs = runs;
    String origin = properties.web().origin();
    this.webhookUrl = (origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin) + "/webhooks/gitlab";
  }

  public Connected connect(String namespacePath, String token, String connectedBy) {
    if (!cipher.available()) {
      throw new Refused(HttpStatus.SERVICE_UNAVAILABLE, "GitLab connections are not configured on this deployment.");
    }
    String path = namespacePath == null ? "" : namespacePath.strip();
    while (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    if (path.isEmpty() || path.length() > 255 || !PATH.matcher(path).matches() || ("/" + path + "/").matches(".*/\\.+/.*")) {
      throw new Refused(HttpStatus.BAD_REQUEST, "Name the group or project by its full path, such as acme or acme/platform.");
    }
    String secret = token == null ? "" : token.strip();
    if (secret.isEmpty() || secret.length() > 512) {
      throw new Refused(HttpStatus.BAD_REQUEST, "Paste the group or project access token.");
    }

    GitLabApi.TokenInfo info;
    try {
      info = api.tokenSelf(secret);
    } catch (GitLabApi.GitLabException e) {
      throw new Refused(HttpStatus.BAD_REQUEST, "GitLab did not accept this token" + (e.status() == 401 ? "." : " (" + e.getMessage() + ")."));
    }
    if (!info.active()) {
      throw new Refused(HttpStatus.BAD_REQUEST, "This token is revoked or expired.");
    }
    if (!info.scopes().contains("api")) {
      throw new Refused(HttpStatus.BAD_REQUEST, "The token needs the api scope, to open issues and set commit statuses.");
    }
    String wanted = path;
    GitLabApi.Namespace ns = api.group(secret, wanted)
        .or(() -> api.project(secret, wanted).map(p -> new GitLabApi.Namespace("project", p.id(), p.pathWithNamespace())))
        .orElseThrow(() -> new Refused(HttpStatus.BAD_REQUEST, "The token cannot see a group or project at " + wanted + "."));
    if (api.accessLevel(secret, ns.kind(), ns.id(), info.userId()) < GitLabApi.MAINTAINER) {
      throw new Refused(HttpStatus.FORBIDDEN, "The token's role on " + ns.fullPath() + " is below Maintainer.");
    }

    Optional<GitLabStore.Connection> existing = store.findByNamespace(ns.kind(), ns.id());
    long id;
    String webhookToken = null;
    if (existing.isPresent()) {
      id = existing.get().id();
      store.replaceToken(id, ns.fullPath(), cipher.encrypt(secret, id), info.userId(), info.expiresAt(), connectedBy);
      log.info("GitLab {} {} reconnected by {}: token replaced", ns.kind(), ns.fullPath(), connectedBy);
    } else {
      id = store.nextId();
      webhookToken = SessionStore.randomToken();
      store.create(id, topLevel(ns.fullPath()), ns.kind(), ns.id(), ns.fullPath(), cipher.encrypt(secret, id), info.userId(), info.expiresAt(),
          GitLabWebhookService.sha256(webhookToken), connectedBy);
      log.info("GitLab {} {} connected by {}", ns.kind(), ns.fullPath(), connectedBy);
    }
    sync(id, secret, ns);
    GitLabStore.Connection c = store.find(id).orElseThrow();
    return describe(c, webhookToken);
  }

  /**
   * Adds the namespace's projects that no connection holds yet, each with a first scan. Empty
   * repositories are left for their first push to bring in.
   */
  private void sync(long id, String token, GitLabApi.Namespace ns) {
    List<GitLabApi.Project> projects = "group".equals(ns.kind())
        ? api.groupProjects(token, ns.id())
        : api.project(token, ns.id()).map(List::of).orElse(List.of());
    for (GitLabApi.Project p : projects) {
      if (p.defaultBranch() == null) {
        continue;
      }
      store.addProject(id, p.id(), p.pathWithNamespace(), p.defaultBranch())
          .ifPresent(repoId -> runs.enqueue(repoId, null, ScanRun.TRIGGER_INSTALL));
    }
  }

  /**
   * The owner sees every connection. A member signed in with GitLab sees those of organisations
   * they can see; one signed in with GitHub sees none, even where a GitHub organisation shares a
   * GitLab group's name.
   */
  public List<Connected> list(Viewer viewer) {
    boolean gitLabMember = viewer instanceof Viewer.Member m && m.session().gitLab();
    List<Connected> out = new ArrayList<>();
    for (GitLabStore.Connection c : store.all()) {
      if (viewer.seesEverything() || (gitLabMember && viewer.canSeeOrg(c.login()))) {
        out.add(describe(c, null));
      }
    }
    return out;
  }

  /**
   * Removes a connection and everything scanned through it. The owner may; so may a person signed
   * in with GitLab whom GitLab, asked with the connection's own token, names a Maintainer of the
   * namespace. Anyone else is told it does not exist.
   */
  public boolean disconnect(long id, Viewer viewer) {
    Optional<GitLabStore.Connection> found = store.find(id);
    if (found.isEmpty()) {
      return false;
    }
    GitLabStore.Connection c = found.get();
    if (!viewer.seesEverything()) {
      if (!(viewer instanceof Viewer.Member m) || !m.session().gitLab() || !viewer.canSeeOrg(c.login())) {
        return false;
      }
      int level;
      try {
        level = api.accessLevel(cipher.decrypt(c.tokenCiphertext(), c.id()), c.kind(), c.namespaceId(), m.session().githubId());
      } catch (RuntimeException e) {
        log.warn("Could not establish {}'s role on {}: {}", m.session().login(), c.namespacePath(), e.getMessage());
        level = 0;
      }
      if (level < GitLabApi.MAINTAINER) {
        throw new Refused(HttpStatus.FORBIDDEN, "Disconnecting " + c.namespacePath() + " needs the Maintainer role on it.");
      }
    }
    store.delete(id);
    log.info("GitLab {} {} disconnected", c.kind(), c.namespacePath());
    return true;
  }

  private Connected describe(GitLabStore.Connection c, String webhookToken) {
    return new Connected(c.id(), c.login(), c.kind(), c.namespacePath(), store.countProjects(c.id()), c.tokenExpiresAt(), c.connectedBy(),
        c.createdAt(), webhookUrl, webhookToken);
  }

  /** The dashboard groups a connection under its top-level group, as GitLab itself does. */
  static String topLevel(String fullPath) {
    int slash = fullPath.indexOf('/');
    return slash < 0 ? fullPath : fullPath.substring(0, slash);
  }
}
