package dev.docwatcher.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Collects evidence per contract id, then finalises contracts with confidence, context, and ordering. */
final class Accumulator {

  private static final List<String> CONFIDENCE_ORDER = List.of("low", "medium", "high");

  private static final class Pending {
    final String provider;
    final String kind;
    final String key;
    final TreeSet<Evidence> evidence = new TreeSet<>(Evidence.ORDER);
    String confidence = "low";

    Pending(String provider, String kind, String key) {
      this.provider = provider;
      this.kind = kind;
      this.key = key;
    }
  }

  private final Map<String, Pending> pending = new TreeMap<>();
  /** provider id -> manifest hits (ecosystem, package, version). */
  final Map<String, List<Context.Sdk>> manifestHits = new TreeMap<>();

  void add(String provider, String kind, String key, Evidence e, String ruleConfidence) {
    String id = Contract.id(provider, kind, key);
    Pending p = pending.computeIfAbsent(id, x -> new Pending(provider, kind, key));
    p.evidence.add(e);
    if (CONFIDENCE_ORDER.indexOf(ruleConfidence) > CONFIDENCE_ORDER.indexOf(p.confidence)) {
      p.confidence = ruleConfidence;
    }
  }

  void manifestHit(String provider, Context.Sdk sdk) {
    manifestHits.computeIfAbsent(provider, x -> new ArrayList<>()).add(sdk);
  }

  boolean hasManifest(String provider, String pkg) {
    List<Context.Sdk> hits = manifestHits.get(provider);
    if (hits == null) return false;
    if (pkg == null) return !hits.isEmpty();
    return hits.stream().anyMatch(h -> h.pkg().equals(pkg));
  }

  List<Contract> finish() {
    Map<String, Integer> apiVersionCount = new TreeMap<>();
    Map<String, String> apiVersion = new TreeMap<>();
    for (Pending p : pending.values()) {
      if ("api_version".equals(p.kind)) {
        apiVersionCount.merge(p.provider, 1, Integer::sum);
        apiVersion.put(p.provider, p.key);
      }
    }
    List<Contract> out = new ArrayList<>();
    for (Map.Entry<String, Pending> e : pending.entrySet()) {
      Pending p = e.getValue();
      String confidence = p.confidence;
      if (p.evidence.stream().allMatch(ev -> !"manifest".equals(ev.layer()) && Paths.isDocOrTest(ev.path()))) confidence = "low";
      List<Context.Sdk> hits = manifestHits.get(p.provider);
      Context.Sdk sdk = hits != null && hits.size() == 1 ? hits.get(0) : null;
      String av = apiVersionCount.getOrDefault(p.provider, 0) == 1 ? apiVersion.get(p.provider) : null;
      Context ctx = new Context(sdk, av);
      out.add(new Contract(e.getKey(), p.provider, p.kind, p.key, confidence,
          List.copyOf(p.evidence), ctx.isEmpty() ? null : ctx));
    }
    return out;
  }
}
