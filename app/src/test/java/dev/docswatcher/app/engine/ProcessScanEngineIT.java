package dev.docswatcher.app.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.docswatcher.app.config.AppProperties;
import dev.docswatcher.app.model.RepoRefDoc;
import dev.docswatcher.engine.ScanLimits;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Scans in a process of their own: the same results as in the server, and every way the process
 * can end badly turned into a failed scan instead of a failed server.
 */
@EnabledIf("knowledgePresent")
class ProcessScanEngineIT {

  private static final Path KNOWLEDGE = Path.of("..", "knowledge");
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final RepoRefDoc REF = new RepoRefDoc("github", "acme", "checkout-web", "refs/heads/main", "abc");
  private static EngineScanEngine local;

  static boolean knowledgePresent() {
    return Files.isDirectory(KNOWLEDGE.resolve("providers"));
  }

  @BeforeAll
  static void start() {
    local = new EngineScanEngine(properties(), MAPPER);
  }

  static AppProperties properties() {
    return new AppProperties(
        new AppProperties.GitHub("1", "", "", "https://api.github.com", "docswatcher:fix", "docswatcher:snooze", "docswatcher:not-in-prod"),
        new AppProperties.Api("t", false),
        new AppProperties.Web("*"),
        new AppProperties.Knowledge(KNOWLEDGE.toString()),
        new AppProperties.Worker(false, 1, 1000, 60));
  }

  private static ProcessScanEngine isolated(EngineScanEngine engine) {
    return new ProcessScanEngine(engine, new ProcessScanEngine.Settings(512, Duration.ofMinutes(2), List.of()));
  }

  private static ProcessScanEngine standIn(Class<?> main, Duration timeout, int heapMb, String... jvmOptions) {
    return new ProcessScanEngine(local, new ProcessScanEngine.Settings(main.getName(), heapMb, timeout, List.of(jvmOptions)));
  }

  private static Path fixture(String name) {
    return KNOWLEDGE.resolve("fixtures").resolve(name).resolve("repo");
  }

  /** The document with what differs between any two runs (the clock) taken out. */
  private static String comparable(ScanEngine.OwnScan scan) {
    JsonNode node = MAPPER.valueToTree(scan);
    ObjectNode inventory = (ObjectNode) node.get("inventory");
    inventory.remove("scannedAt");
    ((ObjectNode) inventory.get("stats")).remove("durationMs");
    return node.toString();
  }

  @Test
  void every_fixture_gives_the_same_inventory_and_findings_in_a_process_as_in_the_server() throws Exception {
    List<Path> repos;
    try (var s = Files.list(KNOWLEDGE.resolve("fixtures"))) {
      repos = s.map(p -> p.resolve("repo")).filter(Files::isDirectory).sorted().toList();
    }
    assertThat(repos).hasSizeGreaterThan(40);
    ProcessScanEngine isolated = isolated(local);
    long started = System.nanoTime();
    // Four scan processes at a time, as a busy worker might run them; each result is still compared alone.
    List<Boolean> hadFindings;
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      List<java.util.concurrent.Future<Boolean>> compared = new java.util.ArrayList<>();
      for (Path repo : repos) {
        compared.add(pool.submit(() -> {
          var inServer = local.scanWithOwnRecords(repo, REF, List.of());
          var inProcess = isolated.scanWithOwnRecords(repo, REF, List.of());
          assertThat(comparable(inProcess)).as(repo.toString()).isEqualTo(comparable(inServer));
          return !inServer.findings().isEmpty();
        }));
      }
      hadFindings = new java.util.ArrayList<>();
      for (var f : compared) {
        hadFindings.add(f.get());
      }
    }
    long withFindings = hadFindings.stream().filter(b -> b).count();
    assertThat(withFindings).as("the comparison must cover real findings").isGreaterThan(20);
    System.out.printf("%d fixtures scanned in both modes in %d ms%n", repos.size(), Duration.ofNanos(System.nanoTime() - started).toMillis());
  }

  @Test
  void own_records_shared_records_problems_and_limits_cross_the_process_unchanged(@TempDir Path tmp) throws Exception {
    Path own = fixture("internal-orders-own-records");
    var shared = List.of(new ScanEngine.OwnRecords("acme/.docswatcher/.docswatcher", own.resolve(".docswatcher")));
    ProcessScanEngine isolated = isolated(local);

    var inServer = local.scanWithOwnRecords(own, REF, List.of());
    var inProcess = isolated.scanWithOwnRecords(own, REF, List.of());
    assertThat(inProcess.providers()).containsExactly("internal-orders");
    assertThat(inProcess.change("internal-orders-v1-sunset-2027")).isPresent();
    assertThat(comparable(inProcess)).isEqualTo(comparable(inServer));

    Path consumer = tmp.resolve("consumer");
    Files.createDirectories(consumer.resolve("src"));
    Files.copy(own.resolve("src/checkout.ts"), consumer.resolve("src/checkout.ts"));
    Files.copy(own.resolve("package.json"), consumer.resolve("package.json"));
    var sharedInProcess = isolated.scanWithOwnRecords(consumer, REF, shared);
    assertThat(sharedInProcess.findings()).hasSize(3);
    assertThat(comparable(sharedInProcess)).isEqualTo(comparable(local.scanWithOwnRecords(consumer, REF, shared)));

    Path broken = tmp.resolve("broken");
    Files.createDirectories(broken.resolve(".docswatcher/providers/orders"));
    Files.writeString(broken.resolve(".docswatcher/providers/orders/provider.yaml"), "id: orders\nname: Orders\n");
    var bad = isolated.scanWithOwnRecords(broken, REF, List.of());
    assertThat(bad.problems()).containsExactly("provider id orders must start with internal- and name your service");
    assertThat(comparable(bad)).isEqualTo(comparable(local.scanWithOwnRecords(broken, REF, List.of())));

    // The worker's limits travel on the command line, and a scan they stop says so the same way.
    var limited = new EngineScanEngine(properties(), MAPPER, new ScanLimits(1, Long.MAX_VALUE, Duration.ofMinutes(1)));
    var cut = isolated(limited).scan(fixture("stripe-java-sources"), REF);
    assertThat(cut.stats().incomplete()).isNotNull();
    assertThat(cut.stats().incomplete().limit()).isEqualTo("maxFiles");
    assertThat(MAPPER.writeValueAsString(cut.contracts())).isEqualTo(MAPPER.writeValueAsString(limited.scan(fixture("stripe-java-sources"), REF).contracts()));
  }

  @Test
  void a_reference_with_missing_parts_crosses_the_command_line_as_it_was() {
    var ref = new RepoRefDoc("local", null, "probe", null, null);
    var inProcess = isolated(local).scan(fixture("stripe-java-sources"), ref);
    assertThat(inProcess.repo()).isEqualTo(ref);
  }

  @Test
  void the_probe_starts_a_real_scan_process() {
    assertThat(isolated(local).probe()).isNull();
  }

  @Test
  void a_crash_fails_the_scan_with_a_message_and_this_jvm_carries_on() {
    assertThatThrownBy(() -> standIn(ScanChildStandIns.Crash.class, Duration.ofMinutes(1), 256).scanWithOwnRecords(fixture("stripe-java-sources"), REF, List.of()))
        .isInstanceOf(ProcessScanEngine.ScanProcessException.class)
        .hasMessage("The scan process crashed (signal 6)");
    // Nothing here was touched: the next scan runs.
    assertThat(isolated(local).scan(fixture("stripe-java-sources"), REF).contracts()).isNotEmpty();
  }

  @Test
  void a_scan_that_threw_reports_why() {
    assertThatThrownBy(() -> standIn(ScanChildStandIns.Throws.class, Duration.ofMinutes(1), 256).scan(fixture("stripe-java-sources"), REF))
        .hasMessage("The scan failed: java.lang.IllegalStateException: the checkout vanished");
  }

  @Test
  void a_hung_scan_is_killed_at_its_timeout_with_everything_it_started(@TempDir Path tmp) throws Exception {
    Path report = tmp.resolve("grandchild.pid");
    long started = System.nanoTime();
    assertThatThrownBy(() -> standIn(ScanChildStandIns.Hang.class, Duration.ofSeconds(3), 256,
            "-D" + ScanChildStandIns.REPORT + "=" + report).scan(fixture("stripe-java-sources"), REF))
        .hasMessage("The scan took longer than 3 s and its process was stopped");
    Duration took = Duration.ofNanos(System.nanoTime() - started);
    assertThat(took).isBetween(Duration.ofSeconds(3), Duration.ofSeconds(20));
    long grandchild = Long.parseLong(Files.readString(report).strip());
    // Killed with its parent, not left behind as an orphan.
    for (int i = 0; i < 50 && running(grandchild); i++) {
      Thread.sleep(100);
    }
    assertThat(running(grandchild)).isFalse();
  }

  @Test
  void the_heap_cap_reaches_the_process_and_a_scan_past_it_fails_as_out_of_memory(@TempDir Path tmp) throws Exception {
    Path report = tmp.resolve("heap");
    assertThatThrownBy(() -> standIn(ScanChildStandIns.Heap.class, Duration.ofMinutes(1), 96,
            "-D" + ScanChildStandIns.REPORT + "=" + report).scan(fixture("stripe-java-sources"), REF))
        .hasMessage("The scan process exited with status 7");
    long max = Long.parseLong(Files.readString(report).strip());
    assertThat(max).isGreaterThan(64L << 20).isLessThanOrEqualTo(96L << 20);

    assertThatThrownBy(() -> standIn(ScanChildStandIns.Hog.class, Duration.ofMinutes(1), 64).scan(fixture("stripe-java-sources"), REF))
        .hasMessage("The scan ran out of memory (its process may use 64 MB)");
  }

  @Test
  void only_the_tail_of_a_noisy_process_is_kept() throws Exception {
    ProcessScanEngine chatty = standIn(ScanChildStandIns.Chatty.class, Duration.ofMinutes(1), 128);
    Path work = Files.createTempDirectory("docswatcher-test-");
    try {
      var result = chatty.launch(work, List.of());
      assertThat(result.exitCode()).isEqualTo(ScanChild.FAILED);
      assertThat(result.output()).startsWith("[... ").endsWith("the last words\n");
      assertThat(result.output().length()).isLessThan(ProcessScanEngine.OUTPUT_KEPT_BYTES + 100);
      assertThat(chatty.failure(result)).isEqualTo("The scan failed: the last words");
    } finally {
      Files.deleteIfExists(work);
    }
  }

  @Test
  void the_scan_process_inherits_none_of_the_servers_environment(@TempDir Path tmp) throws Exception {
    Path report = tmp.resolve("env");
    assertThatThrownBy(() -> standIn(ScanChildStandIns.Env.class, Duration.ofMinutes(1), 128,
            "-D" + ScanChildStandIns.REPORT + "=" + report).scan(fixture("stripe-java-sources"), REF))
        .hasMessage("The scan process exited with status 7");
    List<String> names = Files.readAllLines(report);
    // No token, key or database password of the server's reaches a process that reads outsiders' code.
    assertThat(names).isSubsetOf("PATH", "LANG", "LC_ALL", "TZ", "TMPDIR");
  }

  /**
   * Whether a process is still running. A killed process whose new parent has not collected it yet
   * is a zombie: it runs nothing, but Java still reports it alive, and how soon it is collected
   * depends on what the test runs under.
   */
  private static boolean running(long pid) throws java.io.IOException {
    if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) return false;
    Path stat = Path.of("/proc", Long.toString(pid), "stat");
    if (!Files.exists(stat)) return true;
    String s = Files.readString(stat);
    // The state is the first field after the command name, which is in parentheses.
    return s.charAt(s.lastIndexOf(')') + 2) != 'Z';
  }
}
