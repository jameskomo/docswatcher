package dev.docwatcher.app.api;

import dev.docwatcher.app.model.InventoryDoc;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class DashboardController {

  private final DashboardService service;

  public DashboardController(DashboardService service) {
    this.service = service;
  }

  @GetMapping("/orgs/{login}/overview")
  public Dto.Overview overview(@PathVariable("login") String login) {
    return service.overview(login);
  }

  @GetMapping("/orgs/{login}/repos")
  public List<Dto.RepoSummary> repos(@PathVariable("login") String login) {
    return service.repos(login);
  }

  @GetMapping("/orgs/{login}/horizon")
  public List<Dto.HorizonMonth> horizon(@PathVariable("login") String login) {
    return service.horizon(login);
  }

  @GetMapping("/orgs/{login}/map")
  public List<Dto.MapNode> map(@PathVariable("login") String login) {
    return service.map(login);
  }

  @GetMapping("/orgs/{login}/blast-radius/{changeId}")
  public Dto.BlastRadius blastRadius(@PathVariable("login") String login, @PathVariable("changeId") String changeId) {
    return service.blastRadius(login, changeId);
  }

  @GetMapping("/repos/{id}/inventory")
  public ResponseEntity<InventoryDoc> inventory(@PathVariable("id") long id) {
    return ResponseEntity.of(service.inventory(id));
  }

  @GetMapping("/repos/{id}/findings")
  public List<Dto.RepoFinding> findings(@PathVariable("id") long id) {
    return service.findings(id);
  }
}
