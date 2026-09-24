package dev.docswatcher.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds SDK packages in dependency manifests. See the manifest table in docs/02-schemas.md. */
final class ManifestLayer {

  private ManifestLayer() {}

  static void run(List<Provider> providers, List<SourceFile> files, Accumulator acc) {
    for (SourceFile f : files) {
      String name = f.path.substring(f.path.lastIndexOf('/') + 1);
      String ecosystem = ecosystemOf(name);
      if (ecosystem == null) continue;
      for (Provider p : providers) {
        for (Provider.Manifest m : p.detectors().manifests()) {
          if (!m.ecosystem().equals(ecosystem)) continue;
          Hit hit = switch (ecosystem) {
            case "npm" -> npm(f, m.pkg());
            case "pypi" -> name.equals("pyproject.toml") ? pyproject(f, m.pkg()) : requirements(f, m.pkg());
            case "maven" -> maven(f, m.pkg());
            case "go" -> gomod(f, m.pkg());
            case "rubygems" -> gemfile(f, m.pkg());
            case "packagist" -> composer(f, m.pkg());
            case "nuget" -> csproj(f, m.pkg());
            default -> null;
          };
          if (hit == null) continue;
          Evidence e = new Evidence(f.path, f.lineAt(hit.offset), f.columnAt(hit.offset), f.snippetAt(hit.offset),
              p.id() + ".manifest." + ecosystem, "manifest");
          acc.add(p.id(), "sdk_package", m.pkg(), e, "high");
          acc.manifestHit(p.id(), new Context.Sdk(ecosystem, m.pkg(), hit.version));
        }
      }
    }
  }

  record Hit(int offset, String version) {}

  static String ecosystemOf(String fileName) {
    if (fileName.equals("package.json")) return "npm";
    if (fileName.equals("pyproject.toml") || (fileName.startsWith("requirements") && fileName.endsWith(".txt"))) return "pypi";
    if (fileName.equals("pom.xml")) return "maven";
    if (fileName.equals("go.mod")) return "go";
    if (fileName.equals("Gemfile")) return "rubygems";
    if (fileName.equals("composer.json")) return "packagist";
    if (fileName.endsWith(".csproj")) return "nuget";
    return null;
  }

  static Hit npm(SourceFile f, String pkg) {
    return jsonDependency(f, pkg, "dependencies", "devDependencies");
  }

  static Hit composer(SourceFile f, String pkg) {
    return jsonDependency(f, pkg, "require", "require-dev");
  }

  /** A key of any of the named top-level objects, as package.json and composer.json list dependencies. */
  private static Hit jsonDependency(SourceFile f, String pkg, String... sections) {
    JsonNode root;
    try {
      root = Json.tree(f.text);
    } catch (IllegalArgumentException e) {
      return null;
    }
    String version = null;
    boolean present = false;
    for (String section : sections) {
      JsonNode deps = root.get(section);
      if (deps != null && deps.has(pkg)) {
        present = true;
        if (version == null) version = deps.get(pkg).asText();
      }
    }
    if (!present) return null;
    Matcher m = Pattern.compile("\"" + Pattern.quote(pkg) + "\"\\s*:").matcher(f.text);
    // The first occurrence inside a dependency section: skip a "name" field match by requiring a colon after the quote.
    while (m.find()) {
      return new Hit(m.start() + 1, version);
    }
    return null;
  }

  private static String normalizePy(String s) {
    return s.toLowerCase().replace('_', '-').replace('.', '-');
  }

  static Hit requirements(SourceFile f, String pkg) {
    Pattern line = Pattern.compile("(?m)^[ \\t]*([A-Za-z0-9][A-Za-z0-9._-]*)(?:\\[[^\\]]*\\])?[ \\t]*(?:(==|>=|~=|>|<=|<|!=)[ \\t]*([0-9][0-9A-Za-z.*+!-]*))?");
    Matcher m = line.matcher(f.text);
    while (m.find()) {
      if (normalizePy(m.group(1)).equals(normalizePy(pkg))) {
        String v = m.group(3);
        return new Hit(m.start(1), v);
      }
    }
    return null;
  }

  static Hit pyproject(SourceFile f, String pkg) {
    // PEP 621 dependency strings and poetry style "name = "version"" lines.
    Pattern quoted = Pattern.compile("[\"']([A-Za-z0-9][A-Za-z0-9._-]*)(?:\\[[^\\]]*\\])?[ \\t]*(?:(==|>=|~=|>|<=|<|!=)[ \\t]*([0-9][0-9A-Za-z.*+!-]*))?[^\"']*[\"']");
    Matcher m = quoted.matcher(f.text);
    while (m.find()) {
      if (normalizePy(m.group(1)).equals(normalizePy(pkg))) return new Hit(m.start(1), m.group(3));
    }
    Pattern poetry = Pattern.compile("(?m)^[ \\t]*([A-Za-z0-9][A-Za-z0-9._-]*)[ \\t]*=[ \\t]*[\"']([^\"']*)[\"']");
    m = poetry.matcher(f.text);
    while (m.find()) {
      if (normalizePy(m.group(1)).equals(normalizePy(pkg))) {
        String v = m.group(2).replaceAll("^[\\^~>=<!]+\\s*", "");
        return new Hit(m.start(1), v.isEmpty() ? null : v);
      }
    }
    return null;
  }

  static Hit maven(SourceFile f, String pkg) {
    int colon = pkg.indexOf(':');
    if (colon < 0) return null;
    String group = pkg.substring(0, colon);
    String artifact = pkg.substring(colon + 1);
    Pattern dep = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
    Matcher m = dep.matcher(f.text);
    while (m.find()) {
      String block = m.group(1);
      Matcher g = Pattern.compile("<groupId>\\s*([^<\\s]+)\\s*</groupId>").matcher(block);
      Matcher a = Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>").matcher(block);
      if (g.find() && a.find() && g.group(1).equals(group) && a.group(1).equals(artifact)) {
        Matcher v = Pattern.compile("<version>\\s*([^<\\s]+)\\s*</version>").matcher(block);
        String version = v.find() ? v.group(1) : null;
        return new Hit(m.start(1) + a.start(1), version);
      }
    }
    return null;
  }

  static Hit gomod(SourceFile f, String pkg) {
    Pattern line = Pattern.compile("(?m)^[ \\t]*(?:require[ \\t]+)?(" + Pattern.quote(pkg) + ")[ \\t]+(v[^ \\t\\r\\n/]+)");
    Matcher m = line.matcher(f.text);
    if (m.find()) return new Hit(m.start(1), m.group(2));
    return null;
  }

  static Hit gemfile(SourceFile f, String pkg) {
    Pattern line = Pattern.compile("(?m)^[ \\t]*gem[ \\t]+[\"'](" + Pattern.quote(pkg) + ")[\"'](?:[ \\t]*,[ \\t]*[\"']([^\"']+)[\"'])?");
    Matcher m = line.matcher(f.text);
    if (m.find()) return new Hit(m.start(1), m.group(2));
    return null;
  }

  private static final Pattern PACKAGE_REFERENCE = Pattern.compile("<PackageReference\\b([^>]*)>");
  private static final Pattern INCLUDE = Pattern.compile("\\bInclude\\s*=\\s*([\"'])(.*?)\\1");
  private static final Pattern VERSION_ATTRIBUTE = Pattern.compile("\\bVersion\\s*=\\s*([\"'])(.*?)\\1");
  private static final Pattern VERSION_ELEMENT = Pattern.compile("<Version>\\s*([^<\\s]+)\\s*</Version>");

  /**
   * A {@code <PackageReference Include="...">} of an SDK-style project file. NuGet package ids are
   * case-insensitive. The version is the Version attribute, else a nested Version element.
   */
  static Hit csproj(SourceFile f, String pkg) {
    Matcher m = PACKAGE_REFERENCE.matcher(f.text);
    while (m.find()) {
      String attributes = m.group(1);
      Matcher include = INCLUDE.matcher(attributes);
      if (!include.find() || !include.group(2).equalsIgnoreCase(pkg)) continue;
      Matcher v = VERSION_ATTRIBUTE.matcher(attributes);
      String version = v.find() ? v.group(2) : null;
      if (version == null && !attributes.endsWith("/")) {
        int close = f.text.indexOf("</PackageReference>", m.end());
        if (close >= 0) {
          Matcher e = VERSION_ELEMENT.matcher(f.text.substring(m.end(), close));
          if (e.find()) version = e.group(1);
        }
      }
      return new Hit(m.start(1) + include.start(2), version);
    }
    return null;
  }
}
