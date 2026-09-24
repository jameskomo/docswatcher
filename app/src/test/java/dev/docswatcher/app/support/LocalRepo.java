package dev.docswatcher.app.support;

import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;

/** A throwaway git repository on disk that the cloner can clone from over the file protocol. */
public final class LocalRepo implements AutoCloseable {

  public final Path dir;
  public final Git git;

  private LocalRepo(Path dir, Git git) {
    this.dir = dir;
    this.git = git;
  }

  public static LocalRepo create() throws Exception {
    Path dir = Files.createTempDirectory("docswatcher-src-");
    Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call();
    return new LocalRepo(dir, git);
  }

  public String commitFile(String name, String content, String message) throws Exception {
    Files.createDirectories(dir.resolve(name).getParent());
    Files.writeString(dir.resolve(name), content);
    git.add().addFilepattern(name).call();
    return git.commit().setMessage(message).setAuthor("test", "test@example.com").setCommitter("test", "test@example.com").call().getName();
  }

  public String removeFile(String name, String message) throws Exception {
    Files.deleteIfExists(dir.resolve(name));
    git.rm().addFilepattern(name).call();
    return git.commit().setMessage(message).setAuthor("test", "test@example.com").setCommitter("test", "test@example.com").call().getName();
  }

  public String uri() {
    return dir.toUri().toString();
  }

  @Override
  public void close() {
    git.close();
  }
}
