<script setup lang="ts">
import type { Finding, Inventory } from "~~/engine/types";
import { daysLabel, fmtDate, nearest } from "~/utils/format";
const props = defineProps<{ inventory: Inventory; findings: Finding[] }>();
const providers = computed(() => new Set(props.inventory.contracts.map((c) => c.provider)).size);
const visible = computed(() => props.inventory.contracts.filter((c) => c.confidence !== "low").length);
const bySev = computed(() => {
  const m = { breaking: 0, warning: 0, info: 0 };
  for (const f of props.findings) m[f.severity]++;
  return m;
});
const next = computed(() => nearest(props.findings));
</script>

<template>
  <div class="tiles">
    <div class="tile"><span class="value">{{ visible }}</span><span class="label">external contracts<span v-if="inventory.contracts.length !== visible" class="muted"> · {{ inventory.contracts.length - visible }} low confidence</span></span></div>
    <div class="tile"><span class="value">{{ providers }}</span><span class="label">providers found</span></div>
    <div class="tile"><span class="value" :style="bySev.breaking ? 'color: var(--critical)' : ''">{{ bySev.breaking }}</span><span class="label">breaking</span></div>
    <div class="tile"><span class="value" :style="bySev.warning ? 'color: var(--warning-ink)' : ''">{{ bySev.warning }}</span><span class="label">warnings</span></div>
    <div class="tile">
      <span class="value">{{ next ? daysLabel(next.daysRemaining).replace(/^in /, "") : "—" }}</span>
      <span class="label">{{ next ? `next deadline · ${fmtDate(next.effective)}` : "no upcoming deadline" }}</span>
    </div>
  </div>
</template>
