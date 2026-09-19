package dev.docswatcher.app.api;

import dev.docswatcher.app.engine.ScanEngine;
import dev.docswatcher.app.model.ChangeDoc;
import dev.docswatcher.app.model.ContractDoc;
import dev.docswatcher.app.model.EvidenceDoc;
import dev.docswatcher.app.model.FindingDoc;
import dev.docswatcher.app.model.InventoryDoc;
import dev.docswatcher.app.model.RepoRefDoc;
import dev.docswatcher.app.runtime.RuntimeObservation;
import dev.docswatcher.app.runtime.RuntimeObservationStore;
import dev.docswatcher.app.scan.ScanRunner;
import dev.docswatcher.app.store.ContractStore;
import dev.docswatcher.app.store.FindingStore;
import dev.docswatcher.app.store.Repo;
import dev.docswatcher.app.store.RepoStore;
import dev.docswatcher.app.store.ScanRun;
import dev.docswatcher.app.store.ScanRunStore;
import dev.docswatcher.app.store.StoredContract;
import dev.docswatcher.app.store.StoredFinding;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class DashboardService {

  private final RepoStore repos;
  private final ContractStore contracts;
  private final FindingStore findings;
  private final ScanRunStore runs;
  private final ScanEngine engine;
  private final ScanRunner runner;
  private final RuntimeObservationStore runtime;
  private final ObjectMapper mapper;

  public DashboardService(RepoStore repos, ContractStore contracts, FindingStore findings, ScanRunStore runs, ScanEngine engine, ScanRunner runner, RuntimeObservationStore runtime, ObjectMapper mapper) {
    this.repos = repos;
    this.contracts = contracts;
    this.findings = findings;
    this.runs = runs;
    this.engine = engine;
    this.runner = runner;
    this.runtime = runtime;
    this.mapper = mapper;
  }

  public Dto.Overview overview(String login) {
    List<Repo> repoList = repos.forLogin(login);
    Map<String, Long> bySeverity = new TreeMap<>();
    LocalDate nearest = null;
    for (StoredFinding f : findings.forLogin(login)) {
      if (!"open".equals(f.status())) {
        continue;
      }
      bySeverity.merge(f.severity(), 1L, Long::sum);
      if (f.effective() != null && (nearest == null || f.effective().isBefore(nearest))) {
        nearest = f.effective();
      }
    }
    return new Dto.Overview(login, repoList.size(), contracts.countForLogin(login), bySeverity, nearest, engine.knowledgeVersion());
  }

  public List<Dto.RepoSummary> repos(String login) {
    List<Dto.RepoSummary> out = new ArrayList<>();
    for (Repo r : repos.forLogin(login)) {
      long open = findings.openForRepo(r.id()).stream().filter(f -> "open".equals(f.status())).count();
      out.add(new Dto.RepoSummary(r.id(), r.fullName(), r.defaultBranch(), r.lastScannedSha(), r.production(), open));
    }
    return out;
  }

  public Optional<InventoryDoc> inventory(long repoId) {
    return repos.find(repoId).map(repo -> {
      List<ContractDoc> docs = new ArrayList<>();
      for (StoredContract c : contracts.currentForRepo(repoId)) {
        docs.add(runner.fromStored(c));
      }
      Optional<ScanRun> last = runs.lastDone(repoId);
      InventoryDoc.Stats stats = last.map(ScanRun::statsJson).filter(s -> s != null && !s.equals("{}"))
          .map(s -> mapper.readValue(s, InventoryDoc.Stats.class)).orElse(new InventoryDoc.Stats(0, 0, 0, List.of()));
      return new InventoryDoc("1",
          new RepoRefDoc("github", repo.owner(), repo.name(), "refs/heads/" + repo.defaultBranch(), repo.lastScannedSha()),
          last.map(r -> r.finishedAt().toString()).orElse(null),
          new InventoryDoc.EngineInfo("docswatcher-engine-java", last.map(ScanRun::engineVersion).orElse(null), last.map(ScanRun::knowledgeVersion).orElse(null)),
          stats, docs);
    });
  }

  public List<Dto.RepoFinding> findings(long repoId) {
    Optional<Repo> repo = repos.find(repoId);
    if (repo.isEmpty()) {
      return List.of();
    }
    Map<String, StoredContract> byId = contractsById(repoId);
    Map<String, Dto.Runtime> runtimeByContract = runtimeByContract(repoId);
    List<Dto.RepoFinding> out = new ArrayList<>();
    for (StoredFinding f : findings.forRepo(repoId)) {
      Dto.RepoFinding dto = toDto(repo.get(), f, byId);
      Dto.Runtime seen = runtimeByContract.get(f.contractId());
      out.add(seen == null ? dto : new Dto.RepoFinding(dto.repoId(), dto.repoFullName(), dto.finding(), dto.changeTitle(), seen));
    }
    return out;
  }

  public List<Dto.HorizonMonth> horizon(String login) {
    Map<String, List<Dto.RepoFinding>> months = new TreeMap<>();
    Map<Long, Repo> repoById = new HashMap<>();
    Map<Long, Map<String, StoredContract>> contractsByRepo = new HashMap<>();
    for (StoredFinding f : findings.forLogin(login)) {
      if ("fixed".equals(f.status())) {
        continue;
      }
      Repo repo = repoById.computeIfAbsent(f.repoId(), id -> repos.find(id).orElseThrow());
      Map<String, StoredContract> byId = contractsByRepo.computeIfAbsent(f.repoId(), this::contractsById);
      String month = f.effective() == null ? "undated" : f.effective().toString().substring(0, 7);
      months.computeIfAbsent(month, m -> new ArrayList<>()).add(toDto(repo, f, byId));
    }
    List<Dto.HorizonMonth> out = new ArrayList<>();
    months.forEach((m, list) -> out.add(new Dto.HorizonMonth(m, list)));
    return out;
  }

  public List<Dto.MapNode> map(String login) {
    Map<String, long[]> openByProvider = new HashMap<>();
    Map<String, String> worst = new HashMap<>();
    for (StoredFinding f : findings.forLogin(login)) {
      if (!"open".equals(f.status())) {
        continue;
      }
      String provider = f.contractId().substring(0, f.contractId().indexOf(':'));
      openByProvider.computeIfAbsent(provider, p -> new long[1])[0]++;
      worst.merge(provider, f.severity(), (a, b) -> rank(a) >= rank(b) ? a : b);
    }
    List<Dto.MapNode> out = new ArrayList<>();
    for (ContractStore.ProviderCount p : contracts.countByProvider(login)) {
      out.add(new Dto.MapNode(p.provider(), p.contracts(), p.evidence(), worst.getOrDefault(p.provider(), "healthy"),
          openByProvider.containsKey(p.provider()) ? openByProvider.get(p.provider())[0] : 0));
    }
    return out;
  }

  public Dto.BlastRadius blastRadius(String login, String changeId) {
    Optional<ChangeDoc> change = engine.change(changeId);
    Map<Long, Repo> repoById = new HashMap<>();
    Map<Long, Map<String, StoredContract>> contractsByRepo = new HashMap<>();
    List<Dto.RepoFinding> list = new ArrayList<>();
    for (StoredFinding f : findings.forLoginAndChange(login, changeId)) {
      Repo repo = repoById.computeIfAbsent(f.repoId(), id -> repos.find(id).orElseThrow());
      list.add(toDto(repo, f, contractsByRepo.computeIfAbsent(f.repoId(), this::contractsById)));
    }
    return new Dto.BlastRadius(changeId, change.map(ChangeDoc::title).orElse(changeId), change.map(ChangeDoc::effective).orElse(null), repoById.size(), list);
  }

  /**
   * Collapses a contract's daily observations into one summary. Calls per day is the mean
   * over the days actually seen rather than over the calendar, because a job that runs on
   * weekdays should not read as quieter than it is.
   */
  private Map<String, Dto.Runtime> runtimeByContract(long repoId) {
    Map<String, List<RuntimeObservation>> byContract = new LinkedHashMap<>();
    for (RuntimeObservation o : runtime.attributedForRepo(repoId)) {
      byContract.computeIfAbsent(o.contractId(), c -> new ArrayList<>()).add(o);
    }
    Map<String, Dto.Runtime> out = new LinkedHashMap<>();
    byContract.forEach((contractId, list) -> {
      long total = list.stream().mapToLong(RuntimeObservation::callCount).sum();
      int days = (int) list.stream().map(RuntimeObservation::observedDate).distinct().count();
      out.put(contractId, new Dto.Runtime(
          days == 0 ? 0 : Math.round((double) total / days),
          total,
          days,
          list.stream().map(RuntimeObservation::lastSeen).filter(java.util.Objects::nonNull).max(java.time.OffsetDateTime::compareTo).orElse(null),
          list.stream().map(RuntimeObservation::deprecationHeader).filter(java.util.Objects::nonNull).findFirst().orElse(null),
          list.stream().map(RuntimeObservation::sunsetHeader).filter(java.util.Objects::nonNull).findFirst().orElse(null)));
    });
    return out;
  }

  private Map<String, StoredContract> contractsById(long repoId) {
    Map<String, StoredContract> byId = new LinkedHashMap<>();
    for (StoredContract c : contracts.forRepo(repoId)) {
      byId.put(c.id(), c);
    }
    return byId;
  }

  private Dto.RepoFinding toDto(Repo repo, StoredFinding f, Map<String, StoredContract> byId) {
    StoredContract c = byId.get(f.contractId());
    List<EvidenceDoc> evidence = c == null ? List.of() : List.of(mapper.readValue(c.evidenceJson(), EvidenceDoc[].class));
    Integer days = f.effective() == null ? null : (int) ChronoUnit.DAYS.between(LocalDate.now(), f.effective());
    FindingDoc doc = new FindingDoc(f.id(), f.contractId(), f.changeId(), f.severity(), f.effective(), days, evidence, f.status(), f.snoozedUntil(), f.fixPrUrl());
    return new Dto.RepoFinding(repo.id(), repo.fullName(), doc, engine.change(f.changeId()).map(ChangeDoc::title).orElse(f.changeId()));
  }

  private static int rank(String severity) {
    return switch (severity) {
      case "breaking" -> 3;
      case "warning" -> 2;
      case "info" -> 1;
      default -> 0;
    };
  }
}
