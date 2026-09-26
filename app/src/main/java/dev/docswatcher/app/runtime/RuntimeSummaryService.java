package dev.docswatcher.app.runtime;

import dev.docswatcher.app.engine.ScanEngine;
import dev.docswatcher.app.model.ChangeDoc;
import dev.docswatcher.app.store.FindingStore;
import dev.docswatcher.app.store.Repo;
import dev.docswatcher.app.store.RepoStore;
import dev.docswatcher.app.store.StoredFinding;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * Joins what production called to what the code references, for one repository.
 *
 * <p>Two lists answer two questions. Which deprecated calls does production actually make, and
 * how often: every observed endpoint that corroborates a finding or carried a provider notice.
 * Which findings has production never been seen making: open findings on endpoints with no
 * observation, which is where "delete the dead code" beats "migrate it".
 */
@Service
public class RuntimeSummaryService {

  /** A finding is still live in these states; fixed and dismissed findings are not asked about. */
  private static final Set<String> LIVE = Set.of("open", "snoozed");

  private final RepoStore repos;
  private final FindingStore findings;
  private final RuntimeObservationStore observations;
  private final IngestTokenStore tokens;
  private final ScanEngine engine;

  public RuntimeSummaryService(RepoStore repos, FindingStore findings, RuntimeObservationStore observations, IngestTokenStore tokens, ScanEngine engine) {
    this.repos = repos;
    this.findings = findings;
    this.observations = observations;
    this.tokens = tokens;
    this.engine = engine;
  }

  public Optional<RuntimeSummary> summary(long repoId) {
    return repos.find(repoId).map(this::summarise);
  }

  private RuntimeSummary summarise(Repo repo) {
    List<StoredFinding> all = findings.forRepo(repo.id());
    Map<String, List<StoredFinding>> byContract = new LinkedHashMap<>();
    for (StoredFinding f : all) {
      if (!"fixed".equals(f.status())) {
        byContract.computeIfAbsent(f.contractId(), c -> new ArrayList<>()).add(f);
      }
    }

    // Newest day first, so the first header met for an endpoint is the most recent one sent.
    Map<String, List<RuntimeObservation>> byEndpoint = new LinkedHashMap<>();
    for (RuntimeObservation o : observations.forRepo(repo.id())) {
      byEndpoint.computeIfAbsent(o.host() + " " + o.method() + " " + o.path(), k -> new ArrayList<>()).add(o);
    }

    List<RuntimeSummary.Call> deprecated = new ArrayList<>();
    List<RuntimeSummary.Call> alsoObserved = new ArrayList<>();
    Set<String> seenContracts = new HashSet<>();
    OffsetDateTime lastSeen = null;
    for (List<RuntimeObservation> days : byEndpoint.values()) {
      RuntimeSummary.Call call = collapse(days, byContract);
      if (call.contractId() != null) {
        seenContracts.add(call.contractId());
      }
      lastSeen = later(lastSeen, call.lastSeen());
      boolean notice = call.deprecationHeader() != null || call.sunsetHeader() != null;
      (notice || !call.findings().isEmpty() ? deprecated : alsoObserved).add(call);
    }
    Comparator<RuntimeSummary.Call> busiest = Comparator.comparingLong(RuntimeSummary.Call::totalCalls).reversed()
        .thenComparing(RuntimeSummary.Call::host).thenComparing(RuntimeSummary.Call::path);
    deprecated.sort(busiest);
    alsoObserved.sort(busiest);

    List<RuntimeSummary.FindingRef> notObserved = new ArrayList<>();
    int notObservable = 0;
    for (StoredFinding f : all) {
      if (!LIVE.contains(f.status())) {
        continue;
      }
      if (!"endpoint".equals(kind(f.contractId()))) {
        notObservable++;
      } else if (!seenContracts.contains(f.contractId())) {
        notObserved.add(ref(f));
      }
    }

    OffsetDateTime lastReport = later(lastSeen, tokens.lastUsed(repo.id()).orElse(null));
    return new RuntimeSummary(repo.id(), repo.fullName(), lastReport, tokens.countActive(repo.id()),
        deprecated, alsoObserved, notObserved, notObservable);
  }

  /**
   * One endpoint's days, summed. Calls per day is the mean over the days actually observed, as
   * the findings API computes it, so a weekday job does not read as quieter than it is.
   */
  private RuntimeSummary.Call collapse(List<RuntimeObservation> days, Map<String, List<StoredFinding>> byContract) {
    RuntimeObservation first = days.getFirst();
    long total = days.stream().mapToLong(RuntimeObservation::callCount).sum();
    int observed = (int) days.stream().map(RuntimeObservation::observedDate).distinct().count();
    String contract = firstNonNull(days.stream().map(RuntimeObservation::contractId));
    List<RuntimeSummary.FindingRef> linked = contract == null ? List.of()
        : byContract.getOrDefault(contract, List.of()).stream().map(this::ref).toList();
    return new RuntimeSummary.Call(
        first.host(), first.method(), first.path(),
        firstNonNull(days.stream().map(RuntimeObservation::provider)),
        contract,
        total,
        observed == 0 ? 0 : Math.round((double) total / observed),
        observed,
        days.stream().map(RuntimeObservation::firstSeen).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null),
        days.stream().map(RuntimeObservation::lastSeen).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null),
        firstNonNull(days.stream().map(RuntimeObservation::deprecationHeader)),
        firstNonNull(days.stream().map(RuntimeObservation::sunsetHeader)),
        linked);
  }

  private RuntimeSummary.FindingRef ref(StoredFinding f) {
    String title = engine.change(f.changeId()).map(ChangeDoc::title).orElse(f.changeId());
    return new RuntimeSummary.FindingRef(f.contractId(), f.changeId(), title, f.severity(), f.effective(), f.status());
  }

  /** The kind segment of provider:kind:key. */
  static String kind(String contractId) {
    int i = contractId.indexOf(':');
    int j = i < 0 ? -1 : contractId.indexOf(':', i + 1);
    return j < 0 ? "" : contractId.substring(i + 1, j);
  }

  private static String firstNonNull(Stream<String> values) {
    return values.filter(Objects::nonNull).findFirst().orElse(null);
  }

  private static OffsetDateTime later(OffsetDateTime a, OffsetDateTime b) {
    if (a == null) {
      return b;
    }
    return b == null || a.isAfter(b) ? a : b;
  }
}
