package dev.docswatcher.app.runtime;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Reads an outbound HTTP call out of one OTLP span.
 *
 * <p>Attribute names are read in both the current semantic conventions and the older
 * ones, because a customer's agent version is not ours to choose and a rename should
 * not silently stop attribution. Everything here tolerates a missing or oddly shaped
 * field by returning empty rather than throwing: one strange span must never fail a
 * customer's telemetry export.
 */
final class SpanReader {

  /** OTLP SPAN_KIND_CLIENT. An inbound request to the customer's own service is not ours to record. */
  private static final int SPAN_KIND_CLIENT = 3;

  private static final List<String> METHOD_KEYS = List.of("http.request.method", "http.method");
  private static final List<String> URL_KEYS = List.of("url.full", "http.url");
  private static final List<String> HOST_KEYS = List.of("server.address", "net.peer.name", "http.host");
  private static final List<String> PATH_KEYS = List.of("url.path", "http.target", "http.route");

  // Captured headers arrive as http.response.header.<lowercased name>. Some agents
  // have shipped the plural form, so both are accepted.
  private static final List<String> DEPRECATION_KEYS =
      List.of("http.response.header.deprecation", "http.response.headers.deprecation");
  private static final List<String> SUNSET_KEYS =
      List.of("http.response.header.sunset", "http.response.headers.sunset");

  private SpanReader() {}

  static Optional<HttpCall> read(JsonNode span) {
    if (span == null || !span.isObject()) {
      return Optional.empty();
    }
    if (!isClientSpan(span)) {
      return Optional.empty();
    }
    Attributes attrs = Attributes.of(span.get("attributes"));

    String method = attrs.first(METHOD_KEYS).map(m -> m.toUpperCase()).orElse(null);
    String url = attrs.first(URL_KEYS).orElse(null);
    String host = attrs.first(HOST_KEYS).orElse(null);
    String path = attrs.first(PATH_KEYS).orElse(null);

    // A full URL carries both host and path, so it fills whichever is missing.
    if (url != null) {
      try {
        URI uri = URI.create(url.trim());
        if (host == null && uri.getHost() != null) {
          host = uri.getHost();
        }
        if (path == null && uri.getRawPath() != null && !uri.getRawPath().isBlank()) {
          path = uri.getRawPath();
        }
      } catch (IllegalArgumentException e) {
        // A malformed URL is not a reason to drop an otherwise usable span.
      }
    }
    if (host == null || host.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new HttpCall(
        normaliseHost(host),
        method == null || method.isBlank() ? "ANY" : method,
        normalisePath(path),
        attrs.first(DEPRECATION_KEYS).orElse(null),
        attrs.first(SUNSET_KEYS).orElse(null)));
  }

  /** Absent kind is treated as a client span: some exporters omit it, and the attributes still tell us. */
  private static boolean isClientSpan(JsonNode span) {
    JsonNode kind = span.get("kind");
    if (kind == null || kind.isNull()) {
      return true;
    }
    if (kind.isNumber()) {
      return kind.asInt() == SPAN_KIND_CLIENT;
    }
    return "SPAN_KIND_CLIENT".equals(kind.asString());
  }

  private static String normaliseHost(String host) {
    String h = host.trim().toLowerCase();
    int colon = h.lastIndexOf(':');
    // Strip a port, but leave IPv6 literals alone.
    if (colon > 0 && h.indexOf(']') < 0 && h.indexOf(':') == colon) {
      h = h.substring(0, colon);
    }
    return h;
  }

  /** Drops the query string and normalises the shape, so one endpoint is one row. */
  private static String normalisePath(String path) {
    if (path == null || path.isBlank()) {
      return "/";
    }
    String p = path.trim();
    int cut = p.indexOf('?');
    if (cut >= 0) {
      p = p.substring(0, cut);
    }
    cut = p.indexOf('#');
    if (cut >= 0) {
      p = p.substring(0, cut);
    }
    if (!p.startsWith("/")) {
      p = "/" + p;
    }
    if (p.length() > 1 && p.endsWith("/")) {
      p = p.substring(0, p.length() - 1);
    }
    return p;
  }

  /** An OTLP attribute list, read by key. */
  record Attributes(JsonNode list) {

    static Attributes of(JsonNode list) {
      return new Attributes(list);
    }

    Optional<String> first(List<String> keys) {
      for (String key : keys) {
        Optional<String> v = get(key);
        if (v.isPresent()) {
          return v;
        }
      }
      return Optional.empty();
    }

    Optional<String> get(String key) {
      if (list == null || !list.isArray()) {
        return Optional.empty();
      }
      for (JsonNode attr : list) {
        JsonNode k = attr.get("key");
        if (k != null && key.equals(k.asString())) {
          return value(attr.get("value"));
        }
      }
      return Optional.empty();
    }

    /**
     * OTLP wraps every value in a typed envelope. Captured headers are arrays, because a
     * header can repeat, and we keep the values joined rather than only the first: a
     * Sunset header that appears twice with different dates is worth seeing in full.
     */
    private static Optional<String> value(JsonNode value) {
      if (value == null || value.isNull()) {
        return Optional.empty();
      }
      JsonNode s = value.get("stringValue");
      if (s != null && !s.isNull()) {
        return text(s.asString());
      }
      JsonNode arr = value.get("arrayValue");
      if (arr != null && arr.get("values") != null && arr.get("values").isArray()) {
        StringBuilder joined = new StringBuilder();
        for (JsonNode item : arr.get("values")) {
          value(item).ifPresent(v -> joined.append(joined.isEmpty() ? "" : ", ").append(v));
        }
        return text(joined.toString());
      }
      for (String numeric : List.of("intValue", "doubleValue", "boolValue")) {
        JsonNode n = value.get(numeric);
        if (n != null && !n.isNull()) {
          return text(n.asString());
        }
      }
      return Optional.empty();
    }

    private static Optional<String> text(String v) {
      return v == null || v.isBlank() ? Optional.empty() : Optional.of(v.trim());
    }
  }
}
