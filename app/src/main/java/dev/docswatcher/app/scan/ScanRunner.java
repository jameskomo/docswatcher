package dev.docswatcher.app.scan;

import dev.docswatcher.app.config.AppProperties;
import dev.docswatcher.app.engine.ScanEngine;
import dev.docswatcher.app.github.GitHubClient;
import dev.docswatcher.app.model.ContractDoc;
import dev.docswatcher.app.model.ContextDoc;
import dev.docswatcher.app.model.EvidenceDoc;
import dev.docswatcher.app.model.FindingDoc;
import dev.docswatcher.app.model.InventoryDoc;
import dev.docswatcher.app.model.RepoRefDoc;
import dev.docswatcher.app.store.ContractStore;
import dev.docswatcher.app.store.FindingStore;
import dev.docswatcher.app.store.Repo;
import dev.docswatcher.app.store.RepoStore;
import dev.docswatcher.app.store.ScanRun;
import dev.docswatcher.app.store.ScanRunStore;
import dev.docswatcher.app.store.StoredContract;
import dev.docswatcher.app.store.StoredFinding;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Executes one scan_run end to end. The worker only decides when; this class decides what. */
@Component
public class ScanRunner {

  private static final Logger log = LoggerFactory.getLogger(ScanRunner.class);
  public static final String CHECK_NAME = "DocsWatcher";

  private final AppProperties.GitHub github;
  private final ScanEngine engine;
  private final GitHubClient client;
  private final GitCloner cloner;
  private final RepoStore repos;
  private final ContractStore contracts;
  private final FindingStore findings;
  private final ScanRunStore runs;
  private final ObjectMapper mapper;

  public ScanRunner(AppProperties properties, ScanEngine engine, GitHubClient client, GitCloner cloner, RepoStore repos, ContractStore contracts, FindingStore findings, ScanRunStore runs, ObjectMapper mapper) {
    this.github = properties.github();
    this.engine = engine;
    this.client = client;
    this.cloner = cloner;
    this.repos = repos;
    this.contracts = contracts;
    this.findings = findings;
    this.runs = runs;
    this.mapper = mapper;
  }

  public void run(ScanRun run) {
    try {
      Repo repo = repos.find(run.repoId()).orElseThrow(() -> new IllegalStateException("repo " + run.repoId() + " gone"));
      if (ScanRun.TRIGGER_REMATCH.equals(run.trigger())) {
        rematch(run, repo);
      } else {
        scan(run, repo);
      }
    } catch (Exception e) {
      log.warn("scan_run {} failed: {}", run.id(), e.toString());
      runs.fail(run.id(), e.toString());
    }
  }

  private void scan(ScanRun run, Repo repo) throws Exception {
    GitHubClient.CloneSource source = client.cloneSource(repo.installationId(), repo.fullName());
    try (GitCloner.Checkout checkout = cloner.clone(source, repo.defaultBranch(), run.sha())) {
      RepoRefDoc ref = new RepoRefDoc("github", repo.owner(), repo.name(), "refs/heads/" + repo.defaultBranch(), checkout.sha());
      InventoryDoc inventory = engine.scan(checkout.dir(), ref);
      List<FindingDoc> derived = engine.match(inventory);

      for (ContractDoc c : inventory.contracts()) {
        contracts.upsert(toStored(repo.id(), c, checkout.sha()));
      }
      repos.setLastScannedSha(repo.id(), checkout.sha());
      List<FindingDoc> open = reconcile(repo, checkout.sha(), derived);

      client.createCheckRun(repo.installationId(), repo.fullName(), checkout.sha(), CHECK_NAME,
          IssueText.checkConclusion(open, repo.production()),
          IssueText.checkTitle(inventory.contracts().size(), open),
          IssueText.checkSummary(open));
      runs.finish(run.id(), engine.engineVersion(), engine.knowledgeVersion(), mapper.writeValueAsString(inventory.stats()));
    }
  }

  private void rematch(ScanRun run, Repo repo) {
    List<ContractDoc> current = new ArrayList<>();
    for (StoredContract c : contracts.currentForRepo(repo.id())) {
      current.add(fromStored(c));
    }
    InventoryDoc inventory = new InventoryDoc("1",
        new RepoRefDoc("github", repo.owner(), repo.name(), "refs/heads/" + repo.defaultBranch(), repo.lastScannedSha()),
        Instant.now().toString(), new InventoryDoc.EngineInfo("stored", engine.engineVersion(), engine.knowledgeVersion()),
        new InventoryDoc.Stats(0, 0, 0, List.of()), current);
    reconcile(repo, repo.lastScannedSha(), engine.match(inventory));
    runs.finish(run.id(), engine.engineVersion(), engine.knowledgeVersion(), "{}");
  }

  /** Opens new findings with issues, closes findings that no longer derive, returns what is open now. */
  private List<FindingDoc> reconcile(Repo repo, String sha, List<FindingDoc> derived) {
    List<StoredFinding> stored = findings.openForRepo(repo.id());
    FindingReconciler.Plan plan = FindingReconciler.plan(derived, stored);

    for (FindingDoc f : plan.open()) {
      findings.insertOpen(repo.id(), f.contract(), f.change(), f.id(), f.severity(), f.effective());
      var change = engine.change(f.change());
      int number = client.createIssue(repo.installationId(), repo.fullName(),
          IssueText.issueTitle(f, change),
          IssueText.issueBody(repo, sha, f, change, github.fixLabel(), github.snoozeLabel(), github.notInProdLabel()),
          List.of("docswatcher", "docswatcher:" + f.severity()));
      findings.setIssueNumber(repo.id(), f.contract(), f.change(), number);
    }
    for (FindingDoc f : plan.unchanged()) {
      findings.insertOpen(repo.id(), f.contract(), f.change(), f.id(), f.severity(), f.effective());
    }
    for (StoredFinding f : plan.close()) {
      findings.close(repo.id(), f.contractId(), f.changeId());
      if (f.issueNumber() != null) {
        client.closeIssue(repo.installationId(), repo.fullName(), f.issueNumber(), "Resolved: the contract is no longer observed at " + sha + ".");
      }
    }
    List<FindingDoc> open = new ArrayList<>(plan.open());
    open.addAll(plan.unchanged());
    return open;
  }

  private StoredContract toStored(long repoId, ContractDoc c, String sha) {
    return new StoredContract(repoId, c.id(), c.provider(), c.kind(), c.key(), c.confidence(),
        mapper.writeValueAsString(c.evidence()), c.context() == null ? null : mapper.writeValueAsString(c.context()), sha, sha);
  }

  public ContractDoc fromStored(StoredContract c) {
    List<EvidenceDoc> evidence = List.of(mapper.readValue(c.evidenceJson(), EvidenceDoc[].class));
    ContextDoc context = c.contextJson() == null ? null : mapper.readValue(c.contextJson(), ContextDoc.class);
    return new ContractDoc(c.id(), c.provider(), c.kind(), c.key(), c.confidence(), evidence, context);
  }
}
