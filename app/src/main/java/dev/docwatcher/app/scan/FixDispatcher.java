package dev.docwatcher.app.scan;

import dev.docwatcher.app.engine.ScanEngine;
import dev.docwatcher.app.github.GitHubClient;
import dev.docwatcher.app.model.ChangeDoc;
import dev.docwatcher.app.model.EvidenceDoc;
import dev.docwatcher.app.store.ContractStore;
import dev.docwatcher.app.store.Repo;
import dev.docwatcher.app.store.StoredContract;
import dev.docwatcher.app.store.StoredFinding;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Assembles the fix context and dispatches it to the customer's workflow. */
@Component
public class FixDispatcher {

  public static final String EVENT_TYPE = "docwatcher-fix";

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
    f.put("evidence", evidence);

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
