package dev.docswatcher.engine;

import java.util.Set;

/** Path classification shared by the confidence rule. */
final class Paths {

  private static final Set<String> TEST_SEGMENTS = Set.of("test", "tests", "__tests__", "spec", "specs", "fixtures");

  private Paths() {}

  static boolean isDocumentation(String path) {
    String lower = path.toLowerCase();
    return lower.endsWith(".md") || lower.endsWith(".rst") || lower.endsWith(".txt") || lower.endsWith(".adoc");
  }

  static boolean isTest(String path) {
    String[] segments = path.split("/");
    for (int i = 0; i < segments.length - 1; i++) {
      if (TEST_SEGMENTS.contains(segments[i])) return true;
    }
    String file = segments[segments.length - 1];
    return file.contains(".test.") || file.contains(".spec.") || file.contains("_test.") || file.endsWith("Test.java");
  }

  static boolean isDocOrTest(String path) {
    return isDocumentation(path) || isTest(path);
  }

  static String language(String path) {
    int dot = path.lastIndexOf('.');
    if (dot < 0) return null;
    return switch (path.substring(dot + 1)) {
      case "java" -> "java";
      case "py" -> "python";
      case "ts" -> "typescript";
      case "tsx" -> "tsx";
      case "js", "jsx", "mjs", "cjs" -> "javascript";
      case "go" -> "go";
      default -> null;
    };
  }
}
