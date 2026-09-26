package dev.docswatcher.app.api;

import dev.docswatcher.app.auth.MemberAccess;
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

/**
 * Acting on findings. Open to signed-in members with write access to the repository, the same
 * bar a finding issue's labels are held to, because each action changes shared state or spends
 * the installation's write authority.
 */
@RestController
@RequestMapping("/api")
@MemberAccess(MemberAccess.Level.WRITE)
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
    return doSnooze(repoId, contractId, changeId, body == null ? 0 : body.days());
  }

  @PostMapping("/findings/{repoId}/{contractId}/{changeId}/not-in-prod")
  public ResponseEntity<Dto.Ack> notInProd(@PathVariable("repoId") long repoId, @PathVariable("contractId") String contractId, @PathVariable("changeId") String changeId) {
    return doNotInProd(repoId, contractId, changeId);
  }

  @PostMapping("/findings/{repoId}/{contractId}/{changeId}/not-affected")
  public ResponseEntity<Dto.Ack> notAffected(@PathVariable("repoId") long repoId, @PathVariable("contractId") String contractId, @PathVariable("changeId") String changeId) {
    return withFinding(repoId, contractId, changeId, f -> {
      findings.setStatus(repoId, contractId, changeId, "not_affected", null);
      return new Dto.Ack("not_affected", null);
    });
  }

  @PostMapping("/findings/{repoId}/{contractId}/{changeId}/fix")
  public ResponseEntity<Dto.Ack> fix(@PathVariable("repoId") long repoId, @PathVariable("contractId") String contractId, @PathVariable("changeId") String changeId) {
    return doFix(repoId, contractId, changeId);
  }

  /** The same three actions with the finding named in the body, for contract ids a path cannot carry. */
  @PostMapping("/repos/{repoId}/findings/snooze")
  public ResponseEntity<Dto.Ack> snoozeByRef(@PathVariable("repoId") long repoId, @RequestBody Dto.FindingRef ref) {
    return doSnooze(repoId, ref.contract(), ref.change(), ref.days() == null ? 0 : ref.days());
  }

  @PostMapping("/repos/{repoId}/findings/not-in-prod")
  public ResponseEntity<Dto.Ack> notInProdByRef(@PathVariable("repoId") long repoId, @RequestBody Dto.FindingRef ref) {
    return doNotInProd(repoId, ref.contract(), ref.change());
  }

  @PostMapping("/repos/{repoId}/findings/fix")
  public ResponseEntity<Dto.Ack> fixByRef(@PathVariable("repoId") long repoId, @RequestBody Dto.FindingRef ref) {
    return doFix(repoId, ref.contract(), ref.change());
  }

  @PostMapping("/repos/{repoId}/rescan")
  public ResponseEntity<Dto.Ack> rescan(@PathVariable("repoId") long id) {
    if (repos.find(id).isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    long runId = runs.enqueue(id, null, ScanRun.TRIGGER_MANUAL);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(new Dto.Ack("queued", runId));
  }

  private ResponseEntity<Dto.Ack> doSnooze(long repoId, String contractId, String changeId, int requestedDays) {
    int days = requestedDays <= 0 ? 30 : requestedDays;
    return withFinding(repoId, contractId, changeId, f -> {
      LocalDate until = LocalDate.now().plusDays(days);
      findings.setStatus(repoId, contractId, changeId, "snoozed", until);
      return new Dto.Ack("snoozed", until.toString());
    });
  }

  private ResponseEntity<Dto.Ack> doNotInProd(long repoId, String contractId, String changeId) {
    return withFinding(repoId, contractId, changeId, f -> {
      findings.setStatus(repoId, contractId, changeId, "not_in_prod", null);
      return new Dto.Ack("not_in_prod", null);
    });
  }

  private ResponseEntity<Dto.Ack> doFix(long repoId, String contractId, String changeId) {
    // The fix workflow is a GitHub repository_dispatch. GitLab has no counterpart yet (ADR 0012),
    // so a GitLab project is told so instead of failing inside the dispatcher.
    if (repos.find(repoId).map(Repo::isGitLab).orElse(false)) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(new Dto.Ack("unsupported", "Fix pull requests are GitHub only; GitLab projects get the issue and the dashboard."));
    }
    return withFinding(repoId, contractId, changeId, f -> {
      Repo repo = repos.find(repoId).orElseThrow();
      Map<String, Object> payload = fixes.dispatch(repo, f);
      return new Dto.Ack("dispatched", payload);
    });
  }

  private ResponseEntity<Dto.Ack> withFinding(long repoId, String contractId, String changeId, java.util.function.Function<StoredFinding, Dto.Ack> action) {
    if (contractId == null || changeId == null) {
      return ResponseEntity.badRequest().build();
    }
    return findings.find(repoId, contractId, changeId).map(f -> ResponseEntity.ok(action.apply(f))).orElseGet(() -> ResponseEntity.notFound().build());
  }
}
