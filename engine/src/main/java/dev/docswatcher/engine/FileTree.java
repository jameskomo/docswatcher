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
import java.util.List;
import java.util.Set;

/** Deterministic, filtered walk of a repository checkout. */
final class FileTree {

  static final Set<String> SKIPPED_DIRS = Set.of("node_modules", "target", "dist", "build", ".git", "vendor", ".venv");
  static final long MAX_BYTES = 1024 * 1024;

  final List<SourceFile> files = new ArrayList<>();
  int skipped;

  static FileTree read(Path root) {
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
    for (Path p : paths) {
      try {
        if (Files.size(p) > MAX_BYTES) {
          tree.skipped++;
          continue;
        }
        byte[] bytes = Files.readAllBytes(p);
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
