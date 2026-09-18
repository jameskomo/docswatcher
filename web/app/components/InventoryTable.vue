<script setup lang="ts">
import type { Contract, Finding } from "~~/engine/types";
import { KIND_LABEL } from "~/utils/format";
const props = defineProps<{ contracts: Contract[]; findings: Finding[] }>();
const { providerName } = useKnowledge();
const showLow = ref(false);
const filter = ref("");
const affected = computed(() => new Set(props.findings.map((f) => f.contract)));
const rows = computed(() => props.contracts.filter((c) => (showLow.value || c.confidence !== "low") &&
  (!filter.value || (c.provider + " " + c.kind + " " + c.key).toLowerCase().includes(filter.value.toLowerCase()))));
</script>

<template>
  <div class="stack">
    <div class="row between">
      <input id="inventory-filter" class="input" style="max-width: 320px" v-model="filter" placeholder="Filter by provider, kind, or key" aria-label="Filter inventory" />
      <label class="row small" style="gap: 6px"><input id="show-low" type="checkbox" v-model="showLow" /> show low-confidence ({{ contracts.filter(c => c.confidence === 'low').length }})</label>
    </div>
    <div class="table-wrap">
      <table>
        <thead><tr><th>Provider</th><th>Kind</th><th>Key</th><th>Confidence</th><th>Locations</th><th>Status</th></tr></thead>
        <tbody>
          <tr v-for="c in rows" :key="c.id">
            <td>{{ providerName(c.provider) }}</td>
            <td>{{ KIND_LABEL[c.kind] ?? c.kind }}</td>
            <td class="mono">{{ c.key }}<span v-if="c.context?.sdk?.version" class="muted"> · {{ c.context.sdk.version }}</span></td>
            <td>{{ c.confidence }}</td>
            <td class="mono small">{{ c.evidence[0]?.path }}:{{ c.evidence[0]?.line }}<span v-if="c.evidence.length > 1" class="muted"> +{{ c.evidence.length - 1 }}</span></td>
            <td><SeverityChip v-if="affected.has(c.id)" :severity="findings.find(f => f.contract === c.id)!.severity" /><SeverityChip v-else severity="healthy" /></td>
          </tr>
          <tr v-if="!rows.length"><td colspan="6" class="muted">No contracts match.</td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
