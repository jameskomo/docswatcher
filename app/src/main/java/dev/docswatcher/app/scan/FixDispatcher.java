package dev.docswatcher.app.scan;

import dev.docswatcher.app.engine.ScanEngine;
import dev.docswatcher.app.github.GitHubClient;
import dev.docswatcher.app.model.ChangeDoc;
import dev.docswatcher.app.model.EvidenceDoc;
import dev.docswatcher.app.store.ContractStore;
import dev.docswatcher.app.store.Repo;
import dev.docswatcher.app.store.StoredContract;
import dev.docswatcher.app.store.StoredFinding;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Assembles the fix context and dispatches it to the customer's workflow. */
@Component
public class FixDispatcher {

  public static final String EVENT_TYPE = "docswatcher-fix";

  private final GitHubClient client;
  private final ContractStore contracts;
  private final ScanEngine engine;
  private final ObjectMapper mapper;

  public FixDispatcher(GitHubClient client, ContractStore contracts, ScanEngine engine, ObjectMapper mapper) {
    this.client = client;
    this.contracts = contracts;
    this.engine = engine;
    this.mapper = mapper;
  }

  public Map<String, Object> dispatch(Repo repo, StoredFinding finding) {
    Map<String, Object> payload = payload(repo, finding);
    client.repositoryDispatch(repo.installationId(), repo.fullName(), EVENT_TYPE, payload);
    return payload;
  }

  /** Caps how many locations one repository can push into the dispatched payload. */
  private static final int MAX_EVIDENCE = 50;

  public Map<String, Object> payload(Repo repo, StoredFinding finding) {
    Optional<ChangeDoc> change = engine.change(finding.changeId());
    Optional<StoredContract> contract = contracts.forRepo(repo.id()).stream().filter(c -> c.id().equals(finding.contractId())).findFirst();
    List<EvidenceDoc> evidence = contract.map(c -> List.of(mapper.readValue(c.evidenceJson(), EvidenceDoc[].class))).orElse(List.of());

    Map<String, Object> f = new LinkedHashMap<>();
    f.put("id", finding.id());
    f.put("contract", finding.contractId());
    f.put("change", finding.changeId());
    f.put("severity", finding.severity());
    f.put("effective", finding.effective() == null ? null : finding.effective().toString());
    // Structural locators only. Evidence.snippet is the verbatim matched source line of a
    // repository DocsWatcher does not control, and the shipped workflow hands this payload to a
    // coding agent. The agent has the checkout and can read the line itself, so the prose never
    // needs to cross that boundary. The paths also become the agent's edit scope, which the
    // workflow validates (templates/docswatcher-fix.yml).
    f.put("evidence", evidence.stream()
        .limit(MAX_EVIDENCE)
        .map(e -> Map.<String, Object>of("path", e.path(), "line", e.line(), "column", e.column()))
        .toList());

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("finding", f);
    out.put("repository", repo.fullName());
    out.put("sha", repo.lastScannedSha());
    change.ifPresent(c -> {
      out.put("title", c.title());
      out.put("summary", c.summary());
      out.put("migration", c.migration());
      out.put("guide", c.migration() == null ? null : c.migration().guide());
    });
    return out;
  }
}
