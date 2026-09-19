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
    <div class="row between" style="align-items: center; gap: var(--s3)">
      <div style="position: relative; flex: 1 1 280px; max-width: 360px">
        <input
          id="inventory-filter"
          class="input"
          style="width: 100%; padding-left: 36px"
          v-model="filter"
          placeholder="Filter by provider, kind, or key..."
          aria-label="Filter inventory"
        />
        <svg
          style="position: absolute; left: 12px; top: 50%; transform: translateY(-50%); width: 14px; height: 14px; fill: var(--ink-faint); pointer-events: none"
          viewBox="0 0 16 16"
        >
          <path d="M11.742 10.344a6.5 6.5 0 1 0-1.397 1.398h-.001c.03.04.062.078.098.115l3.85 3.85a1 1 0 0 0 1.415-1.414l-3.85-3.85a1.007 1.007 0 0 0-.115-.1zM12 6.5a5.5 5.5 0 1 1-11 0 5.5 5.5 0 0 1 11 0z"/>
        </svg>
      </div>

      <label class="row t2 ink-soft" style="gap: var(--s2); cursor: pointer; user-select: none">
        <input id="show-low" type="checkbox" v-model="showLow" style="cursor: pointer" />
        <span>show low confidence ({{ lowCount }})</span>
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
            <td style="font-weight: 700; color: var(--ink-max)">
              {{ providerName(c.provider) }}
            </td>
            <td>
              <span class="mono" style="font-size: var(--t1); color: var(--ink-soft); background: rgba(255, 255, 255, 0.04); padding: 2px 6px; border-radius: 4px; border: 1px solid var(--hair)">
                {{ KIND_LABEL[c.kind] ?? c.kind }}
              </span>
            </td>
            <td class="mono">
              <span class="trunc" style="display: inline-block; max-width: 34ch; vertical-align: bottom" :title="c.key">
                {{ c.key }}
              </span>
              <span v-if="c.context?.sdk?.version" class="ink-faint" style="font-size: 0.85em">&nbsp;&nbsp;v{{ c.context.sdk.version }}</span>
            </td>
            <td>
              <span
                class="mono"
                :style="{
                  color: c.confidence === 'high' ? 'var(--ok)' : (c.confidence === 'medium' ? 'var(--soon)' : 'var(--ink-faint)'),
                  fontSize: 'var(--t1)',
                  fontWeight: 600,
                  textTransform: 'uppercase'
                }"
              >
                {{ c.confidence }}
              </span>
            </td>
            <td class="mono" :title="allLocations(c)">
              <span class="trunc" style="display: inline-block; max-width: 34ch; vertical-align: bottom; color: var(--ink-soft)">
                {{ c.evidence[0]?.path }}:{{ c.evidence[0]?.line }}
              </span>
              <span v-if="c.evidence.length > 1" class="ink-faint" style="font-size: var(--t1)"> +{{ c.evidence.length - 1 }}</span>
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
