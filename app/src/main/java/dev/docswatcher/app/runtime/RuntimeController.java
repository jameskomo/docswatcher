package dev.docswatcher.app.runtime;

import dev.docswatcher.app.auth.IngestAccess;
import dev.docswatcher.app.auth.Viewer;
import dev.docswatcher.app.store.Repo;
import dev.docswatcher.app.store.RepoStore;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The OTLP ingest endpoint.
 *
 * <p>The path deliberately ends in the OTLP route, so a customer points an existing
 * exporter at this base URL and changes nothing else. Only the JSON encoding is
 * accepted: protobuf would buy a little bandwidth at the cost of a dependency, and
 * a collector re-encodes with one setting.
 *
 * <p>Two callers. The owner token names the repository in the payload or with {@code ?repo=}.
 * A repository's ingest token names it by itself: every span is that repository's, and a
 * {@code ?repo=} naming a different one is refused outright.
 */
@RestController
@RequestMapping("/api/runtime")
public class RuntimeController {

  private final RuntimeIngestService ingest;
  private final RepoStore repos;

  public RuntimeController(RuntimeIngestService ingest, RepoStore repos) {
    this.ingest = ingest;
    this.repos = repos;
  }

  @IngestAccess
  @PostMapping(path = "/otlp/v1/traces", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<RuntimeIngestService.Result> traces(
      @RequestBody JsonNode payload, @RequestParam(name = "repo", required = false) String repo, Viewer viewer) {
    RuntimeIngestService.Result result;
    if (viewer instanceof Viewer.Ingest token) {
      Optional<Repo> pinned = repos.find(token.repoId());
      if (pinned.isEmpty()) {
        // The repository went away between the lookup and now; its tokens went with it.
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
      }
      if (repo != null && !repo.isBlank() && !repo.equals(pinned.get().fullName())) {
        // A token for one repository asked to write another. Not a misconfiguration to guess past.
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
      }
      result = ingest.ingestFor(pinned.get(), payload);
    } else {
      result = ingest.ingest(payload, repo);
    }
    if (result.repo() == null && result.spans() == 0 && result.skipped() > 0) {
      // Named no repository, so nothing could be stored. Say so rather than accepting
      // silently: a misconfigured exporter should fail loudly on its first export.
      return ResponseEntity.badRequest().body(result);
    }
    return ResponseEntity.accepted().body(result);
  }
}
