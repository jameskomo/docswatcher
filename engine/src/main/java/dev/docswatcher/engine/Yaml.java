package dev.docswatcher.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/** YAML loading into JsonNode trees. Dates stay strings. */
final class Yaml {

  private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());

  private Yaml() {}

  static JsonNode read(InputStream in, String what) {
    try (in) {
      JsonNode n = MAPPER.readTree(in);
      return n == null ? MAPPER.nullNode() : n;
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read YAML " + what, e);
    }
  }

  static String str(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? null : v.asText();
  }

  static List<String> strings(JsonNode n, String field) {
    JsonNode v = n.get(field);
    List<String> out = new ArrayList<>();
    if (v != null && v.isArray()) v.forEach(x -> out.add(x.asText()));
    return out;
  }
}
