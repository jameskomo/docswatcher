package dev.docswatcher.engine;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

final class TestSupport {

  static final LocalDate TODAY = LocalDate.of(2026, 9, 18);

  private TestSupport() {}

  static Path knowledgeDir() {
    Path p = Path.of("..", "knowledge").toAbsolutePath().normalize();
    if (!p.resolve("providers").toFile().isDirectory()) throw new IllegalStateException("knowledge dir not found at " + p);
    return p;
  }

  static Knowledge knowledge() {
    return Knowledge.load(knowledgeDir());
  }

  static SourceFile file(String path, String text) {
    return new SourceFile(path, text);
  }

  static List<Contract> scan(Knowledge k, SourceFile... files) {
    return new Engine(k).scanFiles(List.of(files));
  }
}
