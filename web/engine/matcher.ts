import type { Affects, ChangeRecord, Contract, Finding, Inventory, Knowledge, Severity } from "./types";

export interface MatchOptions {
  today: Date;
  includeLow?: boolean;
}

const SEV_RANK: Record<Severity, number> = { breaking: 0, warning: 1, info: 2 };

export function affectsMatches(a: Affects, c: Contract): boolean {
  if (a.kind !== c.kind) return false;
  if (a.kind === "endpoint") return endpointMatches(a.match, c.key);
  if (a.kind === "api_version") return versionMatches(a.match, c.key);
  return a.match === c.key;
}

function endpointMatches(pattern: string, key: string): boolean {
  const [pm, ...prest] = pattern.split(" ");
  const [km, ...krest] = key.split(" ");
  const ppath = prest.join(" "), kpath = krest.join(" ");
  if (pm !== "ANY" && km !== "ANY" && pm !== km) return false;
  if (!ppath.includes("*")) return ppath === kpath;
  const re = new RegExp("^" + ppath.split("*").map((s) => s.replace(/[.+^$()|[\]\\?{}]/g, "\\$&")).join("[^/]*") + "$");
  return re.test(kpath);
}

function versionMatches(pattern: string, key: string): boolean {
  const m = /^(<=|>=|<|>)\s*(.+)$/.exec(pattern.trim());
  if (!m) return pattern === key;
  const [, op, v] = m;
  switch (op) {
    case "<": return key < v;
    case "<=": return key <= v;
    case ">": return key > v;
    case ">=": return key >= v;
  }
  return false;
}

export function daysBetween(today: Date, effective: string): number {
  const t = Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate());
  const [y, mo, d] = effective.split("-").map(Number);
  const e = Date.UTC(y, mo - 1, d);
  return Math.round((e - t) / 86400000);
}

export function match(inventory: Inventory, knowledge: Knowledge, opts: MatchOptions): Finding[] {
  const out: Finding[] = [];
  const repoPrefix = `${inventory.repo.owner}/${inventory.repo.name}`;
  const changes: ChangeRecord[] = knowledge.providers.flatMap((p) => p.changes)
    .filter((ch) => ch.status === "active" || ch.status === "expired");
  for (const c of inventory.contracts) {
    if (c.confidence === "low" && !opts.includeLow) continue;
    for (const ch of changes) {
      if (ch.provider !== c.provider) continue;
      if (!ch.affects.some((a) => affectsMatches(a, c))) continue;
      out.push({
        id: `${repoPrefix}:${ch.id}:${c.id}`,
        contract: c.id,
        change: ch.id,
        severity: ch.severity,
        effective: ch.effective,
        daysRemaining: ch.effective ? daysBetween(opts.today, ch.effective) : null,
        evidence: c.evidence.map((e) => ({ ...e })),
        status: "open",
        snoozedUntil: null,
        fixPr: null,
      });
    }
  }
  out.sort((a, b) => {
    if (a.effective !== b.effective) {
      if (a.effective === null) return 1;
      if (b.effective === null) return -1;
      return a.effective < b.effective ? -1 : 1;
    }
    if (a.severity !== b.severity) return SEV_RANK[a.severity] - SEV_RANK[b.severity];
    if (a.contract !== b.contract) return a.contract < b.contract ? -1 : 1;
    return a.change < b.change ? -1 : a.change > b.change ? 1 : 0;
  });
  return out;
}
