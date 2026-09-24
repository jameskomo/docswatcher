package dev.docswatcher.cli;

import dev.docswatcher.engine.Knowledge;
import dev.docswatcher.engine.OwnKnowledge;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** DocsWatcher command line. Finds the external API contracts a repository depends on. */
@Command(
    name = "docswatcher",
    mixinStandardHelpOptions = true,
    version = "docswatcher " + DocsWatcher.VERSION,
    description = "Scan a repository for external API contracts and match them against provider deprecations.",
    subcommands = {ScanCommand.class, MatchCommand.class, ValidateCommand.class, McpCommand.class})
public final class DocsWatcher implements Callable<Integer> {

  /** The release this build is. The release workflow refuses to publish a binary that disagrees with its tag. */
  static final String VERSION = "0.3.1";

  /**
   * Exit code for a scan that did not complete. It must differ from 1, which `match` uses to say a
   * breaking finding is open: a crash that exited 1 read as "findings" to a script and, once its
   * empty output was counted, as "nothing found" to the GitHub Action.
   */
  static final int FAILED = 3;

  /**
   * Exit code for a scan a whole-scan limit stopped (--max-files, --max-total-mb, --max-seconds),
   * with no breaking finding in what it did read. The output is valid for the files read; it is
   * not a clean bill for the repository, so it is neither 0 nor 1. A breaking finding still
   * exits 1.
   */
  static final int INCOMPLETE = 4;

  public static void main(String[] args) {
    int code;
    try {
      code = commandLine().execute(args);
    } catch (Throwable t) {
      // Errors such as OutOfMemoryError are not Exceptions, so picocli lets them through.
      report(new java.io.PrintWriter(System.err, true), t);
      code = FAILED;
    }
    System.exit(code);
  }

  /** The command line as main runs it: any exception from a command exits FAILED, not 1. */
  static CommandLine commandLine() {
    return new CommandLine(new DocsWatcher())
        .setExecutionExceptionHandler((ex, cmd, parseResult) -> {
          report(cmd.getErr(), ex);
          return FAILED;
        });
  }

  /**
   * One line for the failure, then every cause: a wrapper with no message of its own
   * (ExceptionInInitializerError, for one) says nothing useful on a machine we cannot reach.
   * DOCSWATCHER_DEBUG adds the stack trace.
   */
  static void report(java.io.PrintWriter err, Throwable ex) {
    err.println("docswatcher: " + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
    for (Throwable c = ex.getCause(); c != null && c != ex; c = c.getCause() == c ? null : c.getCause()) {
      err.println("  caused by: " + c);
    }
    if (System.getenv("DOCSWATCHER_DEBUG") != null) ex.printStackTrace(err);
    err.flush();
  }

  @Override
  public Integer call() {
    CommandLine.usage(this, System.out);
    return 0;
  }

  /** Options shared by every command. */
  static class Common {
    @Option(names = "--knowledge", paramLabel = "<dir>", description = "Knowledge directory instead of the bundled release.")
    Path knowledge;

    @Option(names = "--today", paramLabel = "YYYY-MM-DD", description = "Date used for day counts and validation. Default: today.")
    LocalDate today = LocalDate.now();

    @Option(names = "--knowledge-extra", paramLabel = "<dir>",
        description = "A directory of your own API records (providers/<id>/...) to add, such as a checkout of your "
            + "organisation's .docswatcher repository. Repeatable. A scanned repository's own .docswatcher/ is always read.")
    List<Path> knowledgeExtra = new ArrayList<>();

    Knowledge loadKnowledge() {
      if (knowledge != null) return Knowledge.load(knowledge);
      Path local = Path.of("knowledge");
      if (Files.isDirectory(local.resolve("providers"))) {
        return Knowledge.load(local);
      }
      return Knowledge.bundled();
    }

    /** The --knowledge-extra directories, each labelled as it was typed. */
    List<OwnKnowledge.Source> shared() {
      return knowledgeExtra.stream().map(p -> new OwnKnowledge.Source(p.toString().replace('\\', '/'), p)).toList();
    }

    /** The knowledge plus the own records of {@code repo} (its .docswatcher/) and of every --knowledge-extra. */
    OwnKnowledge.Result own(Knowledge base, Path repo) {
      return OwnKnowledge.merge(base, OwnKnowledge.sources(repo, shared()), today);
    }

    /**
     * The knowledge a scan of {@code repo} uses. Invalid own records stop the command: a scan without
     * them would report a clean repository that was never checked against them. Warnings go to stderr.
     */
    Knowledge loadKnowledge(Path repo) {
      OwnKnowledge.Result r = own(loadKnowledge(), repo);
      for (String w : r.warnings()) System.err.println("docswatcher: warning: " + w);
      if (!r.ok()) throw new InvalidOwnRecords(r.errors());
      return r.knowledge();
    }
  }

  /** Own API records that failed validation. The message lists every error, one per line. */
  static final class InvalidOwnRecords extends RuntimeException {
    InvalidOwnRecords(List<String> errors) {
      super("your own API records have " + errors.size() + (errors.size() == 1 ? " error" : " errors")
          + ", so nothing was scanned (docs/19-your-own-apis.md):\n  " + String.join("\n  ", errors));
    }
  }
}
