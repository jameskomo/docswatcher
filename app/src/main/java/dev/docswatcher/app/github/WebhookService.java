package dev.docswatcher.app.github;

import dev.docswatcher.app.config.AppProperties;
import dev.docswatcher.app.scan.FixDispatcher;
import dev.docswatcher.app.store.InstallationStore;
import dev.docswatcher.app.store.Repo;
import dev.docswatcher.app.store.RepoStore;
import dev.docswatcher.app.store.ScanRun;
import dev.docswatcher.app.store.ScanRunStore;
import dev.docswatcher.app.store.FindingStore;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Turns webhook payloads into rows. Nothing here talks to GitHub except through queued work and the fix dispatcher. */
@Service
public class WebhookService {

  private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

  private final AppProperties.GitHub github;
  private final GitHubClient client;
  private final InstallationStore installations;
  private final RepoStore repos;
  private final ScanRunStore runs;
  private final FindingStore findings;
  private final FixDispatcher fixes;

  public WebhookService(AppProperties properties, GitHubClient client, InstallationStore installations, RepoStore repos, ScanRunStore runs, FindingStore findings, FixDispatcher fixes) {
    this.github = properties.github();
    this.client = client;
    this.installations = installations;
    this.repos = repos;
    this.runs = runs;
    this.findings = findings;
    this.fixes = fixes;
  }

  public boolean firstDelivery(String deliveryId) {
    return runs.recordDelivery(deliveryId);
  }

  public void handle(String event, JsonNode payload) {
    switch (event) {
      case "installation" -> installation(payload);
      case "installation_repositories" -> installationRepositories(payload);
      case "push" -> push(payload);
      case "issues" -> issues(payload);
      default -> log.debug("Ignoring webhook event {}", event);
    }
  }

  private void installation(JsonNode payload) {
    String action = payload.path("action").asString();
    long id = payload.path("installation").path("id").asLong();
    String login = payload.path("installation").path("account").path("login").asString();
    // Every installation event can change what a cached token may reach: an uninstall or a
    // suspension ends it, new permissions or an unsuspension make it stale. Mint afresh.
    client.forgetInstallation(id);
    switch (action) {
      case "created", "unsuspend", "new_permissions_accepted" -> {
        installations.upsert(id, login);
        for (GitHubClient.InstallationRepo r : client.listInstallationRepos(id)) {
          addRepo(id, r);
        }
      }
      case "deleted" -> {
        for (Repo r : repos.forInstallation(id)) {
          runs.cancelQueued(r.id());
        }
        installations.delete(id);
      }
      case "suspend" -> installations.suspend(id, true);
      default -> log.debug("Ignoring installation action {}", action);
    }
  }

  private void installationRepositories(JsonNode payload) {
    long id = payload.path("installation").path("id").asLong();
    String login = payload.path("installation").path("account").path("login").asString();
    // A token is scoped to the repositories the installation could reach when it was minted.
    client.forgetInstallation(id);
    installations.upsert(id, login);
    for (JsonNode removed : payload.path("repositories_removed")) {
      repos.delete(removed.path("id").asLong());
    }
    if (!payload.path("repositories_added").isEmpty()) {
      var known = client.listInstallationRepos(id);
      for (JsonNode added : payload.path("repositories_added")) {
        long repoId = added.path("id").asLong();
        known.stream().filter(r -> r.id() == repoId).findFirst().ifPresent(r -> addRepo(id, r));
      }
    }
  }

  private void addRepo(long installationId, GitHubClient.InstallationRepo r) {
    repos.upsert(r.id(), installationId, r.fullName(), r.defaultBranch());
    runs.enqueue(r.id(), null, ScanRun.TRIGGER_INSTALL);
  }

  private void push(JsonNode payload) {
    JsonNode repository = payload.path("repository");
    String defaultBranch = repository.path("default_branch").asString();
    String ref = payload.path("ref").asString();
    if (!ref.equals("refs/heads/" + defaultBranch)) {
      return;
    }
    Optional<Repo> repo = repos.find(repository.path("id").asLong());
    if (repo.isEmpty()) {
      log.info("Push for unknown repo {}", repository.path("full_name").asString());
      return;
    }
    String after = payload.path("after").asString();
    if (after.matches("0+")) {
      return;
    }
    runs.enqueue(repo.get().id(), after, ScanRun.TRIGGER_PUSH);
  }

  private void issues(JsonNode payload) {
    if (!"labeled".equals(payload.path("action").asString())) {
      return;
    }
    String label = payload.path("label").path("name").asString();
    long repoId = payload.path("repository").path("id").asLong();
    int number = payload.path("issue").path("number").asInt();
    String actor = payload.path("sender").path("login").asString();
    Optional<Repo> repo = repos.find(repoId);
    if (repo.isEmpty()) {
      return;
    }
    if (!label.equals(github.fixLabel()) && !label.equals(github.snoozeLabel()) && !label.equals(github.notInProdLabel())) {
      return;
    }
    // Authorize the actor, not the label. The webhook HMAC proves GitHub sent this message; it
    // says nothing about who applied the label. Applying a label is a triage capability on
    // GitHub, while these branches dispatch into the repository with the installation token and
    // rewrite finding state, so the gate has to be the labeller's own permission.
    if (!canCommand(repo.get(), actor)) {
      log.warn("Ignoring label {} on {}#{} from {}: no write permission", label, repo.get().fullName(), number, actor);
      return;
    }
    findings.findByIssue(repoId, number).ifPresent(f -> {
      if (label.equals(github.fixLabel())) {
        fixes.dispatch(repo.get(), f);
      } else if (label.equals(github.snoozeLabel())) {
        findings.setStatus(repoId, f.contractId(), f.changeId(), "snoozed", LocalDate.now().plusDays(30));
      } else if (label.equals(github.notInProdLabel())) {
        findings.setStatus(repoId, f.contractId(), f.changeId(), "not_in_prod", null);
      }
    });
  }

  private static final Set<String> WRITE_OR_ABOVE = Set.of("admin", "maintain", "write");

  /** Fails closed: a permission we cannot establish is not permission. */
  private boolean canCommand(Repo repo, String actor) {
    if (actor == null || actor.isBlank()) {
      return false;
    }
    return WRITE_OR_ABOVE.contains(client.collaboratorPermission(repo.installationId(), repo.fullName(), actor));
  }
}
