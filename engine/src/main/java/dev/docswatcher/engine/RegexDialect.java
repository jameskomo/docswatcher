package dev.docswatcher.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Rejects regex constructs outside the subset Java and JavaScript both support identically. */
public final class RegexDialect {

  private RegexDialect() {}

  public static List<String> problems(String pattern) {
    List<String> out = new ArrayList<>();
    try {
      Pattern.compile(pattern);
    } catch (PatternSyntaxException e) {
      out.add("does not compile: " + e.getDescription());
      return out;
    }
    if (Pattern.compile("[*+?}]\\+").matcher(pattern).find()) out.add("possessive quantifier");
    if (pattern.contains("\\h")) out.add("\\h is Java-only");
    if (pattern.contains("\\R") || pattern.contains("\\X") || pattern.contains("\\Q") || pattern.contains("\\E")) out.add("\\R, \\X, \\Q, \\E are Java-only");
    if (Pattern.compile("\\\\[AZzG]").matcher(pattern).find()) out.add("\\A, \\Z, \\z, \\G are Java-only");
    if (Pattern.compile("\\\\p\\{(?!L\\})").matcher(pattern).find()) out.add("only \\p{L} is allowed");
    if (Pattern.compile("\\(\\?[a-zA-Z]+[-:)]").matcher(pattern).find()) out.add("inline flags are not allowed");
    if (pattern.contains("(?<=") || pattern.contains("(?<!")) out.add("lookbehind is not allowed");
    return out;
  }
}
