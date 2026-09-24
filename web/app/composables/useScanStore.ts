import type { Finding, Inventory } from "~~/engine/types";
import type { OwnRecords } from "./useScanner";

export interface StoredScan {
  inventory: Inventory;
  findings: Finding[];
  source: { label: string; kind: "sample" | "github" | "gitlab" | "folder" };
  /** The scanned repository's own API records, so their findings keep their titles after a reload. */
  own?: OwnRecords | null;
  at: string;
}
export interface LocalState {
  production: boolean;
  snoozed: Record<string, string>;   // finding id -> until date
  notInProd: Record<string, true>;
}

const KEY = "docswatcher.lastScan";
const LOCAL = "docswatcher.local";

/**
 * A stored scan holds the verbatim matched source line of every hit, plus its path. For a
 * folder or private-repository scan that is the visitor's own private code, sitting on disk
 * in the browser profile. Three bounds follow from that: a sample scan is cheap to re-run so
 * it is the only kind kept indefinitely, anything else ages out within the hour, and nothing
 * oversized is written at all.
 */
const MAX_AGE_MS = 60 * 60 * 1000;
const MAX_BYTES = 2_000_000;

function isFresh(scan: StoredScan): boolean {
  if (scan.source?.kind === "sample") return true;
  const at = Date.parse(scan.at);
  return Number.isFinite(at) && Date.now() - at < MAX_AGE_MS;
}

/**
 * Reads only the keys this app owns and coerces each one. The previous version spread the
 * parsed object over the defaults, so anything that could write this key could also steer
 * effectiveStatus and silently hide findings from every list.
 */
function sanitiseLocal(raw: unknown): LocalState {
  const base: LocalState = { production: true, snoozed: {}, notInProd: {} };
  if (typeof raw !== "object" || raw === null) return base;
  const o = raw as Record<string, unknown>;
  if (typeof o.production === "boolean") base.production = o.production;
  if (typeof o.snoozed === "object" && o.snoozed !== null) {
    for (const [k, v] of Object.entries(o.snoozed as Record<string, unknown>)) {
      if (typeof v === "string" && /^\d{4}-\d{2}-\d{2}$/.test(v)) base.snoozed[k] = v;
    }
  }
  if (typeof o.notInProd === "object" && o.notInProd !== null) {
    for (const [k, v] of Object.entries(o.notInProd as Record<string, unknown>)) {
      if (v === true) base.notInProd[k] = true;
    }
  }
  return base;
}

const current = ref<StoredScan | null>(null);
const local = ref<LocalState>({ production: true, snoozed: {}, notInProd: {} });
let loaded = false;

function load() {
  if (loaded || typeof window === "undefined") return;
  loaded = true;
  try {
    const raw = localStorage.getItem(KEY);
    if (raw) {
      const scan = JSON.parse(raw) as StoredScan;
      if (isFresh(scan)) current.value = scan;
      else localStorage.removeItem(KEY);
    }
  } catch { /* storage unavailable, or a record this version cannot read */ }
  try { const raw = localStorage.getItem(LOCAL); if (raw) local.value = sanitiseLocal(JSON.parse(raw)); } catch { /* storage unavailable */ }
}

export function useScanStore() {
  load();
  function save(scan: StoredScan) {
    current.value = scan;
    try {
      const body = JSON.stringify(scan);
      // Measure before writing. setItem used to throw QuotaExceededError into an empty catch,
      // which left the PREVIOUS scan at rest while the page displayed this one.
      if (body.length > MAX_BYTES) localStorage.removeItem(KEY);
      else localStorage.setItem(KEY, body);
    } catch { try { localStorage.removeItem(KEY); } catch { /* storage unavailable */ } }
  }

  /** The affordance the privacy copy implies. Nothing cleared this before. */
  function forget() {
    current.value = null;
    local.value = { production: true, snoozed: {}, notInProd: {} };
    try { localStorage.removeItem(KEY); localStorage.removeItem(LOCAL); } catch { /* storage unavailable */ }
  }
  function saveLocal() {
    try { localStorage.setItem(LOCAL, JSON.stringify(local.value)); } catch { /* storage unavailable */ }
  }
  function snooze(id: string, days: number) {
    const until = new Date(Date.now() + days * 86400000).toISOString().slice(0, 10);
    local.value.snoozed[id] = until; saveLocal();
  }
  function unsnooze(id: string) { delete local.value.snoozed[id]; saveLocal(); }
  function toggleNotInProd(id: string) {
    if (local.value.notInProd[id]) delete local.value.notInProd[id]; else local.value.notInProd[id] = true;
    saveLocal();
  }
  function setProduction(v: boolean) { local.value.production = v; saveLocal(); }
  function effectiveStatus(f: Finding): Finding["status"] {
    if (local.value.notInProd[f.id]) return "not_in_prod";
    const until = local.value.snoozed[f.id];
    if (until && until >= new Date().toISOString().slice(0, 10)) return "snoozed";
    return "open";
  }
  return { current, local, save, forget, snooze, unsnooze, toggleNotInProd, setProduction, effectiveStatus };
}
