package dev.docswatcher.app.github;

import dev.docswatcher.app.config.AppProperties;
import dev.docswatcher.app.scan.FixDispatcher;
import dev.docswatcher.app.scan.ScanRunner;
import dev.docswatcher.app.store.InstallationStore;
import dev.docswatcher.app.store.Repo;
import dev.docswatcher.app.store.RepoStore;
import dev.docswatcher.app.store.ScanRun;
import dev.docswatcher.app.store.ScanRunStore;
import dev.docswatcher.app.store.FindingStore;
import dev.docswatcher.app.store.StoredFinding;
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
    // The organisation's shared API records changed: every other repository hears about it now,
    // not at its own next push (docs/19-your-own-apis.md).
    if (ScanRunner.isOrgRecordsRepo(repo.get())) {
      for (Repo other : repos.forInstallation(repo.get().installationId())) {
        if (other.id() != repo.get().id() && other.owner().equalsIgnoreCase(repo.get().owner())) {
          runs.enqueue(other.id(), null, ScanRun.TRIGGER_ORG_RECORDS);
        }
      }
    }
  }

  private void issues(JsonNode payload) {
    switch (payload.path("action").asString()) {
      case "labeled" -> labeled(payload);
      case "closed" -> closedByPerson(payload);
      case "reopened" -> reopenedByPerson(payload);
      default -> log.debug("Ignoring issues action {}", payload.path("action").asString());
    }
  }

  private void labeled(JsonNode payload) {
    String label = payload.path("label").path("name").asString();
    if (!label.equals(github.fixLabel()) && !label.equals(github.snoozeLabel()) && !label.equals(github.notInProdLabel())
        && !label.equals(github.notAffectedLabel())) {
      return;
    }
    // Authorize the actor, not the label. The webhook HMAC proves GitHub sent this message; it
    // says nothing about who applied the label. Applying a label is a triage capability on
    // GitHub, while these branches dispatch into the repository with the installation token and
    // rewrite finding state, so the gate has to be the labeller's own permission.
    commanded(payload, "label " + label).ifPresent(c -> {
      StoredFinding f = c.finding();
      long repoId = c.repo().id();
      if (label.equals(github.fixLabel())) {
        fixes.dispatch(c.repo(), f);
      } else if (label.equals(github.snoozeLabel())) {
        findings.setStatus(repoId, f.contractId(), f.changeId(), "snoozed", LocalDate.now().plusDays(30));
      } else if (label.equals(github.notInProdLabel())) {
        findings.setStatus(repoId, f.contractId(), f.changeId(), "not_in_prod", null);
      } else {
        findings.setStatus(repoId, f.contractId(), f.changeId(), "not_affected", null);
      }
    });
  }

  /**
   * A person closing a finding's issue says the finding does not apply to their code, which is
   * recorded as not_affected so it leaves the open counts and survives rescans. The App closes
   * issues itself when a finding's evidence disappears; that close comes back as a webhook sent
   * by the App's bot account, after the finding was already marked fixed, and is ignored on both
   * counts.
   */
  private void closedByPerson(JsonNode payload) {
    if (fromBot(payload)) {
      return;
    }
    commanded(payload, "close").ifPresent(c -> {
      StoredFinding f = c.finding();
      if (!"fixed".equals(f.status()) && !"not_affected".equals(f.status())) {
        findings.setStatus(c.repo().id(), f.contractId(), f.changeId(), "not_affected", null);
      }
    });
  }

  /** Reopening the issue takes a not-affected verdict back: the finding is open again. */
  private void reopenedByPerson(JsonNode payload) {
    if (fromBot(payload)) {
      return;
    }
    commanded(payload, "reopen").ifPresent(c -> {
      StoredFinding f = c.finding();
      if ("not_affected".equals(f.status())) {
        findings.setStatus(c.repo().id(), f.contractId(), f.changeId(), "open", null);
      }
    });
  }

  private record Command(Repo repo, StoredFinding finding) {}

  /** The finding behind this issue event, when the issue is a finding's and its sender may act on it. */
  private Optional<Command> commanded(JsonNode payload, String what) {
    long repoId = payload.path("repository").path("id").asLong();
    int number = payload.path("issue").path("number").asInt();
    String actor = payload.path("sender").path("login").asString();
    Optional<Repo> repo = repos.find(repoId);
    if (repo.isEmpty()) {
      return Optional.empty();
    }
    Optional<StoredFinding> finding = findings.findByIssue(repoId, number);
    if (finding.isEmpty()) {
      return Optional.empty();
    }
    if (!canCommand(repo.get(), actor)) {
      log.warn("Ignoring {} on {}#{} from {}: no write permission", what, repo.get().fullName(), number, actor);
      return Optional.empty();
    }
    return Optional.of(new Command(repo.get(), finding.get()));
  }

  /** GitHub reports an App's own REST calls with the App's bot account as the sender. */
  private static boolean fromBot(JsonNode payload) {
    JsonNode sender = payload.path("sender");
    return "Bot".equals(sender.path("type").asString()) || sender.path("login").asString().endsWith("[bot]");
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
