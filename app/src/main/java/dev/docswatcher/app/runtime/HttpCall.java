package dev.docswatcher.app.runtime;

/**
 * One outbound HTTP call read off a span, reduced to the few fields we keep.
 *
 * <p>The query string is dropped before this record is built. Query strings carry
 * identifiers, tokens and search terms, and none of that is needed to tell you an
 * endpoint is going away.
 */
public record HttpCall(String host, String method, String path, String deprecation, String sunset) {

  public boolean hasProviderNotice() {
    return deprecation != null || sunset != null;
  }
}
