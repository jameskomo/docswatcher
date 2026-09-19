package dev.docswatcher.app.api;

import dev.docswatcher.app.scan.FixDispatcher;
import dev.docswatcher.app.store.FindingStore;
import dev.docswatcher.app.store.Repo;
import dev.docswatcher.app.store.RepoStore;
import dev.docswatcher.app.store.ScanRun;
import dev.docswatcher.app.store.ScanRunStore;
import dev.docswatcher.app.store.StoredFinding;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ActionController {

  private final RepoStore repos;
  private final FindingStore findings;
  private final ScanRunStore runs;
  private final FixDispatcher fixes;

  public ActionController(RepoStore repos, FindingStore findings, ScanRunStore runs, FixDispatcher fixes) {
    this.repos = repos;
    this.findings = findings;
    this.runs = runs;
    this.fixes = fixes;
  }

  @PostMapping("/findings/{repoId}/{contractId}/{changeId}/snooze")
  public ResponseEntity<Dto.Ack> snooze(@PathVariable("repoId") long repoId, @PathVariable("contractId") String contractId, @PathVariable("changeId") String changeId, @RequestBody(required = false) Dto.SnoozeRequest body) {
    int days = body == null || body.days() <= 0 ? 30 : body.days();
    return withFinding(repoId, contractId, changeId, f -> {
      LocalDate until = LocalDate.now().plusDays(days);
      findings.setStatus(repoId, contractId, changeId, "snoozed", until);
      return new Dto.Ack("snoozed", until.toString());
    });
  }

  @PostMapping("/findings/{repoId}/{contractId}/{changeId}/not-in-prod")
  public ResponseEntity<Dto.Ack> notInProd(@PathVariable("repoId") long repoId, @PathVariable("contractId") String contractId, @PathVariable("changeId") String changeId) {
    return withFinding(repoId, contractId, changeId, f -> {
      findings.setStatus(repoId, contractId, changeId, "not_in_prod", null);
      return new Dto.Ack("not_in_prod", null);
    });
  }

  @PostMapping("/findings/{repoId}/{contractId}/{changeId}/fix")
  public ResponseEntity<Dto.Ack> fix(@PathVariable("repoId") long repoId, @PathVariable("contractId") String contractId, @PathVariable("changeId") String changeId) {
    return withFinding(repoId, contractId, changeId, f -> {
      Repo repo = repos.find(repoId).orElseThrow();
      Map<String, Object> payload = fixes.dispatch(repo, f);
      return new Dto.Ack("dispatched", payload);
    });
  }

  @PostMapping("/repos/{id}/rescan")
  public ResponseEntity<Dto.Ack> rescan(@PathVariable("id") long id) {
    if (repos.find(id).isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    long runId = runs.enqueue(id, null, ScanRun.TRIGGER_MANUAL);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(new Dto.Ack("queued", runId));
  }

  private ResponseEntity<Dto.Ack> withFinding(long repoId, String contractId, String changeId, java.util.function.Function<StoredFinding, Dto.Ack> action) {
    return findings.find(repoId, contractId, changeId).map(f -> ResponseEntity.ok(action.apply(f))).orElseGet(() -> ResponseEntity.notFound().build());
  }
}
