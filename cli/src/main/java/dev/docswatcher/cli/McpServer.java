package dev.docswatcher.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.docswatcher.engine.Change;
import dev.docswatcher.engine.Evidence;
import dev.docswatcher.engine.Finding;
import dev.docswatcher.engine.Inventory;
import dev.docswatcher.engine.Json;
import dev.docswatcher.engine.Knowledge;
import dev.docswatcher.engine.Matcher;
import dev.docswatcher.engine.OwnKnowledge;
import dev.docswatcher.engine.Provider;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A Model Context Protocol server over stdio: JSON-RPC 2.0, one message per line.
 *
 * <p>Lets a coding agent ask whether a model ID, endpoint, API version or SDK has a shutdown date
 * before it writes one. See docs/14-coding-agents.md. Read-only: no files written, no network, no
 * processes started.
 */
final class McpServer {

  /** Newest first. An initialize naming one of these gets it back; anything else gets the first. */
  static final List<String> PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

  /** Caps a tool's text answer so a large repository cannot flood the agent's context. */
  static final int TEXT_LIMIT = 12_000;

  static final List<String> KINDS = List.of("model", "endpoint", "api_version", "sdk_package", "sdk_method", "graphql_operation");

  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  private static final Pattern METHOD_AND_PATH = Pattern.compile("^(GET|POST|PUT|PATCH|DELETE|ANY)\\s+(/\\S*)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern DATED_VERSION = Pattern.compile("^\\d{4}-\\d{2}(-\\d{2})?$");
  private static final Pattern PINNED_PACKAGE = Pattern.compile("^(@?[A-Za-z0-9_.\\-/:]+?)\\s*(==|>=|<=|~=|@|:)\\s*v?\\d.*$");

  /** Router-style prefixes ({@code google/gemini-2.5-flash}) that are not provider IDs themselves. */
  private static final Map<String, String> PROVIDER_ALIASES = Map.of("google", "googleai", "gemini", "googleai", "claude", "anthropic");

  /** The knowledge without own records, for a scan of a directory other than root. */
  private final Knowledge base;
  /** Shared own-record directories (--knowledge-extra), read for every scan. */
  private final List<OwnKnowledge.Source> shared;
  /** base plus the own records of root and of shared, when they are valid. */
  private final Knowledge k;
  private final OwnKnowledge.Result own;
  private final Supplier<LocalDate> today;
  private final Path root;
  private final String version;
  private final Map<String, Provider> providers;

  McpServer(Knowledge k, Supplier<LocalDate> today, Path root, String version) {
    this(k, List.of(), today, root, version);
  }

  /**
   * Answers from {@code base} plus the own API records of {@code root}'s .docswatcher/ and of
   * {@code shared} (docs/19-your-own-apis.md). Records that fail validation are left out, and every
   * answer says so, rather than the server refusing to start.
   */
  McpServer(Knowledge base, List<OwnKnowledge.Source> shared, Supplier<LocalDate> today, Path root, String version) {
    this.base = base;
    this.shared = List.copyOf(shared);
    this.own = OwnKnowledge.merge(base, OwnKnowledge.sources(root, shared), today.get());
    this.k = own.knowledge();
    this.today = today;
    this.root = root;
    this.version = version;
    this.providers = k.providers().stream().collect(Collectors.toMap(Provider::id, p -> p, (a, b) -> a, LinkedHashMap::new));
  }

  /** What the server added from own records, or why it added nothing. For its startup line. */
  OwnKnowledge.Result own() {
    return own;
  }

  /** Answers requests until the client closes the stream. */
  void serve(BufferedReader in, PrintStream out) throws IOException {
    String line;
    while ((line = in.readLine()) != null) {
      if (line.isBlank()) continue;
      JsonNode reply = handle(line);
      if (reply != null) {
        out.print(Json.compact(reply));
        out.print('\n');
        out.flush();
      }
    }
  }

  /** One message in, at most one out. Null for a notification, which never gets a reply. */
  JsonNode handle(String line) {
    JsonNode msg;
    try {
      msg = Json.tree(line);
    } catch (IllegalArgumentException e) {
      return error(NullNode.getInstance(), -32700, "Parse error: the message is not JSON");
    }
    if (msg == null || !msg.isObject()) {
      return error(NullNode.getInstance(), -32600, "Invalid request: expected one JSON-RPC object per line");
    }
    JsonNode id = msg.get("id");
    boolean notification = id == null;
    if (!msg.hasNonNull("method")) {
      // A response to a request we sent. We send none, so there is nothing to match it to.
      return notification || msg.has("result") || msg.has("error") ? null
          : error(id, -32600, "Invalid request: no method");
    }
    String method = msg.get("method").asText();
    if (notification) return null;
    try {
      JsonNode result = switch (method) {
        case "initialize" -> initialize(msg.path("params"));
        case "ping" -> F.objectNode();
        case "tools/list" -> toolsList();
        case "tools/call" -> toolsCall(msg.path("params"));
        default -> throw new RpcError(-32601, "Method not found: " + method);
      };
      ObjectNode r = F.objectNode();
      r.put("jsonrpc", "2.0");
      r.set("id", id);
      r.set("result", result);
      return r;
    } catch (RpcError e) {
      return error(id, e.code, e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Protocol

  private JsonNode initialize(JsonNode params) {
    String asked = params.path("protocolVersion").asText("");
    ObjectNode r = F.objectNode();
    r.put("protocolVersion", PROTOCOL_VERSIONS.contains(asked) ? asked : PROTOCOL_VERSIONS.get(0));
    r.putObject("capabilities").putObject("tools").put("listChanged", false);
    ObjectNode info = r.putObject("serverInfo");
    info.put("name", "docswatcher");
    info.put("title", "DocsWatcher");
    info.put("version", version);
    r.put("instructions", instructions());
    return r;
  }

  String instructions() {
    String names = providers.values().stream().map(Provider::name).collect(Collectors.joining(", "));
    return "DocsWatcher knows which third-party AI models, API endpoints, API versions and SDKs have a published "
        + "shutdown date. Your training data is older than that list. Before you write, suggest or keep a model ID, "
        + "endpoint, pinned API version or SDK for " + names + ", call check_api with it. If the answer is RETIRED or "
        + "RETIRING, use the replacement it gives and tell the user why. Use scan_repository to audit code that "
        + "already exists, and upcoming_deprecations to see what is due soon.";
  }

  private JsonNode toolsList() {
    ArrayNode providerIds = F.arrayNode();
    providers.keySet().forEach(providerIds::add);

    ArrayNode tools = F.arrayNode();

    ObjectNode check = tool(tools, "check_api", "Check an API identifier",
        "Is this model ID, API endpoint, API version, SDK or GraphQL field still safe to use? Returns RETIRED, RETIRING (with the "
            + "date and days left), CHANGED, or NO KNOWN DEPRECATION, plus the provider's replacement and migration "
            + "guide. Accepts loose input: 'gpt-4-turbo', 'openai/gpt-4-turbo', 'POST /v1/assistants', a full API "
            + "URL, '2024-04', 'openai==0.28', or a GraphQL field such as 'automaticDiscounts'.");
    ObjectNode cp = schema(check);
    cp.putObject("value").put("type", "string").put("description", "The identifier as it would appear in code.");
    ObjectNode kind = cp.putObject("kind");
    kind.put("type", "string").put("description", "Optional. What the value is. Guessed from its shape when omitted.");
    ArrayNode kinds = kind.putArray("enum");
    KINDS.forEach(kinds::add);
    ObjectNode prov = cp.putObject("provider");
    prov.put("type", "string").put("description", "Optional. Limit the check to one provider.");
    prov.set("enum", providerIds.deepCopy());
    required(check, "value");

    ObjectNode upcoming = tool(tools, "upcoming_deprecations", "List upcoming API shutdowns",
        "Every dated deprecation due within a window, soonest first: provider, what is affected, the date, days "
            + "left and the replacement.");
    ObjectNode up = schema(upcoming);
    up.putObject("within_days").put("type", "integer").put("minimum", 1).put("maximum", 3650).put("default", 90)
        .put("description", "How far ahead to look.");
    ObjectNode prov2 = up.putObject("provider");
    prov2.put("type", "string").put("description", "Optional. Only this provider.");
    prov2.set("enum", providerIds.deepCopy());
    up.putObject("include_past").put("type", "boolean").put("default", false)
        .put("description", "Also list shutdowns that already happened.");

    ObjectNode scan = tool(tools, "scan_repository", "Scan code for deprecated API calls",
        "Scan a directory for the external APIs it calls and report every one with a shutdown date, with file "
            + "and line. The same scan the DocsWatcher GitHub Action runs in CI.");
    ObjectNode sp = schema(scan);
    sp.putObject("path").put("type", "string").put("description", "Directory to scan. Relative paths resolve from where the server started.");
    sp.putObject("include_low").put("type", "boolean").put("default", false)
        .put("description", "Also report APIs found only in documentation and test files.");
    ObjectNode ex = sp.putObject("exclude");
    ex.put("type", "array").put("description",
        "Optional. Extra paths to skip, in .gitignore syntax. The directory's .gitignore files and .docswatcherignore already apply.");
    ex.putObject("items").put("type", "string");
    required(scan, "path");

    ObjectNode r = F.objectNode();
    r.set("tools", tools);
    return r;
  }

  private JsonNode toolsCall(JsonNode params) {
    String name = params.path("name").asText("");
    JsonNode args = params.path("arguments");
    try {
      return switch (name) {
        case "check_api" -> checkApi(args);
        case "upcoming_deprecations" -> upcoming(args);
        case "scan_repository" -> scanRepository(args);
        default -> throw new RpcError(-32602, "Unknown tool: " + name);
      };
    } catch (BadInput e) {
      return toolResult(e.getMessage(), null, true);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // check_api

  /** One interpretation of an agent's input, in the shape the scanner would have extracted. */
  record Query(String provider, String kind, String key) {}

  private JsonNode checkApi(JsonNode args) {
    String value = args.path("value").asText("").trim();
    if (value.isEmpty()) throw new BadInput("check_api needs a value, for example {\"value\": \"gpt-4-turbo\"}.");
    String kind = optional(args, "kind");
    if (kind != null && !KINDS.contains(kind)) throw new BadInput("kind must be one of " + KINDS + ".");
    String provider = optional(args, "provider");
    if (provider != null && !providers.containsKey(provider)) {
      throw new BadInput("Unknown provider '" + provider + "'. Known: " + String.join(", ", providers.keySet()) + ".");
    }

    LocalDate now = today.get();
    Map<String, Matcher.Hit> hits = new LinkedHashMap<>();
    List<Query> queries = interpret(value, kind, provider);
    for (Query q : queries) {
      for (Matcher.Hit h : Matcher.lookup(k, q.provider(), q.kind(), q.key(), now)) hits.putIfAbsent(h.change().id(), h);
    }
    List<Matcher.Hit> sorted = hits.values().stream()
        .sorted(Comparator.comparing((Matcher.Hit h) -> h.change().effective() == null ? "9999-99-99" : h.change().effective()))
        .toList();

    String verdict = verdict(sorted);
    StringBuilder text = new StringBuilder();
    if (sorted.isEmpty()) {
      text.append("NO KNOWN DEPRECATION: \"").append(value).append("\" matches none of DocsWatcher's ")
          .append(k.changes().size()).append(" deprecation records across ").append(k.providers().size())
          .append(" providers (knowledge base ").append(k.version()).append(", last verified ").append(newestObserved())
          .append("). That means nothing known is scheduled, not that nothing is.");
    } else {
      for (Matcher.Hit h : sorted) text.append(line(h)).append('\n');
    }
    if (!own.ok()) {
      text.append(sorted.isEmpty() ? " " : "").append(ownNotLoaded());
    }

    ObjectNode data = F.objectNode();
    ObjectNode q = data.putObject("query");
    q.put("value", value);
    ArrayNode tried = q.putArray("interpretedAs");
    for (Query x : queries) {
      ObjectNode t = tried.addObject();
      t.put("kind", x.kind());
      t.put("key", x.key());
      if (x.provider() != null) t.put("provider", x.provider());
    }
    data.put("verdict", verdict);
    // Clients that show the model structuredContent instead of the text block (Claude Code does)
    // would otherwise never see the sentence that says what "no known deprecation" means.
    data.put("answer", text.toString().stripTrailing());
    ArrayNode matches = data.putArray("matches");
    for (Matcher.Hit h : sorted) matches.add(changeNode(h.change(), h.daysRemaining()));
    data.set("knowledgeBase", kbNode());
    return toolResult(text.toString().stripTrailing(), data, false);
  }

  /**
   * Turns loose agent input into the (provider, kind, key) triples the scanner would have produced.
   * An explicit kind wins; otherwise the value's shape decides, and a shapeless value is tried
   * against every kind whose match semantics are exact.
   */
  List<Query> interpret(String value, String kind, String provider) {
    Set<Query> out = new LinkedHashSet<>();
    String v = value.trim();

    // A full URL: the host names the provider, the path is the endpoint.
    if (v.startsWith("http://") || v.startsWith("https://")) {
      try {
        URI u = URI.create(v);
        String p = provider != null ? provider : providerForHost(u.getHost());
        String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        out.add(new Query(p, "endpoint", "ANY " + path));
        return List.copyOf(out);
      } catch (IllegalArgumentException ignored) {
        // Not a parseable URL after all; fall through and treat it as text.
      }
    }

    // Router style: openai/gpt-4-turbo, google/gemini-2.5-flash. Google's own models/ prefix too.
    String p = provider;
    String bare = v;
    int slash = v.indexOf('/');
    if (slash > 0 && !v.startsWith("/") && !v.contains(" ")) {
      String prefix = v.substring(0, slash).toLowerCase(Locale.ROOT);
      String resolved = providers.containsKey(prefix) ? prefix : PROVIDER_ALIASES.get(prefix);
      if (resolved != null) {
        if (p == null) p = resolved;
        bare = v.substring(slash + 1);
      } else if (prefix.equals("models")) {
        bare = v.substring(slash + 1);
      }
    }

    if (kind != null) {
      out.add(new Query(p, kind, normalise(kind, bare)));
      return List.copyOf(out);
    }

    java.util.regex.Matcher m = METHOD_AND_PATH.matcher(bare);
    if (m.matches()) {
      out.add(new Query(p, "endpoint", m.group(1).toUpperCase(Locale.ROOT) + " " + m.group(2)));
      return List.copyOf(out);
    }
    if (bare.startsWith("/")) {
      out.add(new Query(p, "endpoint", "ANY " + bare));
      return List.copyOf(out);
    }
    if (DATED_VERSION.matcher(bare).matches()) {
      // Only a date-shaped value is tried as an API version. The api_version rules compare
      // strings, so "0.28" would otherwise sort below "<2024-04" and match it.
      out.add(new Query(p, "api_version", bare));
      return List.copyOf(out);
    }

    // Exact-match kinds. A pinned package ("openai==0.28") is looked up by its name.
    java.util.regex.Matcher pinned = PINNED_PACKAGE.matcher(bare);
    String pkg = pinned.matches() ? pinned.group(1) : bare;
    out.add(new Query(p, "model", bare));
    String lower = bare.toLowerCase(Locale.ROOT);
    if (!lower.equals(bare)) out.add(new Query(p, "model", lower));
    out.add(new Query(p, "sdk_package", pkg));
    out.add(new Query(p, "sdk_method", bare));
    out.add(new Query(p, "graphql_operation", bare));
    return List.copyOf(out);
  }

  private static String normalise(String kind, String value) {
    if (!kind.equals("endpoint")) return value;
    java.util.regex.Matcher m = METHOD_AND_PATH.matcher(value);
    if (m.matches()) return m.group(1).toUpperCase(Locale.ROOT) + " " + m.group(2);
    return value.startsWith("/") ? "ANY " + value : value;
  }

  private String providerForHost(String host) {
    if (host == null) return null;
    for (Provider p : providers.values()) {
      for (String base : p.baseUrls() == null ? List.<String>of() : p.baseUrls()) {
        try {
          if (host.equalsIgnoreCase(URI.create(base).getHost())) return p.id();
        } catch (IllegalArgumentException ignored) {
          // A malformed base URL in the knowledge base is the validator's job to report, not ours.
        }
      }
    }
    return null;
  }

  static String verdict(List<Matcher.Hit> hits) {
    if (hits.isEmpty()) return "NO_KNOWN_DEPRECATION";
    boolean retiring = false;
    for (Matcher.Hit h : hits) {
      if (h.daysRemaining() == null) continue;
      if (h.daysRemaining() < 0) return "RETIRED";
      retiring = true;
    }
    return retiring ? "RETIRING" : "CHANGED";
  }

  private String line(Matcher.Hit h) {
    Change c = h.change();
    Integer d = h.daysRemaining();
    StringBuilder sb = new StringBuilder();
    if (d == null) {
      sb.append("CHANGED: ");
    } else if (d < 0) {
      sb.append("RETIRED ").append(-d).append(-d == 1 ? " day" : " days").append(" ago (").append(c.effective()).append("): ");
    } else if (d == 0) {
      sb.append("RETIRING today (").append(c.effective()).append("): ");
    } else {
      sb.append("RETIRING in ").append(d).append(d == 1 ? " day" : " days").append(" (").append(c.effective()).append("): ");
    }
    sb.append(c.title()).append(" [").append(providerName(c.provider())).append("].");
    if (c.migration() != null && c.migration().replacement() != null) {
      sb.append(" Replacement: ").append(c.migration().replacement()).append('.');
    }
    if (d == null && c.summary() != null) sb.append(' ').append(oneLine(c.summary()));
    String guide = c.migration() != null ? c.migration().guide() : null;
    if (guide != null) sb.append("\n  Guide: ").append(guide);
    return sb.toString();
  }

  // ---------------------------------------------------------------------------------------------
  // upcoming_deprecations

  private JsonNode upcoming(JsonNode args) {
    int within = args.has("within_days") ? args.get("within_days").asInt(-1) : 90;
    if (within < 1 || within > 3650) throw new BadInput("within_days must be between 1 and 3650.");
    String provider = optional(args, "provider");
    if (provider != null && !providers.containsKey(provider)) {
      throw new BadInput("Unknown provider '" + provider + "'. Known: " + String.join(", ", providers.keySet()) + ".");
    }
    boolean past = args.path("include_past").asBoolean(false);
    LocalDate now = today.get();

    List<Matcher.Hit> due = new ArrayList<>();
    for (Change c : k.changes()) {
      if (!c.producesFindings() || c.effective() == null) continue;
      if (provider != null && !c.provider().equals(provider)) continue;
      int days = (int) ChronoUnit.DAYS.between(now, LocalDate.parse(c.effective()));
      if ((days >= 0 && days <= within) || (past && days < 0)) due.add(new Matcher.Hit(c, days));
    }
    due.sort(Comparator.comparing((Matcher.Hit h) -> h.change().effective()).thenComparing(h -> h.change().id()));

    StringBuilder text = new StringBuilder();
    if (due.isEmpty()) {
      text.append("Nothing").append(provider == null ? "" : " from " + providerName(provider))
          .append(" is scheduled to shut down in the next ").append(within).append(" days.");
    } else {
      text.append(due.size()).append(due.size() == 1 ? " shutdown" : " shutdowns")
          .append(past ? "" : " in the next " + within + " days").append(":\n");
      for (Matcher.Hit h : due) {
        Change c = h.change();
        int d = h.daysRemaining();
        text.append(c.effective()).append("  ")
            .append(d < 0 ? (-d) + " days ago" : d == 0 ? "today" : "in " + d + (d == 1 ? " day" : " days"))
            .append("  ").append(providerName(c.provider())).append(": ").append(c.title());
        if (c.migration() != null && c.migration().replacement() != null) {
          text.append(" -> ").append(c.migration().replacement());
        }
        text.append('\n');
      }
    }

    ObjectNode data = F.objectNode();
    data.put("withinDays", within);
    if (provider != null) data.put("provider", provider);
    ArrayNode list = data.putArray("deprecations");
    for (Matcher.Hit h : due) list.add(changeNode(h.change(), h.daysRemaining()));
    data.set("knowledgeBase", kbNode());
    return toolResult(cap(text.toString().stripTrailing()), data, false);
  }

  // ---------------------------------------------------------------------------------------------
  // scan_repository

  private JsonNode scanRepository(JsonNode args) {
    String raw = args.path("path").asText("").trim();
    if (raw.isEmpty()) throw new BadInput("scan_repository needs a path, for example {\"path\": \".\"}.");
    Path dir = root.resolve(raw).normalize();
    if (!Files.isDirectory(dir)) throw new BadInput("Not a directory: " + dir);
    boolean includeLow = args.path("include_low").asBoolean(false);
    List<String> exclude = new ArrayList<>();
    for (JsonNode e : args.path("exclude")) if (e.isTextual() && !e.asText().isBlank()) exclude.add(e.asText());

    // The scanned directory's own records, not only the ones of the directory the server started in.
    Knowledge scanK = k;
    if (!dir.equals(root.normalize())) {
      OwnKnowledge.Result r = OwnKnowledge.merge(base, OwnKnowledge.sources(dir, shared), today.get());
      if (!r.ok()) {
        throw new BadInput("Nothing was scanned: the own API records for " + dir + " have " + r.errors().size()
            + (r.errors().size() == 1 ? " error" : " errors") + ":\n  " + String.join("\n  ", r.errors()));
      }
      scanK = r.knowledge();
    } else if (!own.ok()) {
      throw new BadInput("Nothing was scanned: " + ownNotLoaded());
    }
    Inventory inv;
    try {
      inv = ScanCommand.scan(scanK, dir, null, null, null, exclude);
    } catch (RuntimeException e) {
      throw new BadInput("The scan failed: " + e.getMessage());
    }
    List<Finding> findings = Matcher.match(inv, scanK, today.get(), includeLow);

    ObjectNode data = F.objectNode();
    data.put("path", dir.toString());
    data.put("filesScanned", inv.stats().filesScanned());
    if (inv.stats().incomplete() != null) data.put("incomplete", inv.stats().incomplete().describe());
    data.put("findingCount", findings.size());
    data.put("breaking", findings.stream().filter(f -> "breaking".equals(f.severity())).count());
    ArrayNode list = data.putArray("findings");
    for (Finding f : findings.stream().limit(100).toList()) {
      ObjectNode n = list.addObject();
      n.put("change", f.change());
      n.put("contract", f.contract());
      n.put("severity", f.severity());
      if (f.effective() != null) n.put("effective", f.effective());
      if (f.daysRemaining() != null) n.put("daysRemaining", f.daysRemaining());
      ArrayNode at = n.putArray("locations");
      for (Evidence e : f.evidence().stream().limit(5).toList()) at.add(e.path() + ":" + e.line());
    }
    data.set("knowledgeBase", kbNode());
    return toolResult(cap(MatchCommand.render(inv, findings, scanK).stripTrailing()), data, false);
  }

  // ---------------------------------------------------------------------------------------------
  // Shared

  private ObjectNode changeNode(Change c, Integer daysRemaining) {
    ObjectNode n = F.objectNode();
    n.put("id", c.id());
    n.put("provider", c.provider());
    n.put("providerName", providerName(c.provider()));
    n.put("title", c.title());
    n.put("kind", c.kind());
    n.put("severity", c.severity());
    n.put("status", c.status());
    if (c.effective() != null) n.put("effective", c.effective());
    if (daysRemaining != null) n.put("daysRemaining", daysRemaining);
    ArrayNode affects = n.putArray("affects");
    for (Change.Affect a : c.affects()) affects.add(a.kind() + " " + a.match());
    if (c.migration() != null) {
      if (c.migration().replacement() != null) n.put("replacement", c.migration().replacement());
      if (c.migration().guide() != null) n.put("guide", c.migration().guide());
    }
    if (c.sources() != null && !c.sources().isEmpty()) n.put("source", c.sources().get(0).url());
    if (c.summary() != null) n.put("summary", oneLine(c.summary()));
    return n;
  }

  private ObjectNode kbNode() {
    ObjectNode n = F.objectNode();
    n.put("version", k.version());
    n.put("changes", k.changes().size());
    n.put("providers", k.providers().size());
    n.put("lastVerified", newestObserved());
    if (!own.providers().isEmpty() || !own.errors().isEmpty()) {
      ObjectNode o = n.putObject("ownRecords");
      o.put("loaded", own.ok());
      o.put("providers", own.providers().size());
      o.put("changes", own.changes().size());
      ArrayNode errors = o.putArray("errors");
      own.errors().forEach(errors::add);
    }
    return n;
  }

  /** Said in every answer while own records fail validation, so an agent never takes their absence for a clean bill. */
  private String ownNotLoaded() {
    return "Note: your own API records were not loaded: " + own.errors().size()
        + (own.errors().size() == 1 ? " error" : " errors") + ", the first being \"" + own.errors().getFirst()
        + "\". Run docswatcher validate to see them all.";
  }

  /** The newest date a person confirmed any source. What "known" means in every answer. */
  String newestObserved() {
    return k.changes().stream()
        .flatMap(c -> c.sources() == null ? java.util.stream.Stream.<Change.Source>empty() : c.sources().stream())
        .map(Change.Source::observed)
        .filter(o -> o != null && !o.isBlank())
        .max(String::compareTo)
        .orElse("unknown");
  }

  private String providerName(String id) {
    Provider p = providers.get(id);
    return p == null ? id : p.name();
  }

  private static ObjectNode toolResult(String text, JsonNode structured, boolean isError) {
    ObjectNode r = F.objectNode();
    ObjectNode block = r.putArray("content").addObject();
    block.put("type", "text");
    block.put("text", text);
    if (structured != null) r.set("structuredContent", structured);
    r.put("isError", isError);
    return r;
  }

  private static ObjectNode tool(ArrayNode tools, String name, String title, String description) {
    ObjectNode t = tools.addObject();
    t.put("name", name);
    t.put("title", title);
    t.put("description", description);
    ObjectNode schema = t.putObject("inputSchema");
    schema.put("type", "object");
    schema.putObject("properties");
    ObjectNode hints = t.putObject("annotations");
    hints.put("readOnlyHint", true);
    hints.put("openWorldHint", false);
    return t;
  }

  private static ObjectNode schema(ObjectNode tool) {
    return (ObjectNode) tool.get("inputSchema").get("properties");
  }

  private static void required(ObjectNode tool, String... names) {
    ArrayNode req = ((ObjectNode) tool.get("inputSchema")).putArray("required");
    for (String n : names) req.add(n);
  }

  private static String optional(JsonNode args, String field) {
    JsonNode n = args.get(field);
    if (n == null || n.isNull()) return null;
    String s = n.asText("").trim();
    return s.isEmpty() ? null : s;
  }

  private static String oneLine(String s) {
    return s.replaceAll("\\s+", " ").trim();
  }

  private static String cap(String text) {
    if (text.length() <= TEXT_LIMIT) return text;
    return text.substring(0, TEXT_LIMIT) + "\n[truncated: " + (text.length() - TEXT_LIMIT)
        + " more characters. structuredContent holds the full counts and up to 100 entries.]";
  }

  private static ObjectNode error(JsonNode id, int code, String message) {
    ObjectNode r = F.objectNode();
    r.put("jsonrpc", "2.0");
    r.set("id", id == null ? NullNode.getInstance() : id);
    ObjectNode e = r.putObject("error");
    e.put("code", code);
    e.put("message", message);
    return r;
  }

  /** A protocol-level failure, answered with a JSON-RPC error. */
  static final class RpcError extends RuntimeException {
    final int code;

    RpcError(int code, String message) {
      super(message);
      this.code = code;
    }
  }

  /** The agent asked wrong. Answered as a tool result with isError, which the model can read and fix. */
  static final class BadInput extends RuntimeException {
    BadInput(String message) {
      super(message);
    }
  }
}
