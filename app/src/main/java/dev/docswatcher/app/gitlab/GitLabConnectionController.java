package dev.docswatcher.app.gitlab;

import dev.docswatcher.app.auth.MemberAccess;
import dev.docswatcher.app.auth.Viewer;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GitLab connections on the dashboard. Open to the owner and to signed-in members: connecting is
 * authorised by the access token itself, disconnecting by GitLab's answer about the person
 * ({@link GitLabConnectionService}).
 */
@RestController
@RequestMapping("/api/gitlab")
@MemberAccess
public class GitLabConnectionController {

  /** The namespace's full path and the access token, which is never echoed back or logged. */
  public record ConnectRequest(String namespace, String token) {}

  private final GitLabConnectionService service;

  public GitLabConnectionController(GitLabConnectionService service) {
    this.service = service;
  }

  @GetMapping("/connections")
  public List<GitLabConnectionService.Connected> list(Viewer viewer) {
    return service.list(viewer);
  }

  @PostMapping("/connections")
  public ResponseEntity<GitLabConnectionService.Connected> connect(@RequestBody ConnectRequest body, Viewer viewer) {
    String by = viewer instanceof Viewer.Member m ? m.session().provider() + ":" + m.session().login() : "owner";
    GitLabConnectionService.Connected c = service.connect(body == null ? null : body.namespace(), body == null ? null : body.token(), by);
    return ResponseEntity.status(c.webhookToken() == null ? HttpStatus.OK : HttpStatus.CREATED).body(c);
  }

  @PostMapping("/connections/{connectionId}/disconnect")
  public ResponseEntity<Void> disconnect(@PathVariable("connectionId") long id, Viewer viewer) {
    return service.disconnect(id, viewer) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
  }

  @ExceptionHandler(GitLabConnectionService.Refused.class)
  public ResponseEntity<Map<String, String>> refused(GitLabConnectionService.Refused e) {
    return ResponseEntity.status(e.status()).body(Map.of("message", e.getMessage()));
  }

  /** GitLab itself failing mid-connect is a gateway problem, said without GitLab's own words. */
  @ExceptionHandler(GitLabApi.GitLabException.class)
  public ResponseEntity<Map<String, String>> gitlabFailed(GitLabApi.GitLabException e) {
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", "GitLab did not answer as expected. Try again."));
  }
}
