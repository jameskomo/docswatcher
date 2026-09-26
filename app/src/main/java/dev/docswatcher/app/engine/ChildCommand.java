package dev.docswatcher.app.engine;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The command line that starts a main class of this application in a new process, from whatever
 * this process was started from, so the scan process needs nothing installed beside the app:
 *
 * <ul>
 *   <li>a plain classpath (tests, an IDE, an extracted jar whose manifest lists its libraries):
 *       {@code java -cp <this classpath> <main class>};
 *   <li>the Spring Boot jar ({@code java -jar app.jar}), whose classes the system class loader
 *       cannot see: the same jar again, with {@link ScanChild#COMMAND} as the first argument, which
 *       {@code DocsWatcherApplication.main} hands to {@link ScanChild} before Spring starts;
 *   <li>a native image: the same executable, with the same first argument.
 * </ul>
 */
final class ChildCommand {

  private ChildCommand() {}

  static List<String> forMain(String mainClass, List<String> jvmOptions, List<String> args) {
    List<String> command = new ArrayList<>();
    if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
      requireScanChild(mainClass, "a native image");
      command.add(ProcessHandle.current().info().command().orElseThrow(
          () -> new IllegalStateException("The path of this executable is unknown, so no scan process can start")));
      // A native image takes heap options at run time and rejects the JVM's other options.
      jvmOptions.stream().filter(o -> o.startsWith("-Xmx") || o.startsWith("-Xms")).forEach(command::add);
      command.add(ScanChild.COMMAND);
      command.addAll(args);
      return command;
    }
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.addAll(jvmOptions);
    String classpath = absoluteClasspath(System.getProperty("java.class.path", ""));
    if (visibleOnClasspath(mainClass)) {
      command.addAll(List.of("-cp", classpath, mainClass));
    } else {
      requireScanChild(mainClass, "the application jar");
      String launched = launchedAs();
      if (launched.endsWith(".jar")) {
        command.addAll(List.of("-jar", absolute(launched)));
      } else {
        command.addAll(List.of("-cp", classpath, launched));
      }
      command.add(ScanChild.COMMAND);
    }
    command.addAll(args);
    return command;
  }

  private static boolean visibleOnClasspath(String mainClass) {
    try {
      Class.forName(mainClass, false, ClassLoader.getSystemClassLoader());
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  /** The jar or main class this JVM was started with, as the launcher recorded it. */
  private static String launchedAs() {
    String command = System.getProperty("sun.java.command", "").strip();
    if (command.isEmpty()) {
      throw new IllegalStateException("How this JVM was started is unknown, so no scan process can start");
    }
    // A path with spaces would be split here; the image's paths have none, and the probe at startup
    // reports it if that ever changes.
    return command.split(" ")[0];
  }

  private static void requireScanChild(String mainClass, String from) {
    if (!ScanChild.class.getName().equals(mainClass)) {
      throw new IllegalStateException("Only the scan process can be started from " + from + ", not " + mainClass);
    }
  }

  /** The child starts in its own directory, so relative entries are resolved against this one's. */
  private static String absoluteClasspath(String classpath) {
    return Arrays.stream(classpath.split(File.pathSeparator))
        .filter(e -> !e.isEmpty())
        .map(ChildCommand::absolute)
        .collect(Collectors.joining(File.pathSeparator));
  }

  private static String absolute(String entry) {
    if (entry.endsWith("*")) {
      return Path.of(entry.substring(0, entry.length() - 1)).toAbsolutePath() + File.separator + "*";
    }
    return Path.of(entry).toAbsolutePath().toString();
  }
}
