import type {
  Confidence, Contract, ContractContext, ContractKind, Evidence, GrammarLoader, InputFile, Inventory, Knowledge,
  Layer, Provider, RepoRef,
} from "./types";
import { scanManifest, type ManifestHit } from "./manifests";
import { scanLiterals } from "./literals";
import { scanCallsites, type TreeSitterApi } from "./callsites";
import { isDocOrTestPath, isSkippedDir, isTooLarge, languageOf } from "./paths";

export const ENGINE_NAME = "docswatcher-engine-ts";
export const ENGINE_VERSION = "0.1.0";

export interface ScanOptions {
  repo: RepoRef;
  knowledge: Knowledge;
  /** Omit to skip the callsite layer (for example when grammars cannot be loaded). */
  grammars?: { loader: GrammarLoader; api: TreeSitterApi };
  now?: () => Date;
  onProgress?: (done: number, total: number, path: string) => void;
}

const CONF_RANK: Record<Confidence, number> = { low: 0, medium: 1, high: 2 };

interface Accum {
  provider: string; kind: ContractKind; key: string; confidence: Confidence;
  evidence: Map<string, Evidence>;
}

export async function scan(files: InputFile[], opts: ScanOptions): Promise<Inventory> {
  const t0 = Date.now();
  const providers = opts.knowledge.providers;
  const accum = new Map<string, Accum>();
  const manifestHits = new Map<string, ManifestHit[]>(); // provider -> hits
  let filesScanned = 0, filesSkipped = 0;

  const add = (provider: string, kind: ContractKind, key: string, confidence: Confidence, ev: Evidence) => {
    const id = `${provider}:${kind}:${key}`;
    let a = accum.get(id);
    if (!a) { a = { provider, kind, key, confidence, evidence: new Map() }; accum.set(id, a); }
    if (CONF_RANK[confidence] > CONF_RANK[a.confidence]) a.confidence = confidence;
    const evKey = `${ev.path}${ev.line}${ev.column}${ev.detector}`;
    if (!a.evidence.has(evKey)) a.evidence.set(evKey, ev);
  };

  // Pass 1: manifests and literals. Also decide which files are eligible.
  const eligible: InputFile[] = [];
  for (const f of files) {
    if (isSkippedDir(f.path) || isTooLarge(f.text)) { filesSkipped++; continue; }
    filesScanned++;
    eligible.push(f);
    for (const p of providers) {
      const hits = scanManifest(f, p.detectors.manifests);
      for (const h of hits) {
        const list = manifestHits.get(p.info.id) ?? [];
        list.push(h); manifestHits.set(p.info.id, list);
        add(p.info.id, "sdk_package", h.rule.package, "high", {
          path: h.path, line: h.line, column: h.column, snippet: h.snippet,
          detector: `${p.info.id}.manifest.${h.rule.ecosystem}`, layer: "manifest",
        });
      }
      for (const h of scanLiterals(f, p.detectors.literals)) {
        add(p.info.id, h.rule.kind, h.key, h.rule.confidence, {
          path: h.path, line: h.line, column: h.column, snippet: h.snippet, detector: h.rule.id, layer: "literal",
        });
      }
    }
  }

  // Pass 2: callsites, only for providers whose required package was found in a manifest.
  const layers: Layer[] = ["manifest", "literal"];
  if (opts.grammars) {
    layers.push("callsite");
    let i = 0;
    for (const f of eligible) {
      i++;
      opts.onProgress?.(i, eligible.length, f.path);
      const lang = languageOf(f.path);
      if (!lang) continue;
      const ruleLang = lang === "tsx" ? "typescript" : lang;
      for (const p of providers) {
        const found = new Set((manifestHits.get(p.info.id) ?? []).map((h) => h.rule.package));
        const rules = p.detectors.callsites.filter((r) => r.language === ruleLang && found.has(r.requires));
        if (rules.length === 0) continue;
        const hits = await scanCallsites(f, lang, rules, opts.grammars.loader, opts.grammars.api);
        for (const h of hits) {
          const ev: Evidence = { path: h.path, line: h.line, column: h.column, snippet: h.snippet, detector: h.rule.id, layer: "callsite" };
          add(p.info.id, h.rule.kind, h.rule.key, h.rule.confidence, ev);
          if (h.rule.maps_to) add(p.info.id, h.rule.maps_to.kind, h.rule.maps_to.key, h.rule.confidence, ev);
        }
      }
    }
  }

  // Assemble contracts.
  const contracts: Contract[] = [];
  const byProvider = new Map<string, Accum[]>();
  for (const a of accum.values()) {
    const list = byProvider.get(a.provider) ?? [];
    list.push(a); byProvider.set(a.provider, list);
  }
  for (const [providerId, list] of byProvider) {
    const p = providers.find((x) => x.info.id === providerId) as Provider;
    const ctx = contextFor(p, manifestHits.get(providerId) ?? [], list);
    for (const a of list) {
      const evidence = [...a.evidence.values()].sort(compareEvidence);
      const allDocOrTest = evidence.every((e) => e.layer !== "manifest" && isDocOrTestPath(e.path));
      const confidence: Confidence = allDocOrTest ? "low" : a.confidence;
      const c: Contract = {
        id: `${a.provider}:${a.kind}:${a.key}`, provider: a.provider, kind: a.kind, key: a.key, confidence, evidence,
      };
      if (ctx) c.context = ctx;
      contracts.push(c);
    }
  }
  contracts.sort((x, y) => (x.id < y.id ? -1 : x.id > y.id ? 1 : 0));

  const now = opts.now ? opts.now() : new Date();
  return {
    schemaVersion: "1",
    repo: { host: opts.repo.host, owner: opts.repo.owner, name: opts.repo.name, ref: opts.repo.ref, sha: opts.repo.sha },
    scannedAt: now.toISOString().replace(/\.\d{3}Z$/, "Z"),
    engine: { name: ENGINE_NAME, version: ENGINE_VERSION, knowledgeVersion: opts.knowledge.version },
    stats: { filesScanned, filesSkipped, durationMs: Date.now() - t0, layers },
    contracts,
  };
}

function contextFor(p: Provider, hits: ManifestHit[], list: Accum[]): ContractContext | undefined {
  const ctx: ContractContext = {};
  const rulesMatched = new Set(hits.map((h) => h.rule.ecosystem + ":" + h.rule.package));
  if (rulesMatched.size === 1) {
    const first = [...hits].sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : a.line - b.line))[0];
    ctx.sdk = { ecosystem: first.rule.ecosystem, package: first.rule.package, version: first.version };
  }
  const versions = list.filter((a) => a.kind === "api_version");
  if (versions.length === 1) ctx.apiVersion = versions[0].key;
  return Object.keys(ctx).length ? ctx : undefined;
}

function compareEvidence(a: Evidence, b: Evidence): number {
  if (a.path !== b.path) return a.path < b.path ? -1 : 1;
  if (a.line !== b.line) return a.line - b.line;
  if (a.column !== b.column) return a.column - b.column;
  return a.detector < b.detector ? -1 : a.detector > b.detector ? 1 : 0;
}
