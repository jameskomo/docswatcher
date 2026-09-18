package dev.docwatcher.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/** An external contract the scanned code depends on. */
@JsonPropertyOrder({"id", "provider", "kind", "key", "confidence", "evidence", "context"})
public record Contract(
    String id,
    String provider,
    String kind,
    String key,
    String confidence,
    List<Evidence> evidence,
    @JsonInclude(JsonInclude.Include.NON_NULL) Context context) {

  public static String id(String provider, String kind, String key) {
    return provider + ":" + kind + ":" + key;
  }
}
