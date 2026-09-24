package dev.docswatcher.engine;

import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Knowledge base validation. Errors fail the build; warnings are reported. */
public final class Validator {

  public record Report(List<String> errors, List<String> warnings) {
    public boolean ok() {
      return errors.isEmpty();
    }
  }

  static final Set<String> CONTRACT_KINDS = Set.of("sdk_package", "sdk_method", "endpoint", "model", "api_version", "graphql_operation", "webhook");
  static final Set<String> CHANGE_KINDS = Set.of("sunset", "retired", "behavior_change", "field_change", "limit_change");
  static final Set<String> SEVERITIES = Set.of("breaking", "warning", "info");
  static final Set<String> STATUSES = Set.of("draft", "active", "withdrawn", "expired");
  static final Set<String> SOURCE_KINDS = Set.of("changelog", "openapi_diff", "email", "sunset_header", "deprecation_header", "provider_page");
  static final Set<String> EFFORTS = Set.of("small", "medium", "large");
  static final Set<String> CONFIDENCES = Set.of("high", "medium", "low");
  static final Set<String> ECOSYSTEMS = Set.of("npm", "pypi", "maven", "go", "rubygems", "packagist", "nuget");
  // tsx belongs here because CallsiteLayer.run compiles a typescript rule against the tsx
  // grammar for .tsx files. Leaving it out meant a query that compiles for typescript and not
  // for tsx passed the build and threw at scan time, on the first repository with a .tsx file.
  static final Set<String> LANGUAGES = Set.of("java", "python", "typescript", "tsx", "javascript", "go", "ruby", "php", "csharp");

  private Validator() {}

  public static Report validate(Knowledge k, LocalDate today) {
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    Set<String> providerIds = new HashSet<>();
    Set<String> detectorIds = new HashSet<>();

    for (Provider p : k.providers()) {
      if (p.id() == null || !p.id().matches("[a-z][a-z0-9-]*")) errors.add("provider id invalid: " + p.id());
      if (!providerIds.add(p.id())) errors.add("duplicate provider id " + p.id());
      if (p.detectors().manifests().isEmpty()) errors.add(p.id() + ": detectors.yaml needs at least one manifest rule");
      for (Provider.Manifest m : p.detectors().manifests()) {
        if (!ECOSYSTEMS.contains(m.ecosystem())) errors.add(p.id() + ": unknown ecosystem " + m.ecosystem());
        if (m.pkg() == null || m.pkg().isBlank()) errors.add(p.id() + ": manifest rule without package");
      }
      for (Provider.Literal l : p.detectors().literals()) {
        if (!detectorIds.add(l.id())) errors.add("duplicate detector id " + l.id());
        if (!CONTRACT_KINDS.contains(l.kind())) errors.add(l.id() + ": unknown kind " + l.kind());
        if (!CONFIDENCES.contains(l.confidence())) errors.add(l.id() + ": unknown confidence " + l.confidence());
        if (l.files().isEmpty()) errors.add(l.id() + ": files must not be empty");
        if (l.key() == null) errors.add(l.id() + ": key template missing");
        for (String prob : RegexDialect.problems(l.pattern())) errors.add(l.id() + ": pattern " + prob);
      }
      for (Provider.Callsite c : p.detectors().callsites()) {
        if (!detectorIds.add(c.id())) errors.add("duplicate detector id " + c.id());
        if (!CONTRACT_KINDS.contains(c.kind())) errors.add(c.id() + ": unknown kind " + c.kind());
        if (!LANGUAGES.contains(c.language())) errors.add(c.id() + ": unknown language " + c.language());
        if (!CONFIDENCES.contains(c.confidence())) errors.add(c.id() + ": unknown confidence " + c.confidence());
        if (c.query() == null || c.query().isBlank()) errors.add(c.id() + ": query missing");
        if (c.mapsTo() != null && !CONTRACT_KINDS.contains(c.mapsTo().kind())) errors.add(c.id() + ": maps_to kind unknown");
        if (c.requires() != null && p.detectors().manifests().stream().noneMatch(m -> m.pkg().equals(c.requires()))) {
          errors.add(c.id() + ": requires " + c.requires() + " which is not a manifest rule of " + p.id());
        }
      }
      boolean hasNegative = k.fixtures().stream().anyMatch(f -> f.isNegative() && f.name().startsWith(p.id() + "-"));
      if (!hasNegative) errors.add(p.id() + ": needs at least one negative fixture named " + p.id() + "-negative-*");
    }
    errors.addAll(CallsiteLayer.compileAll(k.providers()));

    Set<String> changeIds = new HashSet<>();
    Set<String> referenced = new HashSet<>();
    for (Fixture f : k.fixtures()) {
      for (Finding.Pair pr : f.expectedFindingPairs()) referenced.add(pr.change());
      if (!Files.isDirectory(f.repo())) errors.add("fixture " + f.name() + " has no repo/ directory");
    }
    for (Change c : k.changes()) {
      String where = c.file();
      if (c.id() == null || !c.id().matches("[a-z0-9][a-z0-9.-]*")) errors.add(where + ": id invalid");
      if (!changeIds.add(c.id())) errors.add(where + ": duplicate change id " + c.id());
      if (!providerIds.contains(c.provider())) errors.add(where + ": unknown provider " + c.provider());
      if (!CHANGE_KINDS.contains(c.kind())) errors.add(where + ": unknown kind " + c.kind());
      if (!SEVERITIES.contains(c.severity())) errors.add(where + ": unknown severity " + c.severity());
      if (!STATUSES.contains(c.status())) errors.add(where + ": unknown status " + c.status());
      if (c.title() == null || c.title().isBlank()) errors.add(where + ": title missing");
      if (c.affects().isEmpty()) errors.add(where + ": affects must not be empty");
      for (Change.Affect a : c.affects()) {
        if (!CONTRACT_KINDS.contains(a.kind())) errors.add(where + ": affects kind unknown " + a.kind());
        if (a.match() == null || a.match().isBlank()) errors.add(where + ": affects match missing");
      }
      if (c.sources().isEmpty()) errors.add(where + ": sources must not be empty");
      for (Change.Source s : c.sources()) {
        if (!SOURCE_KINDS.contains(s.kind())) errors.add(where + ": source kind unknown " + s.kind());
        if (parseDate(s.observed()) == null) errors.add(where + ": source observed date invalid");
      }
      if (c.migration() != null && c.migration().effort() != null && !EFFORTS.contains(c.migration().effort())) {
        errors.add(where + ": migration effort unknown " + c.migration().effort());
      }
      LocalDate announced = parseDate(c.announced());
      if (announced == null) errors.add(where + ": announced date invalid or missing");
      LocalDate effective = c.effective() == null ? null : parseDate(c.effective());
      if (c.effective() != null && effective == null) errors.add(where + ": effective date invalid");
      if (announced != null && effective != null && !effective.isAfter(announced)) errors.add(where + ": effective must be after announced");
      if ("active".equals(c.status()) && effective != null && effective.isBefore(today)) {
        errors.add(where + ": status active but effective " + c.effective() + " is in the past; set status expired");
      }
      if ("expired".equals(c.status()) && effective != null && !effective.isBefore(today)) {
        errors.add(where + ": status expired but effective " + c.effective() + " is not in the past");
      }
      if (c.producesFindings()) {
        if (!referenced.contains(c.id())) {
          errors.add(where + ": no fixture references change " + c.id() + " in its expected-findings.json");
        } else {
          for (Change.Affect a : c.affects()) {
            boolean hit = k.fixtures().stream().flatMap(f -> f.expectedFindingPairs().stream())
                .anyMatch(pr -> pr.change().equals(c.id()) && contractMatches(pr.contract(), a));
            if (!hit) warnings.add(where + ": affects " + a.kind() + " " + a.match() + " is not hit by any fixture");
          }
        }
      }
    }
    return new Report(errors, warnings);
  }

  private static boolean contractMatches(String contractId, Change.Affect a) {
    // contractId is provider:kind:key; key may contain colons.
    int first = contractId.indexOf(':');
    int second = contractId.indexOf(':', first + 1);
    if (first < 0 || second < 0) return false;
    String kind = contractId.substring(first + 1, second);
    String key = contractId.substring(second + 1);
    return kind.equals(a.kind()) && Matcher.matches(kind, a.match(), key);
  }

  private static LocalDate parseDate(String s) {
    if (s == null) return null;
    try {
      return LocalDate.parse(s);
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
