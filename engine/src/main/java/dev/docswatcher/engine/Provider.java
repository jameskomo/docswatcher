package dev.docswatcher.engine;

import java.util.List;

/** A provider profile plus its detector table. */
public record Provider(
    String id,
    String name,
    String homepage,
    String docs,
    String changelog,
    List<String> baseUrls,
    List<String> sdkLanguages,
    String deprecationPolicy,
    Detectors detectors) {

  public record Detectors(List<Manifest> manifests, List<Literal> literals, List<Callsite> callsites) {}

  public record Manifest(String ecosystem, String pkg) {}

  public record Literal(
      String id,
      String kind,
      List<String> files,
      List<String> exclude,
      String pattern,
      String key,
      String confidence) {}

  public record Callsite(
      String id,
      String language,
      String kind,
      String requires,
      String query,
      String key,
      MapsTo mapsTo,
      String confidence) {}

  public record MapsTo(String kind, String key) {}
}
