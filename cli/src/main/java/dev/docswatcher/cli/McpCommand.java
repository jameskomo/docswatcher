package dev.docswatcher.cli;

import dev.docswatcher.engine.Knowledge;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(name = "mcp", description = "Run as a Model Context Protocol server on stdio, so coding agents can check an API before they call it.")
final class McpCommand implements Callable<Integer> {

  @Mixin DocsWatcher.Common common;

  @Spec CommandSpec spec;

  @Override
  public Integer call() throws Exception {
    // stdout is the protocol. Anything else printed there corrupts the stream, so every stray
    // println from here on goes to stderr and only the server holds the real stdout.
    PrintStream protocol = new PrintStream(System.out, false, StandardCharsets.UTF_8);
    System.setOut(System.err);

    Knowledge k = common.loadKnowledge();
    // A server can run for days. Unless a date was pinned, "today" is asked for on every call.
    Supplier<LocalDate> today = spec.commandLine().getParseResult().hasMatchedOption("--today")
        ? () -> common.today
        : LocalDate::now;
    McpServer server = new McpServer(k, today, Path.of("").toAbsolutePath(), DocsWatcher.VERSION);
    System.err.println("docswatcher mcp " + DocsWatcher.VERSION + " ready: " + k.changes().size()
        + " deprecation records, " + k.providers().size() + " providers");
    server.serve(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), protocol);
    return 0;
  }
}
