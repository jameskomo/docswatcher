<script setup lang="ts">
import type { Contract, Finding } from "~~/engine/types";
import { KIND_LABEL } from "~/utils/format";

const props = defineProps<{ contracts: Contract[]; findings: Finding[] }>();
const { providerName } = useKnowledge();
const showLow = ref(false);
const filter = ref("");

const lowCount = computed(() => props.contracts.filter((c) => c.confidence === "low").length);
const affected = computed(() => {
  const m = new Map<string, Finding>();
  for (const f of props.findings) if (!m.has(f.contract)) m.set(f.contract, f);
  return m;
});
const rows = computed(() =>
  props.contracts.filter(
    (c) =>
      (showLow.value || c.confidence !== "low") &&
      (!filter.value || `${c.provider} ${c.kind} ${c.key}`.toLowerCase().includes(filter.value.toLowerCase())),
  ),
);
/** Full location list, used as the title so a truncated cell stays inspectable. */
const allLocations = (c: Contract) => c.evidence.map((e) => `${e.path}:${e.line}`).join("\n");
</script>

<template>
  <div class="stack">
    <div class="row between">
      <input
        id="inventory-filter" class="input" style="max-width: 300px"
        v-model="filter" placeholder="Filter by provider, kind, or key" aria-label="Filter inventory"
      />
      <label class="row t2 ink-faint" style="gap: var(--s1)">
        <input id="show-low" type="checkbox" v-model="showLow" />
        show low confidence ({{ lowCount }})
      </label>
    </div>

    <div class="table-wrap" v-if="rows.length">
      <table>
        <thead>
          <tr>
            <th>Provider</th>
            <th>Kind</th>
            <th>Key</th>
            <th>Conf.</th>
            <th>Locations</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in rows" :key="c.id">
            <td>{{ providerName(c.provider) }}</td>
            <td class="ink-faint">{{ KIND_LABEL[c.kind] ?? c.kind }}</td>
            <td class="mono">
              <span class="trunc" style="display: inline-block; max-width: 34ch; vertical-align: bottom" :title="c.key">{{ c.key }}</span>
              <span v-if="c.context?.sdk?.version" class="ink-faint">&nbsp;&nbsp;{{ c.context.sdk.version }}</span>
            </td>
            <td class="ink-faint">{{ c.confidence }}</td>
            <td class="mono" :title="allLocations(c)">
              <span class="trunc" style="display: inline-block; max-width: 34ch; vertical-align: bottom">{{ c.evidence[0]?.path }}:{{ c.evidence[0]?.line }}</span>
              <span v-if="c.evidence.length > 1" class="ink-faint"> +{{ c.evidence.length - 1 }}</span>
            </td>
            <td>
              <SeverityChip v-if="affected.has(c.id)" :severity="affected.get(c.id)!.severity" />
              <SeverityChip v-else severity="healthy" />
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-else class="empty">
      <h3>{{ filter ? "No contracts match that filter" : "Nothing in the inventory" }}</h3>
      <p class="t2" v-if="filter">Clear the filter to see all {{ contracts.length }} contracts.</p>
      <p class="t2" v-else-if="lowCount">Only low-confidence mentions were found. Tick the box above to see them.</p>
      <p class="t2" v-else>This scan found no external API contracts in the files it read.</p>
    </div>
  </div>
</template>
