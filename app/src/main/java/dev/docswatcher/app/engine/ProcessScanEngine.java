package dev.docswatcher.app.engine;

import dev.docswatcher.app.model.ChangeDoc;
import dev.docswatcher.app.model.FindingDoc;
import dev.docswatcher.app.model.InventoryDoc;
import dev.docswatcher.app.model.ProviderDoc;
import dev.docswatcher.app.model.RepoRefDoc;
import dev.docswatcher.engine.OwnKnowledge;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs every scan in a process of its own (docs/adr/0011-scans-in-their-own-process.md). A scan
 * parses files an outsider chose with a native parser, and a crash there cannot be caught: in the
 * web server it would end webhooks, the API and the site with it. Here it ends one process, and the
 * scan run fails with a message that says what happened.
 *
 * <p>The process runs {@link ScanJob}, the same code {@link EngineScanEngine} runs in the server,
 * and its document is parsed by the same method, so both give the same inventory and findings. It
 * gets a heap cap, a wall-clock limit after which it and anything it started are killed, and an
 * environment of its own with none of the server's secrets. Matching a stored inventory, the
 * knowledge base and versions stay in the server: they read no repository.
 */
public class ProcessScanEngine implements ScanEngine {

  private static final Logger log = LoggerFactory.getLogger(ProcessScanEngine.class);

  /** Output kept from a scan process for the log; the rest is dropped as it arrives. */
  static final int OUTPUT_KEPT_BYTES = 16 * 1024;

  /** Environment variables the scan process keeps. Everything else, secrets included, stays behind. */
  private static final List<String> ENVIRONMENT = List.of("PATH", "LANG", "LC_ALL", "TZ", "TMPDIR");

  /**
   * @param mainClass what the process runs; {@link ScanChild} except in tests
   * @param heapMb the process's maximum heap
   * @param timeout wall-clock time before the process is killed
   * @param jvmOptions added to the process's JVM options
   */
  public record Settings(String mainClass, int heapMb, Duration timeout, List<String> jvmOptions) {

    public Settings(int heapMb, Duration timeout, List<String> jvmOptions) {
      this(ScanChild.class.getName(), heapMb, timeout, jvmOptions);
    }
  }

  private final EngineScanEngine local;
  private final Settings settings;

  public ProcessScanEngine(EngineScanEngine local, Settings settings) {
    this.local = local;
    this.settings = settings;
  }

  @Override
  public InventoryDoc scan(Path repoRoot, RepoRefDoc repo) {
    return run(repoRoot, repo, List.of(), false).inventory();
  }

  @Override
  public OwnScan scanWithOwnRecords(Path repoRoot, RepoRefDoc repo, List<OwnRecords> shared) {
    return run(repoRoot, repo, shared, true);
  }

  private OwnScan run(Path repoRoot, RepoRefDoc repo, List<OwnRecords> shared, boolean ownRecords) {
    Path work = null;
    try {
      work = Files.createTempDirectory("docswatcher-scan-");
      Path out = work.resolve("scan.json");
      List<OwnKnowledge.Source> sources = EngineScanEngine.sources(shared).stream()
          .map(s -> new OwnKnowledge.Source(s.label(), s.dir().toAbsolutePath()))
          .toList();
      ScanChild.Args args = new ScanChild.Args(repoRoot.toAbsolutePath(), EngineScanEngine.ref(repo), sources, ownRecords,
          local.knowledgeDir() == null ? null : local.knowledgeDir().toAbsolutePath(), local.limits(), LocalDate.now(), out);
      ChildProcess.Result result = launch(work, args.arguments());
      String name = repo.owner() == null ? repo.name() : repo.owner() + "/" + repo.name();
      if (result.exitCode() == 0 && !result.timedOut() && Files.isRegularFile(out)) {
        log.info("Scan process for {} finished in {} ms", name, result.elapsed().toMillis());
        if (!result.output().isBlank()) {
          log.debug("Scan process for {} said:\n{}", name, result.output());
        }
        return local.read(Files.readString(out, StandardCharsets.UTF_8));
      }
      String reason = failure(result);
      log.warn("Scan process for {} failed: {}. Its last output:\n{}", name, reason, result.output());
      throw new ScanProcessException(reason);
    } catch (IOException e) {
      throw new ScanProcessException("The scan process could not run: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ScanProcessException("The scan was stopped because the app is shutting down", e);
    } finally {
      delete(work);
    }
  }

  ChildProcess.Result launch(Path work, List<String> args) throws IOException, InterruptedException {
    List<String> jvm = new ArrayList<>(List.of(
        "-Xmx" + settings.heapMb() + "m",
        // Out of memory ends the process at once with a status of its own, instead of limping on.
        "-XX:+ExitOnOutOfMemoryError",
        // One scan is single-threaded: the smallest collector starts fastest and holds least.
        "-XX:+UseSerialGC",
        "-XX:TieredStopAtLevel=1",
        "-XX:ErrorFile=" + work.resolve("crash.log"),
        "--enable-native-access=ALL-UNNAMED",
        "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir")));
    jvm.addAll(settings.jvmOptions());
    List<String> command = ChildCommand.forMain(settings.mainClass(), jvm, args);
    Map<String, String> env = new LinkedHashMap<>();
    for (String key : ENVIRONMENT) {
      String value = System.getenv(key);
      if (value != null) {
        env.put(key, value);
      }
    }
    return ChildProcess.run(command, env, work, settings.timeout(), OUTPUT_KEPT_BYTES);
  }

  /** What went wrong, in words a scan run's error column can carry. */
  String failure(ChildProcess.Result result) {
    if (result.timedOut()) {
      return "The scan took longer than " + settings.timeout().toSeconds() + " s and its process was stopped";
    }
    if (result.output().contains("java.lang.OutOfMemoryError") || result.exitCode() == 3) {
      return "The scan ran out of memory (its process may use " + settings.heapMb() + " MB)";
    }
    if (result.exitCode() == ScanChild.FAILED) {
      return "The scan failed: " + lastLine(result.output());
    }
    if (result.exitCode() == 0) {
      return "The scan process ended without a result";
    }
    if (result.exitCode() > 128) {
      return "The scan process crashed (signal " + (result.exitCode() - 128) + ")";
    }
    return "The scan process exited with status " + result.exitCode();
  }

  private static String lastLine(String output) {
    String[] lines = output.strip().split("\n");
    String last = lines[lines.length - 1].strip();
    return last.length() > 500 ? last.substring(0, 500) + "..." : last;
  }

  /**
   * Starts a scan process that scans nothing, to learn at startup whether one can start here at all.
   *
   * @return null when it can, or why it cannot
   */
  public String probe() {
    Path work = null;
    try {
      work = Files.createTempDirectory("docswatcher-scan-probe-");
      Path empty = Files.createDirectory(work.resolve("empty"));
      Path out = work.resolve("scan.json");
      ScanChild.Args args = new ScanChild.Args(empty, new dev.docswatcher.engine.RepoRef("local", null, "probe", null, null),
          List.of(), false, local.knowledgeDir() == null ? null : local.knowledgeDir().toAbsolutePath(), local.limits(),
          LocalDate.now(), out);
      ChildProcess.Result result = launch(work, args.arguments());
      if (result.exitCode() == 0 && Files.isRegularFile(out)) {
        local.read(Files.readString(out, StandardCharsets.UTF_8));
        log.info("Scans run in a process of their own; one starts and scans nothing in {} ms", result.elapsed().toMillis());
        return null;
      }
      return failure(result) + ": " + lastLine(result.output());
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return e.toString();
    } finally {
      delete(work);
    }
  }

  private static void delete(Path dir) {
    if (dir == null) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    } catch (IOException e) {
      log.debug("Could not remove {}: {}", dir, e.toString());
    }
  }

  public Settings settings() {
    return settings;
  }

  @Override
  public List<FindingDoc> match(InventoryDoc inventory) {
    return local.match(inventory);
  }

  @Override
  public String engineVersion() {
    return local.engineVersion();
  }

  @Override
  public String knowledgeVersion() {
    return local.knowledgeVersion();
  }

  @Override
  public List<ChangeDoc> changes() {
    return local.changes();
  }

  @Override
  public List<ProviderDoc> providers() {
    return local.providers();
  }

  /** A scan that did not finish in its process. The message is written for the scan run's record. */
  public static class ScanProcessException extends RuntimeException {
    ScanProcessException(String message) {
      super(message);
    }

    ScanProcessException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
