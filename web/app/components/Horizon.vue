<script setup lang="ts">
import type { Finding } from "~~/engine/types";
import { contractLabel, daysLabel, fmtDate, fmtMonth } from "~/utils/format";

const props = defineProps<{ findings: Finding[]; today?: Date }>();
const { change, providerName } = useKnowledge();

const W = 760, LEFT = 20, RIGHT = 20, TOP = 34, ROW = 22, LANE_GAP = 6;
const today = computed(() => props.today ?? new Date());
const start = computed(() => new Date(Date.UTC(today.value.getUTCFullYear(), today.value.getUTCMonth(), 1)));
const months = computed(() => Array.from({ length: 12 }, (_, i) => {
  const d = new Date(Date.UTC(start.value.getUTCFullYear(), start.value.getUTCMonth() + i, 1));
  return { key: d.toISOString().slice(0, 7), t: d.getTime() };
}));
const end = computed(() => Date.UTC(start.value.getUTCFullYear(), start.value.getUTCMonth() + 12, 1));
const x = (t: number) => LEFT + ((t - start.value.getTime()) / (end.value - start.value.getTime())) * (W - LEFT - RIGHT);

interface Item { f: Finding; t: number; label: string; x: number; lane: number; }
const placed = computed<Item[]>(() => {
  const items = props.findings
    .filter((f) => f.effective)
    .map((f) => {
      const [y, m, d] = f.effective!.split("-").map(Number);
      const t = Date.UTC(y, m - 1, d);
      const c = contractLabel(f.contract);
      return { f, t, label: `${providerName(c.provider)} · ${c.key}`, x: x(Math.max(start.value.getTime(), Math.min(t, end.value - 1))), lane: 0 };
    })
    .filter((i) => i.t < end.value)
    .sort((a, b) => a.t - b.t || a.f.contract.localeCompare(b.f.contract));
  // greedy lane packing so labels do not collide
  const laneRight: number[] = [];
  for (const it of items) {
    const width = 8 + it.label.length * 6.4;
    let lane = laneRight.findIndex((r) => r + 10 < it.x);
    if (lane < 0) { lane = laneRight.length; laneRight.push(0); }
    laneRight[lane] = it.x + width;
    it.lane = lane;
  }
  return items;
});
const lanes = computed(() => Math.max(1, ...placed.value.map((p) => p.lane + 1)));
const H = computed(() => TOP + lanes.value * (ROW + LANE_GAP) + 30);
const todayX = computed(() => x(today.value.getTime()));
const overdue = computed(() => placed.value.filter((p) => p.t < today.value.getTime()).length);
const sevColor = (s: Finding["severity"]) => s === "breaking" ? "var(--critical)" : s === "warning" ? "var(--warning)" : "var(--info)";
const hover = ref<Item | null>(null);
const tableRows = computed(() => [...props.findings].filter((f) => f.effective).sort((a, b) => a.effective! < b.effective! ? -1 : 1));
</script>

<template>
  <div class="stack">
    <svg class="chart" :viewBox="`0 0 ${W} ${H}`" role="img" aria-label="Twelve month timeline of effective dates">
      <g v-for="m in months" :key="m.key">
        <line class="grid" :x1="x(m.t)" :x2="x(m.t)" :y1="TOP - 10" :y2="H - 24" />
        <text :x="x(m.t) + 4" :y="TOP - 14" style="font-size: 11px">{{ fmtMonth(m.key) }}</text>
      </g>
      <line class="axis" :x1="LEFT" :x2="W - RIGHT" :y1="H - 24" :y2="H - 24" />
      <line :x1="todayX" :x2="todayX" :y1="TOP - 10" :y2="H - 24" stroke="var(--accent)" stroke-width="1.5" stroke-dasharray="3 3" />
      <text :x="todayX + 4" :y="H - 8" style="fill: var(--accent); font-weight: 600; font-size: 11px">today</text>
      <g v-for="p in placed" :key="p.f.id" @mouseenter="hover = p" @mouseleave="hover = null">
        <rect :x="p.x - 4" :y="TOP + p.lane * (ROW + LANE_GAP) - 2" :width="8 + p.label.length * 6.4" :height="ROW" rx="4" fill="var(--chart-surface)" />
        <circle :cx="p.x" :cy="TOP + p.lane * (ROW + LANE_GAP) + ROW / 2 - 2" r="5" :fill="sevColor(p.f.severity)" stroke="var(--chart-surface)" stroke-width="2" />
        <text :x="p.x + 10" :y="TOP + p.lane * (ROW + LANE_GAP) + ROW / 2 + 2" style="font-size: 11.5px; fill: var(--ink)">{{ p.label }}</text>
      </g>
      <text v-if="!placed.length" :x="W / 2" :y="TOP + 20" text-anchor="middle">No dated changes in the next twelve months.</text>
    </svg>
    <div v-if="hover" class="notice">
      <strong>{{ change(hover.f.change)?.title }}</strong> · {{ fmtDate(hover.f.effective) }} ({{ daysLabel(hover.f.daysRemaining) }}) · {{ hover.f.evidence.length }} location{{ hover.f.evidence.length === 1 ? "" : "s" }}
    </div>
    <div class="legend">
      <span><span class="sw" style="background: var(--critical)"></span>breaking</span>
      <span><span class="sw" style="background: var(--warning)"></span>warning</span>
      <span><span class="sw" style="background: var(--info)"></span>informational</span>
      <span v-if="overdue" class="muted">{{ overdue }} already effective, shown at the left edge</span>
    </div>
    <details>
      <summary class="small ink2">Table view</summary>
      <div class="table-wrap" style="margin-top: 8px">
        <table>
          <thead><tr><th>Effective</th><th>Days</th><th>Provider</th><th>Contract</th><th>Change</th></tr></thead>
          <tbody>
            <tr v-for="f in tableRows" :key="f.id">
              <td class="num">{{ fmtDate(f.effective) }}</td><td class="num">{{ f.daysRemaining }}</td>
              <td>{{ providerName(contractLabel(f.contract).provider) }}</td><td class="mono">{{ contractLabel(f.contract).key }}</td><td>{{ change(f.change)?.title }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </details>
  </div>
</template>
