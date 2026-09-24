package dev.docswatcher.cli;

import dev.docswatcher.engine.Knowledge;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
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

    Knowledge loadKnowledge() {
      if (knowledge != null) return Knowledge.load(knowledge);
      Path local = Path.of("knowledge");
      if (Files.isDirectory(local.resolve("providers"))) {
        return Knowledge.load(local);
      }
      return Knowledge.bundled();
    }
  }
}
