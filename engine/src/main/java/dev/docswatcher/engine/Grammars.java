package dev.docswatcher.engine;

import org.treesitter.TSLanguage;
import org.treesitter.TreeSitterCSharp;
import org.treesitter.TreeSitterGo;
import org.treesitter.TreeSitterJava;
import org.treesitter.TreeSitterJavascript;
import org.treesitter.TreeSitterPhp;
import org.treesitter.TreeSitterPython;
import org.treesitter.TreeSitterRuby;
import org.treesitter.TreeSitterTsx;
import org.treesitter.TreeSitterTypescript;
import java.util.HashMap;
import java.util.Map;

/** Lazily created tree-sitter languages. Not thread-safe; the engine is single-threaded per scan. */
final class Grammars {

  private final Map<String, TSLanguage> cache = new HashMap<>();

  TSLanguage get(String language) {
    return cache.computeIfAbsent(language, l -> switch (l) {
      case "java" -> new TreeSitterJava();
      case "python" -> new TreeSitterPython();
      case "typescript" -> new TreeSitterTypescript();
      case "tsx" -> new TreeSitterTsx();
      case "javascript" -> new TreeSitterJavascript();
      case "go" -> new TreeSitterGo();
      case "ruby" -> new TreeSitterRuby();
      case "php" -> new TreeSitterPhp();
      case "csharp" -> new TreeSitterCSharp();
      default -> throw new IllegalArgumentException("No grammar for language " + l);
    });
  }

  /** The detector language a file language maps to. tsx files run typescript rules. */
  static String ruleLanguage(String fileLanguage) {
    return "tsx".equals(fileLanguage) ? "typescript" : fileLanguage;
  }
}
