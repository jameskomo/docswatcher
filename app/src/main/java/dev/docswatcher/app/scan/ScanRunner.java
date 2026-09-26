package dev.docswatcher.app.scan;

import dev.docswatcher.app.config.AppProperties;
import dev.docswatcher.app.engine.ProcessScanEngine;
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
import dev.docswatcher.app.model.ChangeDoc;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
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
      // A scan process's failure is already written for people; anything else keeps its type.
      String error = e instanceof ProcessScanEngine.ScanProcessException ? e.getMessage() : e.toString();
      log.warn("scan_run {} failed: {}", run.id(), error);
      runs.fail(run.id(), error);
    }
  }

  private void scan(ScanRun run, Repo repo) throws Exception {
    GitHubClient.CloneSource source = client.cloneSource(repo.installationId(), repo.fullName());
    List<String> problems = new ArrayList<>();
    try (GitCloner.Checkout checkout = cloner.clone(source, repo.defaultBranch(), run.sha());
        GitCloner.Checkout org = cloneOrgRecords(repo, problems)) {
      RepoRefDoc ref = new RepoRefDoc("github", repo.owner(), repo.name(), "refs/heads/" + repo.defaultBranch(), checkout.sha());
      List<ScanEngine.OwnRecords> shared = org == null
          ? List.of()
          : List.of(new ScanEngine.OwnRecords(orgRecordsRepo(repo) + "/" + ORG_RECORDS_DIR, org.dir().resolve(ORG_RECORDS_DIR)));
      ScanEngine.OwnScan result = engine.scanWithOwnRecords(checkout.dir(), ref, shared);
      InventoryDoc inventory = carryForwardIfIncomplete(repo, result.inventory());
      InventoryDoc.Incomplete incomplete = inventory.stats() == null ? null : inventory.stats().incomplete();
      problems.addAll(result.problems());
      if (!problems.isEmpty()) {
        log.warn("{}: own API records not used: {}", repo.fullName(), problems);
      }

      for (ContractDoc c : inventory.contracts()) {
        contracts.upsert(toStored(repo.id(), c, checkout.sha()));
      }
      repos.setLastScannedSha(repo.id(), checkout.sha());
      // With every record read, a finding that no longer derives is gone, even one whose records were
      // deleted. With records that could not be used, their findings are left as they were.
      Set<String> known = new HashSet<>(result.providers());
      engine.providers().forEach(p -> known.add(p.id()));
      // A scan a limit stopped did not read every file, so it judges nothing gone: it may open
      // findings, and it closes none (carryForwardIfIncomplete keeps the unseen contracts too).
      Predicate<String> judged = incomplete != null ? p -> false : problems.isEmpty() ? p -> true : known::contains;
      List<FindingDoc> open = reconcile(repo, checkout.sha(), result.findings(), judged,
          id -> result.change(id).or(() -> engine.change(id)));

      client.createCheckRun(repo.installationId(), repo.fullName(), checkout.sha(), CHECK_NAME,
          IssueText.checkConclusion(open, repo.production(), incomplete, problems),
          IssueText.checkTitle(inventory.contracts().size(), open, incomplete, problems),
          IssueText.checkSummary(open, incomplete, problems, result.warnings()));
      runs.finish(run.id(), engine.engineVersion(), engine.knowledgeVersion(), mapper.writeValueAsString(inventory.stats()));
    }
  }

  /**
   * A scan a limit stopped did not look at every file, so a contract it did not see may still be
   * in the files it did not read. Those contracts are kept as they were last seen, which means an
   * incomplete scan never closes a finding or its issue, and a later rematch does not either. The
   * next complete scan settles them.
   */
  private InventoryDoc carryForwardIfIncomplete(Repo repo, InventoryDoc scanned) {
    if (scanned.stats() == null || scanned.stats().incomplete() == null) {
      return scanned;
    }
    Set<String> seen = new HashSet<>();
    for (ContractDoc c : scanned.contracts()) {
      seen.add(c.id());
    }
    List<ContractDoc> all = new ArrayList<>(scanned.contracts());
    for (StoredContract c : contracts.currentForRepo(repo.id())) {
      if (!seen.contains(c.id())) {
        all.add(fromStored(c));
      }
    }
    log.warn("Scan of {} incomplete ({}): {} contracts carried forward from the last scan",
        repo.fullName(), scanned.stats().incomplete(), all.size() - scanned.contracts().size());
    return new InventoryDoc(scanned.schemaVersion(), scanned.repo(), scanned.scannedAt(), scanned.engine(), scanned.stats(), all);
  }

  /** The repository whose .docswatcher/ every repository of an organisation shares (docs/19-your-own-apis.md). */
  public static final String ORG_RECORDS_REPO = ".docswatcher";

  /** The directory of own API records, in every repository including the organisation's shared one. */
  public static final String ORG_RECORDS_DIR = ".docswatcher";

  static String orgRecordsRepo(Repo repo) {
    return repo.owner() + "/" + ORG_RECORDS_REPO;
  }

  public static boolean isOrgRecordsRepo(Repo repo) {
    return repo.name().equalsIgnoreCase(ORG_RECORDS_REPO);
  }

  /**
   * A checkout of the owner's .docswatcher repository, when this installation can read it and it is
   * not the repository being scanned, whose own records are read anyway. A failure is a problem to
   * report, not a reason to skip the scan.
   */
  private GitCloner.Checkout cloneOrgRecords(Repo repo, List<String> problems) {
    if (isOrgRecordsRepo(repo)) {
      return null;
    }
    Optional<Repo> org = repos.forInstallation(repo.installationId()).stream()
        .filter(r -> r.fullName().equalsIgnoreCase(orgRecordsRepo(repo)))
        .findFirst();
    if (org.isEmpty()) {
      return null;
    }
    try {
      return cloner.clone(client.cloneSource(repo.installationId(), org.get().fullName()), org.get().defaultBranch(), null);
    } catch (Exception e) {
      problems.add(org.get().fullName() + " could not be read: " + e.getMessage());
      return null;
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
    // A rematch reads the bundled knowledge only: findings from a team's own records wait for the
    // next scan, which reads the records again, instead of being closed here.
    Set<String> known = new HashSet<>();
    engine.providers().forEach(p -> known.add(p.id()));
    reconcile(repo, repo.lastScannedSha(), engine.match(inventory), known::contains, engine::change);
    runs.finish(run.id(), engine.engineVersion(), engine.knowledgeVersion(), "{}");
  }

  /**
   * Opens new findings with issues, closes findings that no longer derive, returns what is open now.
   * Stored findings of a provider outside {@code known} are left as they are.
   */
  private List<FindingDoc> reconcile(Repo repo, String sha, List<FindingDoc> derived, Predicate<String> known,
      Function<String, Optional<ChangeDoc>> changes) {
    List<StoredFinding> stored = findings.openForRepo(repo.id());
    FindingReconciler.Plan plan = FindingReconciler.plan(derived, stored, known);

    for (FindingDoc f : plan.open()) {
      findings.insertOpen(repo.id(), f.contract(), f.change(), f.id(), f.severity(), f.effective());
      var change = changes.apply(f.change());
      int number = client.createIssue(repo.installationId(), repo.fullName(),
          IssueText.issueTitle(f, change),
          IssueText.issueBody(repo, sha, f, change, github.fixLabel(), github.snoozeLabel(), github.notInProdLabel(), github.notAffectedLabel()),
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
    // A person said these do not affect the code; they stay recorded but no longer hold the check.
    Set<String> notAffected = new HashSet<>();
    for (StoredFinding f : stored) {
      if ("not_affected".equals(f.status())) {
        notAffected.add(f.contractId() + "|" + f.changeId());
      }
    }
    List<FindingDoc> open = new ArrayList<>(plan.open());
    for (FindingDoc f : plan.unchanged()) {
      if (!notAffected.contains(f.contract() + "|" + f.change())) {
        open.add(f);
      }
    }
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
