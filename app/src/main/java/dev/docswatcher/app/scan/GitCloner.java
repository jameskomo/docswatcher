package dev.docswatcher.app.scan;

import dev.docswatcher.app.config.AppProperties;
import dev.docswatcher.app.github.GitHubClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Component;

/** Shallow clones with JGit. Depth one at a branch, then an optional checkout of a specific SHA. */
@Component
public class GitCloner {

  private final AppProperties.Worker config;

  public GitCloner(AppProperties properties) {
    this.config = properties.worker();
  }

  public record Checkout(Path dir, String sha) implements AutoCloseable {
    @Override
    public void close() {
      deleteQuietly(dir);
    }
  }

  public Checkout clone(GitHubClient.CloneSource source, String branch, String sha) throws Exception {
    Path dir = Files.createTempDirectory("docswatcher-scan-");
    try {
      // docswatcher.worker.clone-timeout-seconds was configured, documented, and read by
      // nothing: the product's only timeout-shaped knob governed no code at all, which is worse
      // than having none because it tells whoever tunes it that a bound exists.
      int timeout = (int) Math.max(1, config.cloneTimeoutSeconds());
      CloneCommand cmd = Git.cloneRepository().setURI(source.uri()).setDirectory(dir.toFile()).setDepth(1).setCloneAllBranches(false).setTimeout(timeout);
      if (branch != null) {
        cmd.setBranch("refs/heads/" + branch);
      }
      UsernamePasswordCredentialsProvider credentials = null;
      if (source.username() != null) {
        credentials = new UsernamePasswordCredentialsProvider(source.username(), source.password());
        cmd.setCredentialsProvider(credentials);
      }
      String head;
      try (Git git = cmd.call()) {
        head = git.getRepository().resolve("HEAD").getName();
        if (sha != null && !sha.equals(head)) {
          git.fetch().setDepth(1).setRefSpecs(sha).setCredentialsProvider(credentials).setTimeout(timeout).call();
          git.checkout().setName(sha).call();
          head = sha;
        }
      }
      deleteQuietly(dir.resolve(".git"));
      return new Checkout(dir, head);
    } catch (Exception e) {
      deleteQuietly(dir);
      throw e;
    }
  }

  static void deleteQuietly(Path dir) {
    if (dir == null || !Files.exists(dir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    } catch (IOException ignored) {
      // best effort
    }
  }
}
