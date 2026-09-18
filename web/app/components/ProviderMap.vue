<script setup lang="ts">
import type { Finding, Inventory } from "~~/engine/types";
import { SEVERITY_GLYPH } from "~/utils/format";

const props = defineProps<{ inventory: Inventory; findings: Finding[] }>();
const { providerName } = useKnowledge();

type Health = "breaking" | "warning" | "info" | "healthy";
interface Node { id: string; name: string; contracts: number; callSites: number; health: Health; x: number; y: number; r: number; }

const W = 760, H = 340, CX = W / 2, CY = H / 2;
const hover = ref<string | null>(null);

const nodes = computed<Node[]>(() => {
  const byProvider = new Map<string, { contracts: number; callSites: number; health: Health }>();
  for (const c of props.inventory.contracts) {
    if (c.confidence === "low") continue;
    const p = byProvider.get(c.provider) ?? { contracts: 0, callSites: 0, health: "healthy" };
    p.contracts++; p.callSites += c.evidence.length; byProvider.set(c.provider, p);
  }
  const rank: Record<Health, number> = { healthy: 0, info: 1, warning: 2, breaking: 3 };
  for (const f of props.findings) {
    const p = byProvider.get(f.contract.slice(0, f.contract.indexOf(":")));
    if (p && rank[f.severity] > rank[p.health]) p.health = f.severity;
  }
  const ids = [...byProvider.keys()].sort();
  const maxCalls = Math.max(1, ...[...byProvider.values()].map((p) => p.callSites));
  const R = Math.min(CX, CY) - 58;
  return ids.map((id, i) => {
    const p = byProvider.get(id)!;
    // start on the right so a single node never sits on the vertical line to the center
    const a = (i / Math.max(1, ids.length)) * Math.PI * 2;
    return { id, name: providerName(id), contracts: p.contracts, callSites: p.callSites, health: p.health,
      x: CX + Math.cos(a) * R, y: CY + Math.sin(a) * R, r: 16 + 18 * Math.sqrt(p.callSites / maxCalls) };
  });
});
const colorVar = (h: Health) => h === "breaking" ? "var(--critical)" : h === "warning" ? "var(--warning)" : h === "info" ? "var(--info)" : "var(--good)";
const glyph = (h: Health) => h === "healthy" ? "✓" : SEVERITY_GLYPH[h];
const repoName = computed(() => `${props.inventory.repo.owner}/${props.inventory.repo.name}`);
</script>

<template>
  <div class="stack">
    <svg class="chart" :viewBox="`0 0 ${W} ${H}`" role="img" :aria-label="`Map of ${nodes.length} external providers used by ${repoName}`">
      <g v-for="n in nodes" :key="'l' + n.id">
        <line :x1="CX" :y1="CY" :x2="n.x" :y2="n.y" :stroke="colorVar(n.health)" :stroke-width="1 + 5 * Math.sqrt(n.callSites / Math.max(1, ...nodes.map(m => m.callSites)))" stroke-opacity="0.45" stroke-linecap="round" />
      </g>
      <g>
        <circle :cx="CX" :cy="CY" r="34" fill="var(--surface-2)" stroke="var(--line-strong)" stroke-width="1.5" />
        <text :x="CX" :y="CY - 2" text-anchor="middle" style="font-weight: 600; fill: var(--ink)">your repo</text>
        <text :x="CX" :y="CY + 14" text-anchor="middle" style="font-size: 10px">{{ inventory.contracts.filter(c => c.confidence !== 'low').length }} contracts</text>
      </g>
      <g v-for="n in nodes" :key="n.id" @mouseenter="hover = n.id" @mouseleave="hover = null" style="cursor: default">
        <circle :cx="n.x" :cy="n.y" :r="n.r + 3" fill="var(--chart-surface)" />
        <circle :cx="n.x" :cy="n.y" :r="n.r" :fill="colorVar(n.health)" fill-opacity="0.18" :stroke="colorVar(n.health)" stroke-width="2" />
        <text :x="n.x" :y="n.y + 5" text-anchor="middle" style="font-size: 14px; font-weight: 700" :style="{ fill: colorVar(n.health) }">{{ glyph(n.health) }}</text>
        <text :x="n.x" :y="n.y < CY - 4 ? n.y - n.r - 22 : n.y + n.r + 16" text-anchor="middle" style="font-weight: 600; fill: var(--ink)">{{ n.name }}</text>
        <text :x="n.x" :y="n.y < CY - 4 ? n.y - n.r - 8 : n.y + n.r + 30" text-anchor="middle" style="font-size: 11px">{{ n.contracts }} contract{{ n.contracts === 1 ? "" : "s" }} · {{ n.callSites }} location{{ n.callSites === 1 ? "" : "s" }}</text>
      </g>
      <text v-if="!nodes.length" :x="CX" :y="CY + 60" text-anchor="middle">No external providers detected.</text>
    </svg>
    <div class="legend" aria-label="Legend">
      <span><span class="sw" style="background: var(--critical)"></span>✕ breaking change pending</span>
      <span><span class="sw" style="background: var(--warning)"></span>! behavior change</span>
      <span><span class="sw" style="background: var(--good)"></span>✓ healthy</span>
      <span class="muted">node size and line weight follow the number of locations in code</span>
    </div>
  </div>
</template>
