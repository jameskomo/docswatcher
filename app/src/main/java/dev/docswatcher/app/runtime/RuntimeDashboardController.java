package dev.docswatcher.app.runtime;

import dev.docswatcher.app.auth.MemberAccess;
import dev.docswatcher.app.auth.Viewer;
import dev.docswatcher.app.store.RepoStore;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dashboard's runtime screen: one repository's runtime summary, and the ingest tokens its
 * telemetry is sent with (docs/13-runtime-observation.md).
 *
 * <p>Anyone who can read the repository sees the summary and which tokens exist. Creating and
 * revoking a token needs write access, the bar every other state change on a repository meets:
 * a token lets its holder add observations to that repository, so it is handed out by the people
 * who could change the repository's findings anyway. Revoking is a POST, like every other action
 * here, so the dashboard's same-origin check covers it.
 */
@RestController
@RequestMapping("/api/repos/{repoId}/runtime")
public class RuntimeDashboardController {

  static final int MAX_LABEL = 80;

  private final RuntimeSummaryService summaries;
  private final IngestTokenStore tokens;
  private final RepoStore repos;

  public RuntimeDashboardController(RuntimeSummaryService summaries, IngestTokenStore tokens, RepoStore repos) {
    this.summaries = summaries;
    this.tokens = tokens;
    this.repos = repos;
  }

  public record CreateToken(String label) {}

  @MemberAccess
  @GetMapping("/summary")
  public ResponseEntity<RuntimeSummary> summary(@PathVariable("repoId") long repoId) {
    return ResponseEntity.of(summaries.summary(repoId));
  }

  @MemberAccess
  @GetMapping("/tokens")
  public ResponseEntity<List<IngestTokenStore.IngestToken>> list(@PathVariable("repoId") long repoId) {
    if (repos.find(repoId).isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(tokens.active(repoId));
  }

  /** The secret is in this response and nowhere else, ever. */
  @MemberAccess(MemberAccess.Level.WRITE)
  @PostMapping("/tokens")
  public ResponseEntity<IngestTokenStore.Created> create(
      @PathVariable("repoId") long repoId, @RequestBody(required = false) CreateToken body, Viewer viewer) {
    if (repos.find(repoId).isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    if (tokens.countActive(repoId) >= IngestTokenStore.MAX_ACTIVE_PER_REPO) {
      return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(tokens.create(repoId, label(body), createdBy(viewer)));
  }

  @MemberAccess(MemberAccess.Level.WRITE)
  @PostMapping("/tokens/{tokenId}/revoke")
  public ResponseEntity<Void> revoke(@PathVariable("repoId") long repoId, @PathVariable("tokenId") long tokenId) {
    return tokens.revoke(repoId, tokenId) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
  }

  /** Plain text, one line, bounded. The dashboard renders it as text either way. */
  static String label(CreateToken body) {
    String raw = body == null || body.label() == null ? "" : body.label();
    String clean = raw.replaceAll("\\p{Cntrl}", " ").strip().replaceAll("\\s+", " ");
    if (clean.isEmpty()) {
      return "Ingest token";
    }
    return clean.length() > MAX_LABEL ? clean.substring(0, MAX_LABEL) : clean;
  }

  private static String createdBy(Viewer viewer) {
    return viewer instanceof Viewer.Member m ? m.session().login() : "owner";
  }
}
