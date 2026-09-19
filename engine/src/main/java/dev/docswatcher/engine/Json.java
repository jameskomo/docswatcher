package dev.docswatcher.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.Map;

/**
 * Deterministic JSON. Output is byte-identical to JavaScript's JSON.stringify(value, null, 2)
 * so the TypeScript engine can be compared against this one without normalisation.
 */
public final class Json {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Json() {}

  public static String write(Object o) {
    JsonNode node = MAPPER.valueToTree(o);
    StringBuilder sb = new StringBuilder();
    writeNode(node, sb, 0);
    sb.append('\n');
    return sb.toString();
  }

  public static <T> T read(String s, Class<T> t) {
    try {
      return MAPPER.readValue(s, t);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid JSON for " + t.getSimpleName() + ": " + e.getOriginalMessage(), e);
    }
  }

  public static <T> T read(String s, com.fasterxml.jackson.core.type.TypeReference<T> t) {
    try {
      return MAPPER.readValue(s, t);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid JSON: " + e.getOriginalMessage(), e);
    }
  }

  public static JsonNode tree(String s) {
    try {
      return MAPPER.readTree(s);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid JSON: " + e.getOriginalMessage(), e);
    }
  }

  private static void writeNode(JsonNode n, StringBuilder sb, int depth) {
    switch (n.getNodeType()) {
      case OBJECT -> {
        if (n.isEmpty()) {
          sb.append("{}");
          return;
        }
        sb.append("{\n");
        Iterator<Map.Entry<String, JsonNode>> it = n.fields();
        while (it.hasNext()) {
          Map.Entry<String, JsonNode> e = it.next();
          indent(sb, depth + 1);
          writeString(e.getKey(), sb);
          sb.append(": ");
          writeNode(e.getValue(), sb, depth + 1);
          if (it.hasNext()) sb.append(',');
          sb.append('\n');
        }
        indent(sb, depth);
        sb.append('}');
      }
      case ARRAY -> {
        if (n.isEmpty()) {
          sb.append("[]");
          return;
        }
        sb.append("[\n");
        for (int i = 0; i < n.size(); i++) {
          indent(sb, depth + 1);
          writeNode(n.get(i), sb, depth + 1);
          if (i < n.size() - 1) sb.append(',');
          sb.append('\n');
        }
        indent(sb, depth);
        sb.append(']');
      }
      case STRING -> writeString(n.textValue(), sb);
      case NULL, MISSING -> sb.append("null");
      case BOOLEAN -> sb.append(n.booleanValue());
      case NUMBER -> sb.append(n.numberValue().toString());
      default -> throw new IllegalStateException("Unsupported node " + n.getNodeType());
    }
  }

  private static void indent(StringBuilder sb, int depth) {
    sb.append("  ".repeat(depth));
  }

  /** Escapes exactly as JSON.stringify does. */
  static void writeString(String s, StringBuilder sb) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20 || (Character.isSurrogate(c) && !validSurrogatePair(s, i))) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }

  private static boolean validSurrogatePair(String s, int i) {
    char c = s.charAt(i);
    if (Character.isHighSurrogate(c)) {
      return i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
    }
    return i > 0 && Character.isHighSurrogate(s.charAt(i - 1));
  }
}
