// check_api, the question an AI coding agent asks before it writes an API identifier.
// The TypeScript twin of cli/.../McpServer.checkApi; both are held to
// cli/src/test/resources/check-api-cases.json.
import { affectsMatches, daysBetween } from "./matcher";
import type { ChangeRecord, ContractKind, Knowledge } from "./types";

export const CHECK_KINDS: ContractKind[] = ["model", "endpoint", "api_version", "sdk_package", "sdk_method"];

export type Verdict = "RETIRED" | "RETIRING" | "CHANGED" | "NO_KNOWN_DEPRECATION";

export interface CheckMatch {
  id: string; provider: string; providerName: string; title: string; effective: string | null;
  daysRemaining: number | null; replacement: string | null; guide: string | null;
}

export interface CheckResult {
  verdict: Verdict;
  /** The same sentence(s) the CLI's MCP server answers with. */
  answer: string;
  matches: CheckMatch[];
}

interface Query { provider: string | null; kind: ContractKind; key: string }

const METHOD_AND_PATH = /^(GET|POST|PUT|PATCH|DELETE|ANY)\s+(\/\S*)$/i;
const DATED_VERSION = /^\d{4}-\d{2}(-\d{2})?$/;
const PINNED_PACKAGE = /^(@?[A-Za-z0-9_.\-/:]+?)\s*(==|>=|<=|~=|@|:)\s*v?\d.*$/;
const PROVIDER_ALIASES: Record<string, string> = { google: "googleai", gemini: "googleai", claude: "anthropic" };

const oneLine = (s: string) => s.replace(/\s+/g, " ").trim();

export function checkApi(
  knowledge: Knowledge,
  input: { value: string; kind?: ContractKind; provider?: string },
  today: Date,
): CheckResult {
  const value = (input.value ?? "").trim();
  if (!value) throw new Error('check_api needs a value, for example {"value": "gpt-4-turbo"}.');
  const providers = new Map(knowledge.providers.map((p) => [p.info.id, p.info]));
  if (input.kind && !CHECK_KINDS.includes(input.kind)) throw new Error(`kind must be one of ${CHECK_KINDS.join(", ")}.`);
  if (input.provider && !providers.has(input.provider)) {
    throw new Error(`Unknown provider '${input.provider}'. Known: ${[...providers.keys()].join(", ")}.`);
  }
  const all = knowledge.providers.flatMap((p) => p.changes);
  const changes = all.filter((c) => c.status === "active" || c.status === "expired");

  const hits = new Map<string, ChangeRecord>();
  for (const q of interpret(value, input.kind ?? null, input.provider ?? null, knowledge)) {
    for (const c of lookup(changes, q)) if (!hits.has(c.id)) hits.set(c.id, c);
  }
  const sorted = [...hits.values()].sort((a, b) => (a.effective ?? "9999-99-99").localeCompare(b.effective ?? "9999-99-99"));
  const matches: CheckMatch[] = sorted.map((c) => ({
    id: c.id, provider: c.provider, providerName: providers.get(c.provider)?.name ?? c.provider, title: c.title,
    effective: c.effective, daysRemaining: c.effective ? daysBetween(today, c.effective) : null,
    replacement: c.migration?.replacement ?? null, guide: c.migration?.guide ?? null,
  }));

  const answer = matches.length === 0
    ? `NO KNOWN DEPRECATION: "${value}" matches none of DocsWatcher's ${all.length} deprecation records across ` +
      `${knowledge.providers.length} providers (knowledge base ${knowledge.version}, last verified ${newestObserved(knowledge)}). ` +
      "That means nothing known is scheduled, not that nothing is."
    : matches.map((m, i) => line(m, sorted[i])).join("\n");
  return { verdict: verdict(matches), answer, matches };
}

/** Every change whose affects match one (provider, kind, key), soonest first, like Matcher.lookup. */
function lookup(changes: ChangeRecord[], q: Query): ChangeRecord[] {
  return changes
    .filter((c) => (q.provider == null || c.provider === q.provider) && c.affects.some((a) =>
      affectsMatches(a, { kind: q.kind, key: q.key } as Parameters<typeof affectsMatches>[1])))
    .sort((a, b) => (a.effective ?? "9999-99-99").localeCompare(b.effective ?? "9999-99-99") || a.id.localeCompare(b.id));
}

/** Loose agent input to the (provider, kind, key) triples the scanner would have produced. */
function interpret(value: string, kind: ContractKind | null, provider: string | null, knowledge: Knowledge): Query[] {
  const out: Query[] = [];
  const add = (q: Query) => { if (!out.some((o) => o.provider === q.provider && o.kind === q.kind && o.key === q.key)) out.push(q); };

  if (value.startsWith("http://") || value.startsWith("https://")) {
    try {
      const u = new URL(value);
      add({ provider: provider ?? providerForHost(u.hostname, knowledge), kind: "endpoint", key: `ANY ${u.pathname || "/"}` });
      return out;
    } catch { /* not a URL after all: treat it as text */ }
  }

  let p = provider;
  let bare = value;
  const slash = value.indexOf("/");
  if (slash > 0 && !value.startsWith("/") && !value.includes(" ")) {
    const prefix = value.slice(0, slash).toLowerCase();
    const resolved = knowledge.providers.some((x) => x.info.id === prefix) ? prefix : PROVIDER_ALIASES[prefix];
    if (resolved) { p ??= resolved; bare = value.slice(slash + 1); }
    else if (prefix === "models") bare = value.slice(slash + 1);
  }

  if (kind) { add({ provider: p, kind, key: normalise(kind, bare) }); return out; }

  const m = METHOD_AND_PATH.exec(bare);
  if (m) { add({ provider: p, kind: "endpoint", key: `${m[1].toUpperCase()} ${m[2]}` }); return out; }
  if (bare.startsWith("/")) { add({ provider: p, kind: "endpoint", key: `ANY ${bare}` }); return out; }
  // Only a date-shaped value is an API version: the rules compare strings, and "0.28" < "2024-04".
  if (DATED_VERSION.test(bare)) { add({ provider: p, kind: "api_version", key: bare }); return out; }

  const pinned = PINNED_PACKAGE.exec(bare);
  add({ provider: p, kind: "model", key: bare });
  if (bare.toLowerCase() !== bare) add({ provider: p, kind: "model", key: bare.toLowerCase() });
  add({ provider: p, kind: "sdk_package", key: pinned ? pinned[1] : bare });
  add({ provider: p, kind: "sdk_method", key: bare });
  return out;
}

function normalise(kind: ContractKind, value: string): string {
  if (kind !== "endpoint") return value;
  const m = METHOD_AND_PATH.exec(value);
  if (m) return `${m[1].toUpperCase()} ${m[2]}`;
  return value.startsWith("/") ? `ANY ${value}` : value;
}

function providerForHost(host: string, knowledge: Knowledge): string | null {
  for (const p of knowledge.providers) {
    for (const base of p.info.base_urls ?? []) {
      try { if (new URL(base).hostname.toLowerCase() === host.toLowerCase()) return p.info.id; } catch { /* validator's job */ }
    }
  }
  return null;
}

function verdict(matches: CheckMatch[]): Verdict {
  if (matches.length === 0) return "NO_KNOWN_DEPRECATION";
  let retiring = false;
  for (const m of matches) {
    if (m.daysRemaining == null) continue;
    if (m.daysRemaining < 0) return "RETIRED";
    retiring = true;
  }
  return retiring ? "RETIRING" : "CHANGED";
}

function line(m: CheckMatch, c: ChangeRecord): string {
  const d = m.daysRemaining;
  let s = d == null ? "CHANGED: "
    : d < 0 ? `RETIRED ${-d} ${-d === 1 ? "day" : "days"} ago (${m.effective}): `
    : d === 0 ? `RETIRING today (${m.effective}): `
    : `RETIRING in ${d} ${d === 1 ? "day" : "days"} (${m.effective}): `;
  s += `${m.title} [${m.providerName}].`;
  if (m.replacement != null) s += ` Replacement: ${m.replacement}.`;
  if (d == null && c.summary) s += ` ${oneLine(c.summary)}`;
  if (m.guide != null) s += `\n  Guide: ${m.guide}`;
  return s;
}

/** The newest date a person confirmed any source. What "known" means in every answer. */
function newestObserved(knowledge: Knowledge): string {
  let newest = "";
  for (const p of knowledge.providers) for (const c of p.changes) for (const s of c.sources ?? []) {
    if (s.observed > newest) newest = s.observed;
  }
  return newest || "unknown";
}
