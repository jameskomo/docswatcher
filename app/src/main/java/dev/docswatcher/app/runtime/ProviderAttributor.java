package dev.docswatcher.app.runtime;

import dev.docswatcher.app.model.ProviderDoc;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Turns an observed host and path into a provider id, and then into the id of a
 * contract the scanner already produced.
 *
 * <p>This is best effort and says so. It reads the same `base_urls` the knowledge base
 * declares, so adding a provider teaches detection and attribution at once, but a host
 * is weaker evidence than a parsed call site. A customer who routes provider traffic
 * through their own gateway will present their own hostname and will not attribute.
 */
public final class ProviderAttributor {

  private final List<Rule> rules;

  public ProviderAttributor(List<ProviderDoc> providers) {
    List<Rule> parsed = new ArrayList<>();
    for (ProviderDoc p : providers) {
      for (String base : p.baseUrls() == null ? List.<String>of() : p.baseUrls()) {
        Rule.parse(p.id(), base).ifPresent(parsed::add);
      }
    }
    // Longest host pattern first, so a specific subdomain beats a bare apex.
    parsed.sort((a, b) -> Integer.compare(b.host().length(), a.host().length()));
    this.rules = List.copyOf(parsed);
  }

  /** The provider whose base URL best matches this call, if any. */
  public Optional<String> provider(HttpCall call) {
    for (Rule r : rules) {
      if (r.matches(call.host(), call.path())) {
        return Optional.of(r.provider());
      }
    }
    return Optional.empty();
  }

  /**
   * Candidate contract ids for this call, most specific first.
   *
   * <p>Endpoint keys are not uniform across providers. Most use the URL path alone,
   * but Twilio splits one product per subdomain while reusing paths, so its keys carry
   * the subdomain. Rather than special casing a provider here, every plausible spelling
   * is offered and the caller keeps whichever the scanner actually found. Attribution
   * therefore follows the knowledge base instead of predicting it.
   */
  public List<String> candidateContractIds(String provider, HttpCall call) {
    Set<String> paths = new LinkedHashSet<>();
    paths.add(call.path());
    String label = firstHostLabel(call.host());
    if (label != null && !call.path().startsWith("/" + label + "/")) {
      paths.add("/" + label + call.path());
    }
    List<String> ids = new ArrayList<>();
    for (String path : paths) {
      ids.add(provider + ":endpoint:" + call.method() + " " + path);
      ids.add(provider + ":endpoint:ANY " + path);
    }
    return ids;
  }

  private static String firstHostLabel(String host) {
    int dot = host.indexOf('.');
    return dot <= 0 ? null : host.substring(0, dot);
  }

  /**
   * One base URL, reduced to a host pattern and an optional path prefix.
   *
   * @param wildcard true when the declared host began with a template such as
   *     {@code https://{shop}.myshopify.com}, in which case any single label matches.
   */
  record Rule(String provider, String host, String pathPrefix, boolean wildcard) {

    static Optional<Rule> parse(String provider, String base) {
      if (base == null || base.isBlank()) {
        return Optional.empty();
      }
      try {
        String cleaned = base.trim();
        boolean wildcard = cleaned.contains("{");
        // URI cannot parse a brace, so the template label becomes a placeholder first.
        String parseable = cleaned.replaceAll("\\{[^}]*}", "wildcard");
        URI uri = URI.create(parseable);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
          return Optional.empty();
        }
        host = host.toLowerCase();
        if (wildcard) {
          // Keep only the fixed remainder, for example myshopify.com.
          int dot = host.indexOf('.');
          host = dot > 0 ? host.substring(dot + 1) : host;
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (path.endsWith("/")) {
          path = path.substring(0, path.length() - 1);
        }
        return Optional.of(new Rule(provider, host, path, wildcard));
      } catch (IllegalArgumentException e) {
        return Optional.empty();
      }
    }

    boolean matches(String observedHost, String observedPath) {
      boolean hostOk = observedHost.equals(host) || observedHost.endsWith("." + host);
      if (!hostOk) {
        return false;
      }
      // A base URL with a path only claims calls under that path, which is what keeps
      // slack.com/api from claiming every request to slack.com.
      return pathPrefix.isEmpty() || observedPath.equals(pathPrefix) || observedPath.startsWith(pathPrefix + "/");
    }
  }
}
