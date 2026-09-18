package dev.docwatcher.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/** Turns YAML trees into knowledge records. Tolerant of missing optional fields. */
final class Loaders {

  private Loaders() {}

  static Provider provider(JsonNode profile, JsonNode det) {
    List<Provider.Manifest> manifests = new ArrayList<>();
    List<Provider.Literal> literals = new ArrayList<>();
    List<Provider.Callsite> callsites = new ArrayList<>();
    if (det != null) {
      for (JsonNode m : array(det, "manifests")) {
        manifests.add(new Provider.Manifest(Yaml.str(m, "ecosystem"), Yaml.str(m, "package")));
      }
      for (JsonNode l : array(det, "literals")) {
        literals.add(new Provider.Literal(
            Yaml.str(l, "id"),
            Yaml.str(l, "kind"),
            Yaml.strings(l, "files"),
            Yaml.strings(l, "exclude"),
            Yaml.str(l, "pattern"),
            Yaml.str(l, "key"),
            orDefault(Yaml.str(l, "confidence"), "medium")));
      }
      for (JsonNode c : array(det, "callsites")) {
        JsonNode mt = c.get("maps_to");
        callsites.add(new Provider.Callsite(
            Yaml.str(c, "id"),
            Yaml.str(c, "language"),
            Yaml.str(c, "kind"),
            Yaml.str(c, "requires"),
            Yaml.str(c, "query"),
            Yaml.str(c, "key"),
            mt == null || mt.isNull() ? null : new Provider.MapsTo(Yaml.str(mt, "kind"), Yaml.str(mt, "key")),
            orDefault(Yaml.str(c, "confidence"), "high")));
      }
    }
    return new Provider(
        Yaml.str(profile, "id"),
        Yaml.str(profile, "name"),
        Yaml.str(profile, "homepage"),
        Yaml.str(profile, "docs"),
        Yaml.str(profile, "changelog"),
        Yaml.strings(profile, "base_urls"),
        Yaml.strings(profile, "sdk_languages"),
        Yaml.str(profile, "deprecation_policy"),
        new Provider.Detectors(manifests, literals, callsites));
  }

  static Change change(JsonNode n, String file) {
    List<Change.Affect> affects = new ArrayList<>();
    for (JsonNode a : array(n, "affects")) {
      affects.add(new Change.Affect(Yaml.str(a, "kind"), Yaml.str(a, "match")));
    }
    List<Change.Source> sources = new ArrayList<>();
    for (JsonNode s : array(n, "sources")) {
      sources.add(new Change.Source(Yaml.str(s, "kind"), Yaml.str(s, "url"), Yaml.str(s, "observed"), Yaml.str(s, "note")));
    }
    JsonNode m = n.get("migration");
    Change.Migration migration = m == null || m.isNull()
        ? null
        : new Change.Migration(Yaml.str(m, "replacement"), Yaml.str(m, "guide"), Yaml.str(m, "effort"), trim(Yaml.str(m, "notes")));
    return new Change(
        Yaml.str(n, "id"),
        Yaml.str(n, "provider"),
        Yaml.str(n, "kind"),
        Yaml.str(n, "severity"),
        Yaml.str(n, "title"),
        trim(Yaml.str(n, "summary")),
        affects,
        Yaml.str(n, "announced"),
        Yaml.str(n, "effective"),
        sources,
        migration,
        Yaml.str(n, "status"),
        file);
  }

  private static Iterable<JsonNode> array(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v != null && v.isArray() ? v : List.of();
  }

  private static String orDefault(String s, String d) {
    return s == null ? d : s;
  }

  private static String trim(String s) {
    return s == null ? null : s.strip();
  }
}
