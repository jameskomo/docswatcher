package dev.docswatcher.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Deterministic, filtered walk of a repository checkout. */
final class FileTree {

  // .docswatcher holds a repository's own API records (OwnKnowledge): knowledge, not code to scan.
  static final Set<String> SKIPPED_DIRS = Set.of("node_modules", "target", "dist", "build", ".git", "vendor", ".venv", ".docswatcher");
  static final long MAX_BYTES = 1024 * 1024;

  final List<SourceFile> files = new ArrayList<>();
  int skipped;
  /** Set when a whole-scan limit stopped the read; null when every file was considered. */
  Inventory.Incomplete incomplete;

  static FileTree read(Path root) {
    return read(root, List.of());
  }

  /**
   * Reads every file not excluded by a .gitignore, the root .docswatcherignore or {@code exclude}
   * (docs/18-excluding-paths.md). Excluded files are counted as skipped and never read.
   */
  static FileTree read(Path root, List<String> exclude) {
    return read(root, exclude, ScanLimits.DEFAULT, () -> false);
  }

  /**
   * As {@link #read(Path, List)}, stopping before the first file that would pass {@code limits},
   * or once {@code expired} says the scan is out of time. The files after the stop are counted in
   * {@link #incomplete}, never in {@link #skipped}.
   */
  static FileTree read(Path root, List<String> exclude, ScanLimits limits, BooleanSupplier expired) {
    FileTree tree = new FileTree();
    List<Path> paths = new ArrayList<>();
    try {
      Files.walkFileTree(root, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
          if (!dir.equals(root) && SKIPPED_DIRS.contains(dir.getFileName().toString())) {
            return FileVisitResult.SKIP_SUBTREE;
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          if (attrs.isRegularFile()) paths.add(file);
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot walk " + root, e);
    }
    paths.sort((a, b) -> rel(root, a).compareTo(rel(root, b)));

    // Ignore files are small and few: read them first, so nothing they exclude is ever opened.
    Map<String, String> ignoreFiles = new HashMap<>();
    for (Path p : paths) {
      String rel = rel(root, p);
      if (!Ignore.isIgnoreFile(rel)) continue;
      try {
        ignoreFiles.put(rel, Files.readString(p, StandardCharsets.UTF_8));
      } catch (IOException | UncheckedIOException e) {
        // An unreadable ignore file excludes nothing, as git would treat it.
      }
    }
    Ignore ignore = Ignore.of(ignoreFiles, exclude);

    long bytesRead = 0;
    for (int i = 0; i < paths.size(); i++) {
      Path p = paths.get(i);
      if (ignore.ignored(rel(root, p))) {
        tree.skipped++;
        continue;
      }
      try {
        long size = Files.size(p);
        if (size > MAX_BYTES) {
          tree.skipped++;
          continue;
        }
        String limit = tree.files.size() >= limits.maxFiles() ? ScanLimits.MAX_FILES
            : bytesRead + size > limits.maxBytes() ? ScanLimits.MAX_BYTES
            : expired.getAsBoolean() ? ScanLimits.MAX_DURATION
            : null;
        if (limit != null) {
          int notRead = 0;
          for (int j = i; j < paths.size(); j++) if (!ignore.ignored(rel(root, paths.get(j)))) notRead++;
          long max = switch (limit) {
            case ScanLimits.MAX_FILES -> limits.maxFiles();
            case ScanLimits.MAX_BYTES -> limits.maxBytes();
            default -> limits.maxDuration().toMillis();
          };
          tree.incomplete = new Inventory.Incomplete(limit, max, notRead);
          break;
        }
        byte[] bytes = Files.readAllBytes(p);
        bytesRead += bytes.length;
        if (isBinary(bytes)) {
          tree.skipped++;
          continue;
        }
        tree.files.add(new SourceFile(rel(root, p), new String(bytes, StandardCharsets.UTF_8)));
      } catch (IOException e) {
        tree.skipped++;
      }
    }
    return tree;
  }

  private static String rel(Path root, Path p) {
    return root.relativize(p).toString().replace('\\', '/');
  }

  /** A NUL byte in the first 8 KB marks a binary file. */
  static boolean isBinary(byte[] bytes) {
    int n = Math.min(bytes.length, 8192);
    for (int i = 0; i < n; i++) if (bytes[i] == 0) return true;
    return false;
  }
}
