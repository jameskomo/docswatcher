package dev.docswatcher.app.engine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Scan processes that misbehave, launched by {@link ProcessScanEngine} in place of
 * {@link ScanChild} with the same command line. Each stands for one way a real scan process ends.
 */
final class ScanChildStandIns {

  private ScanChildStandIns() {}

  /** Where a stand-in reports what the test asserts on, passed as a JVM option by the test. */
  static final String REPORT = "standin.report";

  static Path report() {
    return Path.of(System.getProperty(REPORT));
  }

  /** What a native crash looks like from outside: the process ends at once, by a signal. */
  static final class Crash {
    public static void main(String[] args) {
      System.out.println("#  SIGSEGV (0xb) in a native parser, simulated");
      Runtime.getRuntime().halt(134);
    }
  }

  /** A scan that threw: the reason is the last line of its output. */
  static final class Throws {
    public static void main(String[] args) {
      System.err.println("java.lang.IllegalStateException: the checkout vanished");
      System.exit(ScanChild.FAILED);
    }
  }

  /** A scan that never ends, holding a process of its own that must die with it. */
  static final class Hang {
    public static void main(String[] args) throws Exception {
      Process sleeper = new ProcessBuilder("sleep", "600").start();
      Files.writeString(report(), Long.toString(sleeper.pid()));
      Thread.sleep(600_000);
    }
  }

  /** Reports the heap it was given, where the scan document would go. */
  static final class Heap {
    public static void main(String[] args) throws Exception {
      Files.writeString(report(), Long.toString(Runtime.getRuntime().maxMemory()));
      Runtime.getRuntime().halt(7);
    }
  }

  /** Reports the names of the environment variables it was given. */
  static final class Env {
    public static void main(String[] args) throws Exception {
      Files.writeString(report(), String.join("\n", System.getenv().keySet()));
      Runtime.getRuntime().halt(7);
    }
  }

  /** A runaway scan: holds more than its heap allows. */
  static final class Hog {
    public static void main(String[] args) {
      List<long[]> held = new ArrayList<>();
      while (true) {
        held.add(new long[1 << 20]);
      }
    }
  }

  /** Prints far more than is kept, then fails. */
  static final class Chatty {
    public static void main(String[] args) {
      String line = "x".repeat(99);
      for (int i = 0; i < 20_000; i++) {
        System.out.println(line);
      }
      System.err.println("the last words");
      System.exit(ScanChild.FAILED);
    }
  }
}
