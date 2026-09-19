package dev.docswatcher.app.runtime;

import dev.docswatcher.app.engine.ScanEngine;
import dev.docswatcher.app.store.ContractStore;
import dev.docswatcher.app.store.Repo;
import dev.docswatcher.app.store.RepoStore;
import dev.docswatcher.app.store.StoredContract;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Turns one OTLP export into runtime observations.
 *
 * <p>Two rules govern what is kept. A span carrying a Deprecation or Sunset header is
 * always recorded, because the provider has said something and that is the signal we
 * exist to catch. A span without either is recorded only when it matches a contract the
 * scanner already found, which keeps call volume for endpoints we care about while
 * refusing to become a log of every outbound request the customer makes.
 */
@Service
public class RuntimeIngestService {

  private static final Logger log = LoggerFactory.getLogger(RuntimeIngestService.class);

  /** The resource attribute a customer sets to say which repository this service is. */
  static final String REPO_ATTRIBUTE = "docswatcher.repo";

  private final RepoStore repos;
  private final ContractStore contracts;
  private final RuntimeObservationStore observations;
  private final ScanEngine engine;

  public RuntimeIngestService(RepoStore repos, ContractStore contracts, RuntimeObservationStore observations, ScanEngine engine) {
    this.repos = repos;
    this.contracts = contracts;
    this.observations = observations;
    this.engine = engine;
  }

  /** The outcome of one export, returned to the caller so a misconfiguration is visible immediately. */
  public record Result(int spans, int recorded, int skipped, int unattributed, String repo) {}

  public Result ingest(JsonNode payload, String repoOverride) {
    ProviderAttributor attributor = new ProviderAttributor(engine.providers());
    Counters counters = new Counters();
    Map<Long, Set<String>> contractIdsByRepo = new HashMap<>();
    String lastRepo = null;

    JsonNode resourceSpans = payload == null ? null : payload.get("resourceSpans");
    if (resourceSpans == null || !resourceSpans.isArray()) {
      return new Result(0, 0, 0, 0, null);
    }

    for (JsonNode resourceSpan : resourceSpans) {
      String fullName = repoName(resourceSpan, repoOverride);
      if (fullName == null) {
        // Named nothing, so it belongs to nothing. Counted, not guessed at.
        counters.skipped += countSpans(resourceSpan);
        continue;
      }
      Optional<Repo> repo = repos.findByFullName(fullName);
      if (repo.isEmpty()) {
        counters.skipped += countSpans(resourceSpan);
        continue;
      }
      lastRepo = fullName;
      long repoId = repo.get().id();
      Set<String> known = contractIdsByRepo.computeIfAbsent(repoId, this::endpointContractIds);
      ingestResource(resourceSpan, repoId, known, attributor, counters);
    }
    return new Result(counters.spans, counters.recorded, counters.skipped, counters.unattributed, lastRepo);
  }

  private void ingestResource(JsonNode resourceSpan, long repoId, Set<String> known, ProviderAttributor attributor, Counters counters) {
    JsonNode scopeSpans = resourceSpan.get("scopeSpans");
    if (scopeSpans == null || !scopeSpans.isArray()) {
      return;
    }
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    for (JsonNode scope : scopeSpans) {
      JsonNode spans = scope.get("spans");
      if (spans == null || !spans.isArray()) {
        continue;
      }
      for (JsonNode span : spans) {
        counters.spans++;
        try {
          record(span, repoId, known, attributor, today, counters);
        } catch (RuntimeException e) {
          // One odd span is not worth failing an export the customer cannot retry.
          counters.skipped++;
          log.debug("skipped a span that could not be read", e);
        }
      }
    }
  }

  private void record(JsonNode span, long repoId, Set<String> known, ProviderAttributor attributor, LocalDate today, Counters counters) {
    Optional<HttpCall> parsed = SpanReader.read(span);
    if (parsed.isEmpty()) {
      counters.skipped++;
      return;
    }
    HttpCall call = parsed.get();
    Optional<String> provider = attributor.provider(call);
    String contractId = provider.flatMap(p -> attributor.candidateContractIds(p, call).stream().filter(known::contains).findFirst()).orElse(null);

    if (!call.hasProviderNotice() && contractId == null) {
      // Nothing announced and nothing we track. Not our business to store.
      counters.skipped++;
      return;
    }
    if (provider.isEmpty()) {
      counters.unattributed++;
    }
    observations.record(repoId, today, call, provider.orElse(null), contractId, 1);
    counters.recorded++;
  }

  /** The endpoint contracts this repository's last scan produced, which is what a call can attach to. */
  private Set<String> endpointContractIds(long repoId) {
    Set<String> ids = new HashSet<>();
    for (StoredContract c : contracts.currentForRepo(repoId)) {
      if ("endpoint".equals(c.kind())) {
        ids.add(c.id());
      }
    }
    return ids;
  }

  private static String repoName(JsonNode resourceSpan, String override) {
    JsonNode resource = resourceSpan.get("resource");
    JsonNode attrs = resource == null ? null : resource.get("attributes");
    return SpanReader.Attributes.of(attrs).get(REPO_ATTRIBUTE).orElse(override);
  }

  private static int countSpans(JsonNode resourceSpan) {
    int n = 0;
    JsonNode scopeSpans = resourceSpan.get("scopeSpans");
    if (scopeSpans == null || !scopeSpans.isArray()) {
      return 0;
    }
    for (JsonNode scope : scopeSpans) {
      JsonNode spans = scope.get("spans");
      if (spans != null && spans.isArray()) {
        n += spans.size();
      }
    }
    return n;
  }

  private static final class Counters {
    int spans;
    int recorded;
    int skipped;
    int unattributed;
  }
}
