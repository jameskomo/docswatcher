<script setup lang="ts">
import type { Finding, Inventory, Severity } from "~~/engine/types";
import { SEVERITY_GLYPH } from "~/utils/format";

/**
 * Two honest renderings of the same data.
 *
 * A node graph needs several nodes before it says anything. With one provider,
 * which is the common case, it was a large empty rectangle with two circles in
 * it. Below the threshold this renders a ledger instead: one row per provider,
 * with its severity split shown as a proportional bar. The graph returns when
 * there are enough nodes for adjacency and weight to carry meaning.
 */
const GRAPH_MIN_PROVIDERS = 3;

const props = defineProps<{ inventory: Inventory; findings: Finding[] }>();
const { providerName } = useKnowledge();

type Health = Severity | "healthy";
const rank: Record<Health, number> = { healthy: 0, info: 1, warning: 2, breaking: 3 };

interface Row {
  id: string;
  name: string;
  contracts: number;
  callSites: number;
  health: Health;
  split: Record<Health, number>;
}

const rows = computed<Row[]>(() => {
  const worst = new Map<string, Severity>();
  for (const f of props.findings) {
    const prev = worst.get(f.contract);
    if (!prev || rank[f.severity] > rank[prev]) worst.set(f.contract, f.severity);
  }
  const byProvider = new Map<string, Row>();
  for (const c of props.inventory.contracts) {
    if (c.confidence === "low") continue;
    let r = byProvider.get(c.provider);
    if (!r) {
      r = {
        id: c.provider, name: providerName(c.provider), contracts: 0, callSites: 0,
        health: "healthy", split: { breaking: 0, warning: 0, info: 0, healthy: 0 },
      };
      byProvider.set(c.provider, r);
    }
    r.contracts++;
    r.callSites += c.evidence.length;
    const h: Health = worst.get(c.id) ?? "healthy";
    r.split[h]++;
    if (rank[h] > rank[r.health]) r.health = h;
  }
  return [...byProvider.values()].sort(
    (a, b) => rank[b.health] - rank[a.health] || b.callSites - a.callSites || a.name.localeCompare(b.name),
  );
});

const useGraph = computed(() => rows.value.length >= GRAPH_MIN_PROVIDERS);
const glyph = (h: Health) => (h === "healthy" ? "✓" : SEVERITY_GLYPH[h]);
const pct = (r: Row, h: Health) => (r.contracts ? (r.split[h] / r.contracts) * 100 : 0);

/* Graph geometry, only used above the threshold. */
const W = 760;
const H = computed(() => (rows.value.length > 6 ? 340 : 280));
const CX = W / 2;
const CY = computed(() => H.value / 2);
const maxCalls = computed(() => Math.max(1, ...rows.value.map((r) => r.callSites)));
const nodes = computed(() =>
  rows.value.map((r, i) => {
    const a = (i / Math.max(1, rows.value.length)) * Math.PI * 2 - Math.PI / 2;
    const radius = Math.min(CX, CY.value) - 62;
    return { ...r, x: CX + Math.cos(a) * radius, y: CY.value + Math.sin(a) * radius, r: 14 + 16 * Math.sqrt(r.callSites / maxCalls.value) };
  }),
);
const colorVar = (h: Health) =>
  h === "breaking" ? "var(--critical)" : h === "warning" ? "var(--warning)" : h === "info" ? "var(--info)" : "var(--good)";
const visibleContracts = computed(() => props.inventory.contracts.filter((c) => c.confidence !== "low").length);
</script>

<template>
  <div class="stack">
    <!-- Few providers: a ledger reads better than a mostly empty canvas. -->
    <div v-if="!useGraph && rows.length" class="ledger">
      <div v-for="r in rows" :key="r.id" class="ledger-row">
        <span class="who">
          <SeverityChip :severity="r.health" />
          <span class="name trunc">{{ r.name }}</span>
        </span>
        <span class="counts">
          {{ r.contracts }} contract{{ r.contracts === 1 ? "" : "s" }} in
          {{ r.callSites }} location{{ r.callSites === 1 ? "" : "s" }}
        </span>
        <span
          class="meter sev-bar"
          role="img"
          :aria-label="`${r.name}: ${r.split.breaking} breaking, ${r.split.warning} warning, ${r.split.info} informational, ${r.split.healthy} healthy`"
        >
          <span class="s-breaking" :style="{ width: pct(r, 'breaking') + '%' }"></span>
          <span class="s-warning" :style="{ width: pct(r, 'warning') + '%' }"></span>
          <span class="s-info" :style="{ width: pct(r, 'info') + '%' }"></span>
          <span class="s-healthy" :style="{ width: pct(r, 'healthy') + '%' }"></span>
        </span>
      </div>
    </div>

    <!-- Enough nodes that adjacency and weight mean something. -->
    <svg
      v-else-if="useGraph"
      class="chart"
      :viewBox="`0 0 ${W} ${H}`"
      role="img"
      :aria-label="`Map of ${nodes.length} external providers used by ${inventory.repo.owner}/${inventory.repo.name}`"
    >
      <line
        v-for="n in nodes" :key="'l' + n.id"
        :x1="CX" :y1="CY" :x2="n.x" :y2="n.y"
        :stroke="colorVar(n.health)"
        :stroke-width="1 + 4 * Math.sqrt(n.callSites / maxCalls)"
        stroke-opacity="0.4" stroke-linecap="round"
      />
      <circle :cx="CX" :cy="CY" r="30" fill="var(--surface-2)" stroke="var(--line-strong)" />
      <text :x="CX" :y="CY - 1" text-anchor="middle" style="font-weight: 600; fill: var(--ink)">this repo</text>
      <text :x="CX" :y="CY + 14" text-anchor="middle" style="font-size: 10px">{{ visibleContracts }} contracts</text>
      <g v-for="n in nodes" :key="n.id">
        <circle :cx="n.x" :cy="n.y" :r="n.r + 3" fill="var(--chart-surface)" />
        <circle :cx="n.x" :cy="n.y" :r="n.r" :fill="colorVar(n.health)" fill-opacity="0.16" :stroke="colorVar(n.health)" stroke-width="2" />
        <text :x="n.x" :y="n.y + 5" text-anchor="middle" style="font-size: 13px; font-weight: 700" :style="{ fill: colorVar(n.health) }">{{ glyph(n.health) }}</text>
        <text :x="n.x" :y="n.y < CY - 4 ? n.y - n.r - 20 : n.y + n.r + 16" text-anchor="middle" style="font-weight: 600; fill: var(--ink)">{{ n.name }}</text>
        <text :x="n.x" :y="n.y < CY - 4 ? n.y - n.r - 7 : n.y + n.r + 29" text-anchor="middle" style="font-size: 11px">{{ n.contracts }} in {{ n.callSites }} place{{ n.callSites === 1 ? "" : "s" }}</text>
      </g>
    </svg>

    <div v-else class="empty">
      <h3>No external providers detected</h3>
      <p class="t2">This repository calls nothing DocsWatcher tracks, or the scan found only low-confidence mentions.</p>
    </div>

    <div class="legend" v-if="rows.length">
      <span><span class="sw" style="background: var(--critical)"></span>breaking change pending</span>
      <span><span class="sw" style="background: var(--warning)"></span>behaviour change</span>
      <span><span class="sw" style="background: var(--good)"></span>healthy</span>
      <span class="ink-faint">{{ useGraph ? "node size and line weight follow call sites in code" : "bar width is the share of each provider's contracts" }}</span>
    </div>
  </div>
</template>
