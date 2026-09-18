import type { Finding, Inventory } from "~~/engine/types";

export interface StoredScan {
  inventory: Inventory;
  findings: Finding[];
  source: { label: string; kind: "sample" | "github" | "folder" };
  at: string;
}
export interface LocalState {
  production: boolean;
  snoozed: Record<string, string>;   // finding id -> until date
  notInProd: Record<string, true>;
}

const KEY = "docwatcher.lastScan";
const LOCAL = "docwatcher.local";

const current = ref<StoredScan | null>(null);
const local = ref<LocalState>({ production: true, snoozed: {}, notInProd: {} });
let loaded = false;

function load() {
  if (loaded || typeof window === "undefined") return;
  loaded = true;
  try { const raw = localStorage.getItem(KEY); if (raw) current.value = JSON.parse(raw); } catch { /* storage unavailable */ }
  try { const raw = localStorage.getItem(LOCAL); if (raw) local.value = { ...local.value, ...JSON.parse(raw) }; } catch { /* storage unavailable */ }
}

export function useScanStore() {
  load();
  function save(scan: StoredScan) {
    current.value = scan;
    try { localStorage.setItem(KEY, JSON.stringify(scan)); } catch { /* storage unavailable */ }
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
  return { current, local, save, snooze, unsnooze, toggleNotInProd, setProduction, effectiveStatus };
}
