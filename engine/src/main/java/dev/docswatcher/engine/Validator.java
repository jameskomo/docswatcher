package dev.docswatcher.engine;

import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
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

  private static final String PROVIDER_ID = "[a-z][a-z0-9-]*";
  private static final String CHANGE_ID = "[a-z0-9][a-z0-9.-]*";

  private Validator() {}

  public static Report validate(Knowledge k, LocalDate today) {
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    Set<String> providerIds = new HashSet<>();
    Set<String> detectorIds = new HashSet<>();

    for (Provider p : k.providers()) {
      if (p.id() == null || !p.id().matches(PROVIDER_ID)) errors.add("provider id invalid: " + p.id());
      if (p.id() != null && p.id().startsWith(OwnKnowledge.PREFIX)) {
        errors.add("provider id " + p.id() + " is reserved: ids starting " + OwnKnowledge.PREFIX + " belong to a team's own records");
      }
      if (!providerIds.add(p.id())) errors.add("duplicate provider id " + p.id());
      if (p.detectors().manifests().isEmpty()) errors.add(p.id() + ": detectors.yaml needs at least one manifest rule");
      checkDetectors(p, detectorIds, errors);
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
      if (c.id() == null || !c.id().matches(CHANGE_ID)) errors.add(where + ": id invalid");
      if (!changeIds.add(c.id())) errors.add(where + ": duplicate change id " + c.id());
      if (!providerIds.contains(c.provider())) errors.add(where + ": unknown provider " + c.provider());
      checkChange(c, today, errors, errors, warnings);
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

  /**
   * How many days ahead an active record's effective date is announced as a warning. The day
   * after that date the same record is an error, so the warning is the notice that the build
   * will turn red unless someone sets {@code status: expired} on the day.
   */
  static final int EXPIRY_NOTICE_DAYS = 7;

  /** Whether the status agrees with the effective date on {@code today}. */
  static void lifecycle(Change c, LocalDate effective, LocalDate today, List<String> errors, List<String> warnings) {
    if (effective == null) return;
    String where = c.file();
    if ("active".equals(c.status())) {
      if (effective.isBefore(today)) {
        errors.add(where + ": status active but effective " + c.effective() + " is in the past; set status expired");
      } else if (!effective.isAfter(today.plusDays(EXPIRY_NOTICE_DAYS))) {
        long days = today.until(effective, ChronoUnit.DAYS);
        warnings.add(where + ": " + c.id() + " takes effect " + c.effective()
            + (days == 0 ? " (today)" : " (in " + days + (days == 1 ? " day)" : " days)"))
            + "; set status expired on " + effective.plusDays(1) + ", when validation starts failing on it");
      }
    }
    if ("expired".equals(c.status()) && !effective.isBefore(today)) {
      errors.add(where + ": status expired but effective " + c.effective() + " is not in the past");
    }
  }

  /**
   * A team's own records, checked against the knowledge they are added to (docs/19-your-own-apis.md).
   * The knowledge base's rules, with these differences:
   *
   * <ul>
   *   <li>Provider ids start with {@code internal-}, and detector and change ids with their
   *       provider's id, so nothing a team writes collides with a bundled record, now or after an
   *       upgrade. Change records describe only these providers.
   *   <li>A manifest rule is optional: an internal HTTP service may have no client package.
   *   <li>No fixtures are required.
   *   <li>A status that disagrees with the effective date is a warning, not an error, so a date
   *       passing cannot fail every build that reads the records.
   *   <li>Source URLs and migration guides must be http(s) links, because they are shown as links.
   * </ul>
   *
   * <p>Mirrored message for message by validateOwn in web/engine/own.ts, apart from query
   * compilation, which the browser reports when it compiles the query.
   */
  public static Report validateOwn(Knowledge base, List<Provider> own, List<Change> ownChanges, LocalDate today) {
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    Set<String> baseProviderIds = new HashSet<>();
    Set<String> detectorIds = new HashSet<>();
    for (Provider p : base.providers()) {
      baseProviderIds.add(p.id());
      for (Provider.Literal l : p.detectors().literals()) detectorIds.add(l.id());
      for (Provider.Callsite c : p.detectors().callsites()) detectorIds.add(c.id());
    }
    Set<String> providerIds = new HashSet<>(baseProviderIds);
    Set<String> ownIds = new HashSet<>();

    List<Provider> providers = new ArrayList<>(own);
    providers.sort(Comparator.comparing(p -> String.valueOf(p.id())));
    for (Provider p : providers) {
      if (p.id() == null || !p.id().matches(PROVIDER_ID)) {
        errors.add("provider id invalid: " + p.id());
      } else if (!p.id().startsWith(OwnKnowledge.PREFIX) || p.id().equals(OwnKnowledge.PREFIX)) {
        errors.add("provider id " + p.id() + " must start with " + OwnKnowledge.PREFIX + " and name your service");
      }
      if (!providerIds.add(p.id())) errors.add("duplicate provider id " + p.id());
      ownIds.add(p.id());
      if (p.name() == null || p.name().isBlank()) errors.add(p.id() + ": name missing");
      checkDetectors(p, detectorIds, errors);
      List<String> ids = new ArrayList<>();
      p.detectors().literals().forEach(l -> ids.add(l.id()));
      p.detectors().callsites().forEach(c -> ids.add(c.id()));
      for (String id : ids) {
        if (id == null || !id.startsWith(p.id() + ".")) errors.add(id + ": detector id must start with " + p.id() + ".");
      }
    }
    errors.addAll(CallsiteLayer.compileAll(providers));

    List<Change> changes = new ArrayList<>(ownChanges);
    changes.sort(Comparator.comparing(c -> String.valueOf(c.id())));
    Set<String> changeIds = new HashSet<>();
    for (Change c : base.changes()) changeIds.add(c.id());
    for (Change c : changes) {
      String where = c.file();
      boolean validId = c.id() != null && c.id().matches(CHANGE_ID);
      if (!validId) errors.add(where + ": id invalid");
      if (!changeIds.add(c.id())) errors.add(where + ": duplicate change id " + c.id());
      if (baseProviderIds.contains(c.provider())) {
        errors.add(where + ": provider " + c.provider() + " is bundled; your own records describe your own " + OwnKnowledge.PREFIX + " providers");
      } else if (!ownIds.contains(c.provider())) {
        errors.add(where + ": unknown provider " + c.provider());
      } else if (validId && !c.id().startsWith(c.provider() + "-")) {
        errors.add(where + ": id must start with " + c.provider() + "-");
      }
      checkChange(c, today, errors, warnings, warnings);
      for (Change.Source s : c.sources()) {
        if (s.url() != null && !isHttp(s.url())) errors.add(where + ": source url must start with https:// or http://");
      }
      if (c.migration() != null && c.migration().guide() != null && !isHttp(c.migration().guide())) {
        errors.add(where + ": migration guide must start with https:// or http://");
      }
    }
    return new Report(errors, warnings);
  }

  /** A provider's manifest, literal and callsite rules. Detector ids are unique across every provider. */
  private static void checkDetectors(Provider p, Set<String> detectorIds, List<String> errors) {
    for (Provider.Manifest m : p.detectors().manifests()) {
      if (!in(ECOSYSTEMS, m.ecosystem())) errors.add(p.id() + ": unknown ecosystem " + m.ecosystem());
      if (m.pkg() == null || m.pkg().isBlank()) errors.add(p.id() + ": manifest rule without package");
    }
    for (Provider.Literal l : p.detectors().literals()) {
      if (!detectorIds.add(l.id())) errors.add("duplicate detector id " + l.id());
      if (!in(CONTRACT_KINDS, l.kind())) errors.add(l.id() + ": unknown kind " + l.kind());
      if (!in(CONFIDENCES, l.confidence())) errors.add(l.id() + ": unknown confidence " + l.confidence());
      if (l.files().isEmpty()) errors.add(l.id() + ": files must not be empty");
      if (l.key() == null) errors.add(l.id() + ": key template missing");
      if (l.pattern() == null) {
        errors.add(l.id() + ": pattern missing");
      } else {
        for (String prob : RegexDialect.problems(l.pattern())) errors.add(l.id() + ": pattern " + prob);
      }
    }
    for (Provider.Callsite c : p.detectors().callsites()) {
      if (!detectorIds.add(c.id())) errors.add("duplicate detector id " + c.id());
      if (!in(CONTRACT_KINDS, c.kind())) errors.add(c.id() + ": unknown kind " + c.kind());
      if (!in(LANGUAGES, c.language())) errors.add(c.id() + ": unknown language " + c.language());
      if (!in(CONFIDENCES, c.confidence())) errors.add(c.id() + ": unknown confidence " + c.confidence());
      if (c.query() == null || c.query().isBlank()) errors.add(c.id() + ": query missing");
      if (c.mapsTo() != null && !in(CONTRACT_KINDS, c.mapsTo().kind())) errors.add(c.id() + ": maps_to kind unknown");
      if (c.requires() != null && p.detectors().manifests().stream().noneMatch(m -> c.requires().equals(m.pkg()))) {
        errors.add(c.id() + ": requires " + c.requires() + " which is not a manifest rule of " + p.id());
      }
    }
  }

  /**
   * One change record's fields and dates. {@code dates} receives a status that disagrees with the
   * effective date (errors for the bundled knowledge, warnings for a team's own records);
   * {@code warnings} receives the week-ahead notice that a record is about to need expiring.
   */
  private static void checkChange(Change c, LocalDate today, List<String> errors, List<String> dates, List<String> warnings) {
    String where = c.file();
    if (!in(CHANGE_KINDS, c.kind())) errors.add(where + ": unknown kind " + c.kind());
    if (!in(SEVERITIES, c.severity())) errors.add(where + ": unknown severity " + c.severity());
    if (!in(STATUSES, c.status())) errors.add(where + ": unknown status " + c.status());
    if (c.title() == null || c.title().isBlank()) errors.add(where + ": title missing");
    if (c.affects().isEmpty()) errors.add(where + ": affects must not be empty");
    for (Change.Affect a : c.affects()) {
      if (!in(CONTRACT_KINDS, a.kind())) errors.add(where + ": affects kind unknown " + a.kind());
      if (a.match() == null || a.match().isBlank()) errors.add(where + ": affects match missing");
    }
    if (c.sources().isEmpty()) errors.add(where + ": sources must not be empty");
    for (Change.Source s : c.sources()) {
      if (!in(SOURCE_KINDS, s.kind())) errors.add(where + ": source kind unknown " + s.kind());
      if (parseDate(s.observed()) == null) errors.add(where + ": source observed date invalid");
    }
    if (c.migration() != null && c.migration().effort() != null && !in(EFFORTS, c.migration().effort())) {
      errors.add(where + ": migration effort unknown " + c.migration().effort());
    }
    LocalDate announced = parseDate(c.announced());
    if (announced == null) errors.add(where + ": announced date invalid or missing");
    LocalDate effective = c.effective() == null ? null : parseDate(c.effective());
    if (c.effective() != null && effective == null) errors.add(where + ": effective date invalid");
    if (announced != null && effective != null && !effective.isAfter(announced)) errors.add(where + ": effective must be after announced");
    lifecycle(c, effective, today, dates, warnings);
  }

  /** Set.of(...).contains(null) throws, and a field missing from a record is null. */
  private static boolean in(Set<String> allowed, String value) {
    return value != null && allowed.contains(value);
  }

  private static boolean isHttp(String url) {
    return url.startsWith("https://") || url.startsWith("http://");
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
