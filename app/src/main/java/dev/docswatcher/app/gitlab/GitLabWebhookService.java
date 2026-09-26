package dev.docswatcher.app.gitlab;

import dev.docswatcher.app.config.AppProperties;
import dev.docswatcher.app.scan.ScanRunner;
import dev.docswatcher.app.store.FindingStore;
import dev.docswatcher.app.store.Repo;
import dev.docswatcher.app.store.RepoStore;
import dev.docswatcher.app.store.ScanRun;
import dev.docswatcher.app.store.ScanRunStore;
import dev.docswatcher.app.store.StoredFinding;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * GitLab's push and issue hooks, turned into queued scans and finding states: the GitLab half of
 * what {@code WebhookService} does for the GitHub App (docs/adr/0011-gitlab.md).
 *
 * <p>The webhook token says which connection sent the event. That connection's namespace then
 * bounds what the event may touch: a project outside it is ignored, however the payload names it.
 */
@Service
public class GitLabWebhookService {

  private static final Logger log = LoggerFactory.getLogger(GitLabWebhookService.class);

  private final AppProperties.GitHub labels;
  private final GitLabStore store;
  private final GitLabApi api;
  private final TokenCipher cipher;
  private final RepoStore repos;
  private final ScanRunStore runs;
  private final FindingStore findings;

  public GitLabWebhookService(AppProperties properties, GitLabStore store, GitLabApi api, TokenCipher cipher, RepoStore repos,
      ScanRunStore runs, FindingStore findings) {
    this.labels = properties.github();
    this.store = store;
    this.api = api;
    this.cipher = cipher;
    this.repos = repos;
    this.runs = runs;
    this.findings = findings;
  }

  /**
   * The connection whose webhook token this is. Only the token's SHA-256 is stored; the lookup is
   * by that hash, and the stored hash is compared again in constant time, so neither the query
   * nor the comparison times anything an attacker chose byte by byte.
   */
  public Optional<GitLabStore.Connection> authenticate(String token) {
    if (token == null || token.isBlank() || token.length() > 256) {
      return Optional.empty();
    }
    byte[] presented = sha256(token);
    return store.findByWebhookTokenHash(presented)
        .filter(c -> MessageDigest.isEqual(store.webhookTokenHash(c.id()), presented));
  }

  public boolean firstDelivery(String deliveryId) {
    return runs.recordDelivery("gitlab:" + deliveryId);
  }

  public void handle(GitLabStore.Connection connection, JsonNode payload) {
    switch (payload.path("object_kind").asString()) {
      case "push" -> push(connection, payload);
      case "issue" -> issue(connection, payload);
      default -> log.debug("Ignoring GitLab event {}", payload.path("object_kind").asString());
    }
  }

  /**
   * The stored project, when the sending connection may speak for it: it holds the project, or the
   * project's stored path lies in its namespace. The payload's own path is never the test, since
   * whoever holds one group's webhook token can write any path into a payload.
   */
  private Optional<Repo> heldWithin(GitLabStore.Connection connection, long projectId) {
    return store.findProject(projectId).filter(r -> r.installationId() == connection.id() || connection.covers(projectId, r.fullName()));
  }

  private void push(GitLabStore.Connection connection, JsonNode payload) {
    JsonNode project = payload.path("project");
    long projectId = payload.path("project_id").asLong(project.path("id").asLong());
    String path = project.path("path_with_namespace").asString();
    Optional<Repo> held = heldWithin(connection, projectId);
    if (held.isEmpty()) {
      if (store.findProject(projectId).isEmpty() && connection.covers(projectId, path)) {
        adopt(connection, projectId);
      } else {
        log.warn("Ignoring a push for {} sent with the webhook token of {}", path, connection.namespacePath());
      }
      return;
    }
    Repo repo = held.get();
    String defaultBranch = project.path("default_branch").asString(repo.defaultBranch());
    if (!path.equals(repo.fullName()) || !defaultBranch.equals(repo.defaultBranch())) {
      repo = refresh(repo, projectId);
    }
    if (!payload.path("ref").asString().equals("refs/heads/" + repo.defaultBranch())) {
      return;
    }
    String after = payload.path("after").asString();
    if (after.isBlank() || after.matches("0+")) {
      return;
    }
    runs.enqueue(repo.id(), after, ScanRun.TRIGGER_PUSH);
    // The group's shared API records changed: every other project of the group hears about it now.
    if (ScanRunner.isOrgRecordsRepo(repo)) {
      for (Repo other : repos.forInstallation(repo.installationId())) {
        if (other.id() != repo.id() && other.owner().equalsIgnoreCase(repo.owner())) {
          runs.enqueue(other.id(), null, ScanRun.TRIGGER_ORG_RECORDS);
        }
      }
    }
  }

  /**
   * A push from a project the group gained after it was connected. GitLab is asked about the
   * project with the connection's own token, so what is stored is what GitLab says, not what the
   * payload claims, and the project is scanned whole.
   */
  private void adopt(GitLabStore.Connection connection, long projectId) {
    String token = cipher.decrypt(connection.tokenCiphertext(), connection.id());
    Optional<GitLabApi.Project> project = api.project(token, projectId);
    if (project.isEmpty() || project.get().defaultBranch() == null || !connection.covers(project.get().id(), project.get().pathWithNamespace())) {
      log.info("Not adopting GitLab project {}: not reachable, empty, or outside {}", projectId, connection.namespacePath());
      return;
    }
    store.addProject(connection.id(), project.get().id(), project.get().pathWithNamespace(), project.get().defaultBranch())
        .ifPresent(repoId -> runs.enqueue(repoId, null, ScanRun.TRIGGER_INSTALL));
  }

  /**
   * The payload says the project was renamed or moved, or has another default branch. GitLab is
   * asked, with the holding connection's token, and its answer is stored when it still lies in
   * that connection's namespace.
   */
  private Repo refresh(Repo repo, long projectId) {
    try {
      GitLabStore.Connection holder = store.find(repo.installationId()).orElseThrow();
      Optional<GitLabApi.Project> now = api.project(cipher.decrypt(holder.tokenCiphertext(), holder.id()), projectId);
      if (now.isPresent() && now.get().defaultBranch() != null && holder.covers(projectId, now.get().pathWithNamespace())) {
        store.updateProject(repo.id(), now.get().pathWithNamespace(), now.get().defaultBranch());
        return repos.find(repo.id()).orElse(repo);
      }
    } catch (RuntimeException e) {
      log.warn("Could not refresh GitLab project {}: {}", projectId, e.getMessage());
    }
    return repo;
  }

  private void issue(GitLabStore.Connection connection, JsonNode payload) {
    long projectId = payload.path("project").path("id").asLong();
    Optional<Repo> repo = heldWithin(connection, projectId);
    if (repo.isEmpty()) {
      return;
    }
    JsonNode attributes = payload.path("object_attributes");
    int iid = attributes.path("iid").asInt();
    Optional<StoredFinding> finding = findings.findByIssue(repo.get().id(), iid);
    if (finding.isEmpty()) {
      return;
    }
    GitLabStore.Connection holder = store.find(repo.get().installationId()).orElse(connection);
    long actor = payload.path("user").path("id").asLong();
    // DocsWatcher's own calls come back as hooks sent by the access token's bot user.
    if (actor == holder.tokenUserId()) {
      return;
    }
    String action = attributes.path("action").asString();
    Set<String> added = addedLabels(payload);
    boolean relevant = "close".equals(action) || "reopen".equals(action)
        || added.contains(labels.snoozeLabel()) || added.contains(labels.notInProdLabel()) || added.contains(labels.notAffectedLabel());
    if (!relevant) {
      return;
    }
    if (!canCommand(holder, projectId, actor)) {
      log.warn("Ignoring {} on {}#{} from GitLab user {}: below Developer", action, repo.get().fullName(), iid, actor);
      return;
    }
    StoredFinding f = finding.get();
    long repoId = repo.get().id();
    if ("close".equals(action)) {
      if (!"fixed".equals(f.status()) && !"not_affected".equals(f.status())) {
        findings.setStatus(repoId, f.contractId(), f.changeId(), "not_affected", null);
      }
    } else if ("reopen".equals(action)) {
      if ("not_affected".equals(f.status())) {
        findings.setStatus(repoId, f.contractId(), f.changeId(), "open", null);
      }
    } else if (added.contains(labels.snoozeLabel())) {
      findings.setStatus(repoId, f.contractId(), f.changeId(), "snoozed", LocalDate.now().plusDays(30));
    } else if (added.contains(labels.notInProdLabel())) {
      findings.setStatus(repoId, f.contractId(), f.changeId(), "not_in_prod", null);
    } else {
      findings.setStatus(repoId, f.contractId(), f.changeId(), "not_affected", null);
    }
  }

  /** Labels in the update's current set that were not in its previous one. */
  static Set<String> addedLabels(JsonNode payload) {
    JsonNode change = payload.path("changes").path("labels");
    Set<String> before = new HashSet<>();
    for (JsonNode l : change.path("previous")) {
      before.add(l.path("title").asString());
    }
    Set<String> added = new HashSet<>();
    for (JsonNode l : change.path("current")) {
      String title = l.path("title").asString();
      if (!before.contains(title)) {
        added.add(title);
      }
    }
    return added;
  }

  /**
   * Developer or above, the GitLab counterpart of GitHub's write, asked of GitLab with the
   * connection's token. Fails closed: a role we cannot establish is no role.
   */
  private boolean canCommand(GitLabStore.Connection holder, long projectId, long actor) {
    if (actor <= 0) {
      return false;
    }
    try {
      String token = cipher.decrypt(holder.tokenCiphertext(), holder.id());
      return api.accessLevel(token, "project", projectId, actor) >= GitLabApi.DEVELOPER;
    } catch (RuntimeException e) {
      log.warn("Could not establish GitLab user {}'s role on project {}: {}", actor, projectId, e.getMessage());
      return false;
    }
  }

  static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
