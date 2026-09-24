// A team's own API records, added to the bundled knowledge for one scan (docs/19-your-own-apis.md,
// ADR 0008). The TypeScript twin of engine/.../OwnKnowledge.java and Validator.validateOwn: both are
// held to the same messages by engine/src/test/resources/own-knowledge-cases.json.
//
// Records use the knowledge base's own layout under a repository's .docswatcher/ directory:
// providers/<id>/provider.yaml, detectors.yaml and changes/*.yaml. Any error means none of them are
// used, and the caller reports every error.
import YAML from "yaml";
import type {
  CallsiteRule, ChangeRecord, ContractKind, DetectorTable, GrammarLoader, InputFile, Knowledge, LiteralRule, ManifestRule,
  Provider, ProviderInfo,
} from "./types";
import type { TreeSitterApi } from "./callsites";

/** The directory at a repository's root that holds its own records. */
export const OWN_DIR = ".docswatcher";
/** Every provider id of a team's own records starts with this, and no bundled one does. */
export const OWN_PREFIX = "internal-";

/** Where records are read from: files whose path starts with `prefix`, named `label` in messages. */
export interface OwnSource { label: string; prefix: string; }

export interface OwnResult {
  /** The merged knowledge when there are no errors, otherwise the base unchanged. */
  knowledge: Knowledge;
  /** Every own provider read, valid or not, with its change records. */
  providers: Provider[];
  /** Every own change record read, valid or not. */
  changes: ChangeRecord[];
  errors: string[];
  warnings: string[];
  ok: boolean;
}

const CONTRACT_KINDS = new Set(["sdk_package", "sdk_method", "endpoint", "model", "api_version", "graphql_operation", "webhook"]);
const CHANGE_KINDS = new Set(["sunset", "retired", "behavior_change", "field_change", "limit_change"]);
const SEVERITIES = new Set(["breaking", "warning", "info"]);
const STATUSES = new Set(["draft", "active", "withdrawn", "expired"]);
const SOURCE_KINDS = new Set(["changelog", "openapi_diff", "email", "sunset_header", "deprecation_header", "provider_page"]);
const EFFORTS = new Set(["small", "medium", "large"]);
const CONFIDENCES = new Set(["high", "medium", "low"]);
const ECOSYSTEMS = new Set(["npm", "pypi", "maven", "go", "rubygems"]);
const LANGUAGES = new Set(["java", "python", "typescript", "tsx", "javascript", "go"]);
const PROVIDER_ID = /^[a-z][a-z0-9-]*$/;
const CHANGE_ID = /^[a-z0-9][a-z0-9.-]*$/;

/** A change record and the file it came from, which messages name. */
interface Loaded { rec: ChangeRecord; file: string; }

/** The repository's own .docswatcher/, when it has any file, then each shared source, each prefix once. */
export function ownSources(files: InputFile[], shared: OwnSource[] = []): OwnSource[] {
  const out: OwnSource[] = [];
  const repo = `${OWN_DIR}/`;
  if (files.some((f) => f.path.startsWith(repo))) out.push({ label: OWN_DIR, prefix: repo });
  for (const s of shared) if (!out.some((o) => o.prefix === s.prefix)) out.push(s);
  return out;
}

/** The base knowledge plus the own records in `files`, or the base and the errors. */
export function mergeOwn(base: Knowledge, files: InputFile[], today: Date, shared: OwnSource[] = []): OwnResult {
  const sources = ownSources(files, shared);
  if (sources.length === 0) return { knowledge: base, providers: [], changes: [], errors: [], warnings: [], ok: true };
  const providers: Provider[] = [];
  const changes: Loaded[] = [];
  const errors: string[] = [];
  for (const s of sources) load(s, files, providers, changes, errors);
  const report = validateOwn(base, providers, changes, today);
  errors.push(...report.errors);
  // Each provider carries its own records, the shape the rest of the engine reads.
  for (const c of changes) providers.find((p) => p.info.id === c.rec.provider)?.changes.push(c.rec);
  for (const p of providers) p.changes.sort((a, b) => cmp(String(a.id), String(b.id)));
  const ok = errors.length === 0;
  const knowledge = ok
    ? { version: base.version, providers: [...base.providers, ...providers].sort((a, b) => cmp(a.info.id, b.info.id)) }
    : base;
  return { knowledge, providers, changes: changes.map((c) => c.rec), errors, warnings: report.warnings, ok };
}

/** One line for a report: what was added, or why nothing was. */
export function ownSummary(r: OwnResult): string {
  if (!r.ok) return `Your own API records were not used: ${r.errors.length} ${r.errors.length === 1 ? "error" : "errors"}`;
  const n = r.changes.length;
  return `Your own API records: ${r.providers.length} ${r.providers.length === 1 ? "provider" : "providers"}, ` +
    `${n} ${n === 1 ? "change record" : "change records"}`;
}

/**
 * Compiles each own callsite query, which validateOwn cannot do without grammars. The Java
 * validator reports the same problems when it validates.
 */
export async function ownQueryErrors(providers: Provider[], loader: GrammarLoader, api: TreeSitterApi): Promise<string[]> {
  const errors: string[] = [];
  for (const p of providers) {
    for (const c of p.detectors.callsites) {
      if (typeof c.query !== "string" || !LANGUAGES.has(c.language)) continue;
      try {
        const lang = await loader(c.language as never);
        const tree = api.parse(lang, "");
        try { api.matches(lang, c.query, tree.rootNode); } finally { tree.delete?.(); }
        if (!/@call(?![\w.-])/.test(c.query)) errors.push(`${c.id}: query has no @call capture`);
      } catch (e: any) {
        errors.push(`${c.id}: query does not compile for ${c.language}: ${e?.message ?? String(e)}`);
      }
    }
  }
  return errors;
}

function load(s: OwnSource, files: InputFile[], providers: Provider[], changes: Loaded[], errors: string[]) {
  const root = `${s.prefix}providers/`;
  const byDir = new Map<string, Map<string, string>>();
  for (const f of files) {
    if (!f.path.startsWith(root)) continue;
    const rest = f.path.slice(root.length);
    const slash = rest.indexOf("/");
    if (slash <= 0) continue; // a file directly in providers/ is not a provider
    const dir = rest.slice(0, slash);
    if (!byDir.has(dir)) byDir.set(dir, new Map());
    byDir.get(dir)!.set(rest.slice(slash + 1), f.text);
  }
  if (byDir.size === 0) { errors.push(`${s.label}: no providers/ directory`); return; }
  for (const name of [...byDir.keys()].sort(cmp)) {
    const inDir = byDir.get(name)!;
    const where = `${s.label}/providers/${name}`;
    if (!inDir.has("provider.yaml")) { errors.push(`${where}: provider.yaml missing`); continue; }
    const profile = read(inDir.get("provider.yaml")!, `${where}/provider.yaml`, errors);
    if (profile === null) continue;
    let detectors: any = null;
    if (inDir.has("detectors.yaml")) {
      detectors = read(inDir.get("detectors.yaml")!, `${where}/detectors.yaml`, errors);
      if (detectors === null) continue;
    }
    const p = provider(profile, detectors);
    if (p.info.id !== name) errors.push(`${where}/provider.yaml: id ${p.info.id} does not match its directory ${name}`);
    providers.push(p);
    const changeFiles = [...inDir.keys()].filter((k) => k.startsWith("changes/") && !k.slice(8).includes("/")).sort(cmp);
    for (const k of changeFiles) {
      const file = `${where}/${k}`;
      if (file.endsWith(".yml")) { errors.push(`${file}: name it .yaml, the only extension that is read`); continue; }
      if (!file.endsWith(".yaml")) continue;
      const n = read(inDir.get(k)!, file, errors);
      if (n !== null) changes.push({ rec: change(n), file });
    }
  }
}

/** A YAML mapping, or null with the reason added to `errors`. */
function read(text: string, what: string, errors: string[]): Record<string, unknown> | null {
  let n: unknown;
  try {
    n = YAML.parse(text);
  } catch (e: any) {
    errors.push(`${what}: not valid YAML: ${String(e?.message ?? e).split("\n")[0].trim()}`);
    return null;
  }
  if (typeof n !== "object" || n === null || Array.isArray(n)) {
    errors.push(`${what}: expected a mapping of fields`);
    return null;
  }
  return n as Record<string, unknown>;
}

// Field readers that behave like the Java engine's Yaml.str and Yaml.strings over Jackson trees.
function text(v: unknown): string {
  if (v === null) return "null";
  if (typeof v === "object") return "";
  return String(v);
}
function str(n: unknown, field: string): string | null {
  if (typeof n !== "object" || n === null || Array.isArray(n)) return null;
  const v = (n as Record<string, unknown>)[field];
  return v === undefined || v === null ? null : text(v);
}
function strings(n: unknown, field: string): string[] {
  const v = typeof n === "object" && n !== null ? (n as Record<string, unknown>)[field] : undefined;
  return Array.isArray(v) ? v.map(text) : [];
}
function array(n: unknown, field: string): unknown[] {
  const v = typeof n === "object" && n !== null ? (n as Record<string, unknown>)[field] : undefined;
  return Array.isArray(v) ? v : [];
}
const trim = (s: string | null) => (s === null ? null : s.trim());

function provider(profile: unknown, det: unknown): Provider {
  const info = { id: str(profile, "id"), name: str(profile, "name") } as ProviderInfo;
  for (const f of ["homepage", "docs", "changelog", "deprecation_policy"] as const) {
    const v = str(profile, f);
    if (v !== null) info[f] = v;
  }
  info.base_urls = strings(profile, "base_urls");
  info.sdk_languages = strings(profile, "sdk_languages");
  const detectors: DetectorTable = {
    provider: info.id,
    manifests: array(det, "manifests").map((m) => ({ ecosystem: str(m, "ecosystem"), package: str(m, "package") }) as ManifestRule),
    literals: array(det, "literals").map((l) => ({
      id: str(l, "id"), kind: str(l, "kind"), files: strings(l, "files"), exclude: strings(l, "exclude"),
      pattern: str(l, "pattern"), key: str(l, "key"), confidence: str(l, "confidence") ?? "medium",
    }) as LiteralRule),
    callsites: array(det, "callsites").map((c) => {
      const rule = {
        id: str(c, "id"), language: str(c, "language"), kind: str(c, "kind"), requires: str(c, "requires"),
        query: str(c, "query"), key: str(c, "key"), confidence: str(c, "confidence") ?? "high",
      } as CallsiteRule;
      const mt = (c as Record<string, unknown>)?.maps_to;
      if (mt !== undefined && mt !== null) rule.maps_to = { kind: str(mt, "kind") as ContractKind, key: str(mt, "key") as string };
      return rule;
    }),
  };
  return { info, detectors, changes: [] };
}

function change(n: Record<string, unknown>): ChangeRecord {
  const m = n.migration;
  const rec = {
    id: str(n, "id"), provider: str(n, "provider"), kind: str(n, "kind"), severity: str(n, "severity"),
    title: str(n, "title"), summary: trim(str(n, "summary")),
    affects: array(n, "affects").map((a) => ({ kind: str(a, "kind"), match: str(a, "match") })),
    announced: str(n, "announced"), effective: str(n, "effective"),
    sources: array(n, "sources").map((s) => ({ kind: str(s, "kind"), url: str(s, "url"), observed: str(s, "observed"), note: str(s, "note") })),
    status: str(n, "status"),
  } as unknown as ChangeRecord;
  if (m !== undefined && m !== null) {
    rec.migration = {
      replacement: str(m, "replacement"), guide: str(m, "guide"), effort: str(m, "effort"), notes: trim(str(m, "notes")),
    } as ChangeRecord["migration"];
  }
  return rec;
}

/**
 * Validator.validateOwn, message for message: the knowledge base's rules, with provider ids that
 * start internal-, detector and change ids that start with their provider's id, optional manifest
 * rules, no fixtures, status/date disagreements as warnings, and http(s) links only.
 */
export function validateOwn(base: Knowledge, own: Provider[], ownChanges: Loaded[], today: Date): { errors: string[]; warnings: string[] } {
  const errors: string[] = [];
  const warnings: string[] = [];
  const baseProviderIds = new Set<string>();
  const detectorIds = new Set<string | null>();
  const changeIds = new Set<string | null>();
  for (const p of base.providers) {
    baseProviderIds.add(p.info.id);
    for (const l of p.detectors.literals) detectorIds.add(l.id);
    for (const c of p.detectors.callsites) detectorIds.add(c.id);
    for (const c of p.changes) changeIds.add(c.id);
  }
  const providerIds = new Set<string | null>(baseProviderIds);
  const ownIds = new Set<string | null>();
  const todayIso = today.toISOString().slice(0, 10);

  for (const p of [...own].sort((a, b) => cmp(String(a.info.id), String(b.info.id)))) {
    const id = p.info.id as string | null;
    if (id === null || !PROVIDER_ID.test(id)) errors.push(`provider id invalid: ${id}`);
    else if (!id.startsWith(OWN_PREFIX) || id === OWN_PREFIX) errors.push(`provider id ${id} must start with ${OWN_PREFIX} and name your service`);
    if (providerIds.has(id)) errors.push(`duplicate provider id ${id}`);
    providerIds.add(id);
    ownIds.add(id);
    if (p.info.name === null || !String(p.info.name).trim()) errors.push(`${id}: name missing`);
    checkDetectors(p, id, detectorIds, errors);
    for (const d of [...p.detectors.literals.map((l) => l.id), ...p.detectors.callsites.map((c) => c.id)] as Array<string | null>) {
      if (d === null || !d.startsWith(`${id}.`)) errors.push(`${d}: detector id must start with ${id}.`);
    }
  }

  for (const { rec: c, file: where } of [...ownChanges].sort((a, b) => cmp(String(a.rec.id), String(b.rec.id)))) {
    const id = c.id as string | null;
    const validId = id !== null && CHANGE_ID.test(id);
    if (!validId) errors.push(`${where}: id invalid`);
    if (changeIds.has(id)) errors.push(`${where}: duplicate change id ${id}`);
    changeIds.add(id);
    if (baseProviderIds.has(c.provider)) {
      errors.push(`${where}: provider ${c.provider} is bundled; your own records describe your own ${OWN_PREFIX} providers`);
    } else if (!ownIds.has(c.provider)) {
      errors.push(`${where}: unknown provider ${c.provider}`);
    } else if (validId && !id.startsWith(`${c.provider}-`)) {
      errors.push(`${where}: id must start with ${c.provider}-`);
    }
    checkChange(c, where, todayIso, errors, warnings);
    for (const s of c.sources) if (s.url != null && !isHttp(s.url)) errors.push(`${where}: source url must start with https:// or http://`);
    if (c.migration && c.migration.guide != null && !isHttp(c.migration.guide)) {
      errors.push(`${where}: migration guide must start with https:// or http://`);
    }
  }
  return { errors, warnings };
}

function checkDetectors(p: Provider, id: string | null, detectorIds: Set<string | null>, errors: string[]) {
  const d = p.detectors;
  for (const m of d.manifests) {
    if (!ECOSYSTEMS.has(m.ecosystem)) errors.push(`${id}: unknown ecosystem ${m.ecosystem}`);
    if (m.package === null || !String(m.package).trim()) errors.push(`${id}: manifest rule without package`);
  }
  for (const l of d.literals) {
    if (detectorIds.has(l.id)) errors.push(`duplicate detector id ${l.id}`);
    detectorIds.add(l.id);
    if (!CONTRACT_KINDS.has(l.kind)) errors.push(`${l.id}: unknown kind ${l.kind}`);
    if (!CONFIDENCES.has(l.confidence)) errors.push(`${l.id}: unknown confidence ${l.confidence}`);
    if (l.files.length === 0) errors.push(`${l.id}: files must not be empty`);
    if (l.key === null) errors.push(`${l.id}: key template missing`);
    if (l.pattern === null) errors.push(`${l.id}: pattern missing`);
    else for (const prob of regexProblems(l.pattern)) errors.push(`${l.id}: pattern ${prob}`);
  }
  for (const c of d.callsites) {
    if (detectorIds.has(c.id)) errors.push(`duplicate detector id ${c.id}`);
    detectorIds.add(c.id);
    if (!CONTRACT_KINDS.has(c.kind)) errors.push(`${c.id}: unknown kind ${c.kind}`);
    if (!LANGUAGES.has(c.language)) errors.push(`${c.id}: unknown language ${c.language}`);
    if (!CONFIDENCES.has(c.confidence)) errors.push(`${c.id}: unknown confidence ${c.confidence}`);
    if (c.query === null || !String(c.query).trim()) errors.push(`${c.id}: query missing`);
    if (c.maps_to && !CONTRACT_KINDS.has(c.maps_to.kind)) errors.push(`${c.id}: maps_to kind unknown`);
    if (c.requires !== null && !d.manifests.some((m) => m.package === c.requires)) {
      errors.push(`${c.id}: requires ${c.requires} which is not a manifest rule of ${id}`);
    }
  }
}

function checkChange(c: ChangeRecord, where: string, today: string, errors: string[], dates: string[]) {
  if (!CHANGE_KINDS.has(c.kind)) errors.push(`${where}: unknown kind ${c.kind}`);
  if (!SEVERITIES.has(c.severity)) errors.push(`${where}: unknown severity ${c.severity}`);
  if (!STATUSES.has(c.status)) errors.push(`${where}: unknown status ${c.status}`);
  if (c.title === null || !String(c.title).trim()) errors.push(`${where}: title missing`);
  if (c.affects.length === 0) errors.push(`${where}: affects must not be empty`);
  for (const a of c.affects) {
    if (!CONTRACT_KINDS.has(a.kind)) errors.push(`${where}: affects kind unknown ${a.kind}`);
    if (a.match === null || !String(a.match).trim()) errors.push(`${where}: affects match missing`);
  }
  if (c.sources.length === 0) errors.push(`${where}: sources must not be empty`);
  for (const s of c.sources) {
    if (!SOURCE_KINDS.has(s.kind)) errors.push(`${where}: source kind unknown ${s.kind}`);
    if (!isDate(s.observed)) errors.push(`${where}: source observed date invalid`);
  }
  if (c.migration && c.migration.effort != null && !EFFORTS.has(c.migration.effort)) {
    errors.push(`${where}: migration effort unknown ${c.migration.effort}`);
  }
  const announced = isDate(c.announced) ? c.announced : null;
  if (announced === null) errors.push(`${where}: announced date invalid or missing`);
  const effective = c.effective !== null && isDate(c.effective) ? c.effective : null;
  if (c.effective !== null && effective === null) errors.push(`${where}: effective date invalid`);
  if (announced !== null && effective !== null && !(effective > announced)) errors.push(`${where}: effective must be after announced`);
  if (c.status === "active" && effective !== null && effective < today) {
    dates.push(`${where}: status active but effective ${c.effective} is in the past; set status expired`);
  }
  if (c.status === "expired" && effective !== null && !(effective < today)) {
    dates.push(`${where}: status expired but effective ${c.effective} is not in the past`);
  }
}

/** RegexDialect.problems: constructs outside the subset Java and JavaScript run identically. */
export function regexProblems(pattern: string): string[] {
  const out: string[] = [];
  if (/[*+?}]\+/.test(pattern)) out.push("possessive quantifier");
  if (pattern.includes("\\h")) out.push("\\h is Java-only");
  if (["\\R", "\\X", "\\Q", "\\E"].some((s) => pattern.includes(s))) out.push("\\R, \\X, \\Q, \\E are Java-only");
  if (/\\[AZzG]/.test(pattern)) out.push("\\A, \\Z, \\z, \\G are Java-only");
  if (/\\p\{(?!L\})/.test(pattern)) out.push("only \\p{L} is allowed");
  if (/\(\?[a-zA-Z]+[-:)]/.test(pattern)) out.push("inline flags are not allowed");
  if (pattern.includes("(?<=") || pattern.includes("(?<!")) out.push("lookbehind is not allowed");
  // Java compiles first; JavaScript rejects some of the Java-only constructs above outright, so it
  // is asked only about a pattern that has none of them.
  if (out.length === 0) {
    try { new RegExp(pattern, "gd"); } catch (e: any) { out.push(`does not compile: ${e?.message ?? String(e)}`); }
  }
  return out;
}

/** LocalDate.parse: yyyy-mm-dd naming a real day. */
function isDate(s: string | null | undefined): s is string {
  if (typeof s !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(s)) return false;
  const [y, m, d] = s.split("-").map(Number);
  const t = new Date(Date.UTC(y, m - 1, d));
  return t.getUTCFullYear() === y && t.getUTCMonth() === m - 1 && t.getUTCDate() === d;
}

const isHttp = (url: string) => url.startsWith("https://") || url.startsWith("http://");

/** Java's String.compareTo: UTF-16 code units, which is what < does. */
function cmp(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}
