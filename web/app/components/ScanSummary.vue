<script setup lang="ts">
import type { Finding, Inventory, Severity } from "~~/engine/types";
import { daysLabel, fmtDate, nearest } from "~/utils/format";

/**
 * The whole scan in one line, plus a bar showing what share of the inventory is
 * affected. This replaces a row of five bordered tiles: the figures matter, the
 * boxes did not, and the tiles cost six times the vertical space.
 */
const props = defineProps<{ inventory: Inventory; findings: Finding[] }>();

const visible = computed(() => props.inventory.contracts.filter((c) => c.confidence !== "low"));
const low = computed(() => props.inventory.contracts.length - visible.value.length);
const providers = computed(() => new Set(visible.value.map((c) => c.provider)).size);

const bySev = computed(() => {
  const m: Record<Severity, number> = { breaking: 0, warning: 0, info: 0 };
  for (const f of props.findings) m[f.severity]++;
  return m;
});

/** Share of contracts carrying each severity, worst wins, remainder is healthy. */
const bar = computed(() => {
  const rank: Record<Severity, number> = { info: 1, warning: 2, breaking: 3 };
  const worst = new Map<string, Severity>();
  for (const f of props.findings) {
    const prev = worst.get(f.contract);
    if (!prev || rank[f.severity] > rank[prev]) worst.set(f.contract, f.severity);
  }
  const total = Math.max(1, visible.value.length);
  const count = (s: Severity) => visible.value.filter((c) => worst.get(c.id) === s).length;
  const breaking = count("breaking"), warning = count("warning"), info = count("info");
  const healthy = Math.max(0, visible.value.length - breaking - warning - info);
  const pct = (n: number) => (n / total) * 100;
  return { breaking: pct(breaking), warning: pct(warning), info: pct(info), healthy: pct(healthy),
    counts: { breaking, warning, info, healthy } };
});

const next = computed(() => nearest(props.findings));
</script>

<template>
  <div class="summary" id="scan-summary">
    <div class="figures">
      <span class="figure">
        <span class="v">{{ visible.length }}</span>
        <span class="k">external contract{{ visible.length === 1 ? "" : "s" }}<span v-if="low"> · {{ low }} low confidence</span></span>
      </span>
      <span class="figure">
        <span class="v">{{ providers }}</span>
        <span class="k">provider{{ providers === 1 ? "" : "s" }}</span>
      </span>
      <span class="figure" :class="{ 'is-critical': bySev.breaking }">
        <span class="v">{{ bySev.breaking }}</span>
        <span class="k">breaking</span>
      </span>
      <span class="figure" :class="{ 'is-warning': bySev.warning }">
        <span class="v">{{ bySev.warning }}</span>
        <span class="k">warning{{ bySev.warning === 1 ? "" : "s" }}</span>
      </span>
      <span class="figure" v-if="next">
        <span class="v">{{ daysLabel(next.daysRemaining).replace(/^in /, "") }}</span>
        <span class="k">until {{ fmtDate(next.effective) }}</span>
      </span>
      <span class="figure" v-else>
        <span class="v">—</span>
        <span class="k">no upcoming deadline</span>
      </span>
    </div>
    <div
      class="sev-bar"
      role="img"
      :aria-label="`${bar.counts.breaking} breaking, ${bar.counts.warning} warning, ${bar.counts.info} informational, ${bar.counts.healthy} healthy of ${visible.length} contracts`"
    >
      <span class="s-breaking" :style="{ width: bar.breaking + '%' }"></span>
      <span class="s-warning" :style="{ width: bar.warning + '%' }"></span>
      <span class="s-info" :style="{ width: bar.info + '%' }"></span>
      <span class="s-healthy" :style="{ width: bar.healthy + '%' }"></span>
    </div>
  </div>
</template>
