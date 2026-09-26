import type { Severity } from "~~/engine/types";

/**
 * Runtime observation on the organisation dashboard: shapes the app sends, and the small pieces
 * of wording the screen needs. docs/13-runtime-observation.md.
 */

/** A finding, named well enough to show it and find it in the findings list. */
export interface RuntimeFindingRef {
  contract: string;
  change: string;
  changeTitle: string;
  severity: Severity;
  effective: string | null;
  status: string;
}

/** One endpoint as production called it, summed over every day observed. */
export interface RuntimeCall {
  host: string;
  method: string;
  path: string;
  provider: string | null;
  contractId: string | null;
  totalCalls: number;
  callsPerDay: number;
  daysObserved: number;
  firstSeen: string | null;
  lastSeen: string | null;
  deprecationHeader: string | null;
  sunsetHeader: string | null;
  findings: RuntimeFindingRef[];
}

export interface RuntimeSummary {
  repoId: number;
  repoFullName: string;
  /** Null until any telemetry has arrived; then silence means something. */
  lastReportAt: string | null;
  activeTokens: number;
  deprecated: RuntimeCall[];
  alsoObserved: RuntimeCall[];
  notObserved: RuntimeFindingRef[];
  notObservable: number;
}

/** What anyone who can read the repository sees about a token. Never the token. */
export interface IngestToken {
  id: number;
  repoId: number;
  prefix: string;
  label: string;
  createdBy: string;
  createdAt: string;
  lastUsedAt: string | null;
}

/** The one response that carries the secret. */
export interface CreatedToken { token: IngestToken; secret: string }

/** The canonical ingest address, for pages that are not served by an app of their own. */
export const HOSTED_INGEST = "https://docswatcher.vukisha.co.ke/api/runtime/otlp/v1/traces";

/** Where this deployment takes telemetry, next to wherever the dashboard itself is served. */
export function ingestEndpoint(base: string): string {
  return new URL("api/runtime/otlp/v1/traces", base).toString();
}

type Raw<T> = { [K in keyof T]?: T[K] | null };

function ref(r: Raw<RuntimeFindingRef>): RuntimeFindingRef {
  return {
    contract: r.contract ?? "",
    change: r.change ?? "",
    changeTitle: r.changeTitle ?? r.change ?? "",
    severity: (r.severity ?? "info") as Severity,
    effective: r.effective ?? null,
    status: r.status ?? "open",
  };
}

function call(c: Raw<RuntimeCall>): RuntimeCall {
  return {
    host: c.host ?? "",
    method: c.method ?? "ANY",
    path: c.path ?? "/",
    provider: c.provider ?? null,
    contractId: c.contractId ?? null,
    totalCalls: c.totalCalls ?? 0,
    callsPerDay: c.callsPerDay ?? 0,
    daysObserved: c.daysObserved ?? 0,
    firstSeen: c.firstSeen ?? null,
    lastSeen: c.lastSeen ?? null,
    deprecationHeader: c.deprecationHeader ?? null,
    sunsetHeader: c.sunsetHeader ?? null,
    findings: (c.findings ?? []).map(ref),
  };
}

/** Fills in what the server's NON_NULL serialisation leaves out. */
export function normaliseRuntimeSummary(s: Raw<RuntimeSummary>): RuntimeSummary {
  return {
    repoId: s.repoId ?? 0,
    repoFullName: s.repoFullName ?? "",
    lastReportAt: s.lastReportAt ?? null,
    activeTokens: s.activeTokens ?? 0,
    deprecated: (s.deprecated ?? []).map(call),
    alsoObserved: (s.alsoObserved ?? []).map(call),
    notObserved: (s.notObserved ?? []).map(ref),
    notObservable: s.notObservable ?? 0,
  };
}

/** 1204 as "1,204". Fixed, not the browser's locale, so the page reads the same everywhere. */
export function fmtCount(n: number): string {
  return String(Math.round(n)).replace(/\B(?=(\d{3})+(?!\d))/g, ",");
}

/** How long ago, in the largest whole unit that fits. */
export function ago(iso: string | null, now: number = Date.now()): string {
  if (!iso) return "never";
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return "never";
  const s = Math.max(0, Math.round((now - t) / 1000));
  if (s < 60) return "just now";
  const units: [number, string][] = [[86_400, "day"], [3_600, "hour"], [60, "minute"]];
  for (const [size, name] of units) {
    const n = Math.floor(s / size);
    if (n >= 1) return `${n} ${name}${n === 1 ? "" : "s"} ago`;
  }
  return "just now";
}

/** "POST /v1/assistants", or the path alone when the span named no method. */
export function endpointLabel(c: Pick<RuntimeCall, "method" | "path">): string {
  return c.method === "ANY" ? c.path : `${c.method} ${c.path}`;
}

/** The provider's own words, when it sent any. */
export function noticeLabel(c: Pick<RuntimeCall, "deprecationHeader" | "sunsetHeader">): string | null {
  const parts = [
    c.sunsetHeader ? `Sunset: ${c.sunsetHeader}` : null,
    c.deprecationHeader ? `Deprecation: ${c.deprecationHeader}` : null,
  ].filter((p): p is string => p !== null);
  return parts.length ? parts.join(" · ") : null;
}
