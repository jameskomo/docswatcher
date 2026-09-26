package dev.docswatcher.app.engine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs a command to completion under a wall-clock limit. Past the limit the process and everything
 * it started are killed. Its standard output and error are read together and only the last
 * {@code keepBytes} are kept, so a child that prints without end costs a fixed amount of memory.
 */
final class ChildProcess {

  /**
   * @param exitCode the process's exit status; after a timeout, whatever killing it produced
   * @param output the tail of its combined output, decoded as UTF-8
   */
  record Result(int exitCode, boolean timedOut, String output, Duration elapsed) {}

  private ChildProcess() {}

  /**
   * @param environment the whole environment of the child; nothing of this process's is inherited
   */
  static Result run(List<String> command, Map<String, String> environment, Path workDir, Duration timeout, int keepBytes)
      throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(true);
    builder.environment().clear();
    builder.environment().putAll(environment);
    long start = System.nanoTime();
    Process process = builder.start();
    Tail tail = new Tail(keepBytes);
    Thread reader = Thread.ofVirtual().name("scan-process-output").start(() -> tail.drain(process.getInputStream()));
    boolean timedOut = false;
    try {
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        timedOut = true;
        killTree(process);
      }
    } catch (InterruptedException e) {
      // The app is shutting down: the scan must not outlive it.
      killTree(process);
      throw e;
    }
    // The pipe closes when the process and anything that inherited it are gone.
    reader.join(Duration.ofSeconds(5));
    return new Result(process.isAlive() ? -1 : process.exitValue(), timedOut, tail.text(), Duration.ofNanos(System.nanoTime() - start));
  }

  static void killTree(Process process) throws InterruptedException {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
    process.waitFor(10, TimeUnit.SECONDS);
  }

  /** The last bytes of a stream, in a ring buffer. */
  static final class Tail {
    private final byte[] ring;
    private long written;

    Tail(int size) {
      this.ring = new byte[size];
    }

    void drain(InputStream in) {
      byte[] buf = new byte[8192];
      try (in) {
        int n;
        while ((n = in.read(buf)) != -1) {
          append(buf, n);
        }
      } catch (IOException ignored) {
        // The process was killed; what was read is what there is.
      }
    }

    synchronized void append(byte[] buf, int n) {
      for (int i = 0; i < n; i++) {
        ring[(int) (written++ % ring.length)] = buf[i];
      }
    }

    synchronized String text() {
      if (written <= ring.length) {
        return new String(ring, 0, (int) written, StandardCharsets.UTF_8);
      }
      int start = (int) (written % ring.length);
      byte[] ordered = new byte[ring.length];
      System.arraycopy(ring, start, ordered, 0, ring.length - start);
      System.arraycopy(ring, 0, ordered, ring.length - start, start);
      return "[... " + (written - ring.length) + " bytes dropped ...]\n" + new String(ordered, StandardCharsets.UTF_8);
    }
  }
}
