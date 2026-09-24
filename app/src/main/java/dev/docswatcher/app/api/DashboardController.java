package dev.docswatcher.app.api;

import dev.docswatcher.app.auth.MemberAccess;
import dev.docswatcher.app.auth.Viewer;
import dev.docswatcher.app.model.InventoryDoc;
import dev.docswatcher.app.runtime.RuntimeObservation;
import dev.docswatcher.app.runtime.RuntimeObservationStore;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dashboard's read side. Every route is open to signed-in members as well as the token
 * holder: {@code {login}} and {@code {repoId}} are checked against what the member can see before
 * a handler runs, and organisation-wide answers are filtered to their repositories.
 */
@RestController
@RequestMapping("/api")
@MemberAccess
public class DashboardController {

  private final DashboardService service;
  private final RuntimeObservationStore runtime;

  public DashboardController(DashboardService service, RuntimeObservationStore runtime) {
    this.service = service;
    this.runtime = runtime;
  }

  @GetMapping("/orgs/{login}/overview")
  public Dto.Overview overview(@PathVariable("login") String login, Viewer viewer) {
    return service.overview(login, viewer);
  }

  @GetMapping("/orgs/{login}/repos")
  public List<Dto.RepoSummary> repos(@PathVariable("login") String login, Viewer viewer) {
    return service.repos(login, viewer);
  }

  @GetMapping("/orgs/{login}/horizon")
  public List<Dto.HorizonMonth> horizon(@PathVariable("login") String login, Viewer viewer) {
    return service.horizon(login, viewer);
  }

  @GetMapping("/orgs/{login}/map")
  public List<Dto.MapNode> map(@PathVariable("login") String login, Viewer viewer) {
    return service.map(login, viewer);
  }

  @GetMapping("/orgs/{login}/blast-radius/{changeId}")
  public Dto.BlastRadius blastRadius(@PathVariable("login") String login, @PathVariable("changeId") String changeId, Viewer viewer) {
    return service.blastRadius(login, changeId, viewer);
  }

  @GetMapping("/repos/{repoId}/inventory")
  public ResponseEntity<InventoryDoc> inventory(@PathVariable("repoId") long id) {
    return ResponseEntity.of(service.inventory(id));
  }

  @GetMapping("/repos/{repoId}/findings")
  public List<Dto.RepoFinding> findings(@PathVariable("repoId") long id) {
    return service.findings(id);
  }

  @GetMapping("/repos/{repoId}/runtime")
  public List<RuntimeObservation> runtime(@PathVariable("repoId") long id) {
    return runtime.forRepo(id);
  }
}
