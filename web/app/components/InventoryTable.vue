<script setup lang="ts">
import type { Contract, Evidence, Finding, RepoRef } from "~~/engine/types";
import { KIND_LABEL } from "~/utils/format";
import { safeUrl } from "~/utils/safeUrl";

const props = defineProps<{ contracts: Contract[]; findings: Finding[]; repo?: RepoRef | null }>();
const { providerName } = useKnowledge();
const showLow = ref(false);
const filter = ref("");

/** A long inventory buries the rest of the page, so only this many rows show until asked. */
const COLLAPSED_ROWS = 15;
const expanded = ref(false);
/** Contract ids whose full location list is open. */
const openRows = ref(new Set<string>());
const toggleRow = (id: string) => {
  const next = new Set(openRows.value);
  next.has(id) ? next.delete(id) : next.add(id);
  openRows.value = next;
};

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

/**
 * A permanent link to the exact line on GitHub, or null when there is nothing to link to.
 * Only a public GitHub scan has a repo: a local folder or a bundled fixture has none, and
 * linking those would point at a repository the visitor never scanned. The sha pins the
 * line to the commit that was actually read, so the link cannot drift as the branch moves.
 */
function blobUrl(e: Evidence): string | null {
  const r = props.repo;
  if (!r || r.host !== "github" || !r.owner || !r.name) return null;
  const at = r.sha || r.ref?.replace(/^refs\/heads\//, "");
  if (!at) return null;
  const path = e.path.split("/").map(encodeURIComponent).join("/");
  return safeUrl(
    `https://github.com/${encodeURIComponent(r.owner)}/${encodeURIComponent(r.name)}/blob/${encodeURIComponent(at)}/${path}#L${e.line}`,
  );
}

const rowsAfterCollapse = computed(() =>
  // A filter is a deliberate narrowing, so it shows everything it matched.
  filter.value || expanded.value ? rows.value : rows.value.slice(0, COLLAPSED_ROWS),
);
const hiddenCount = computed(() => rows.value.length - rowsAfterCollapse.value.length);
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
          <template v-for="c in rowsAfterCollapse" :key="c.id">
          <tr>
            <td style="font-weight: 700; color: var(--ink-max)">
              {{ providerName(c.provider) }}
            </td>
            <td>
              <span class="code-pill">
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
              <a
                v-if="c.evidence[0] && blobUrl(c.evidence[0])"
                class="trunc loc"
                :href="blobUrl(c.evidence[0])!"
                target="_blank"
                rel="noopener noreferrer"
              >{{ c.evidence[0].path }}:{{ c.evidence[0].line }}</a>
              <span v-else class="trunc loc">{{ c.evidence[0]?.path }}:{{ c.evidence[0]?.line }}</span>
              <button
                v-if="c.evidence.length > 1"
                type="button"
                class="more"
                :aria-expanded="openRows.has(c.id)"
                @click="toggleRow(c.id)"
              >{{ openRows.has(c.id) ? "less" : `+${c.evidence.length - 1} more` }}</button>
            </td>
            <td>
              <SeverityChip v-if="affected.has(c.id)" :severity="affected.get(c.id)!.severity" />
              <SeverityChip v-else severity="healthy" />
            </td>
          </tr>
          <tr v-if="openRows.has(c.id)" class="locations">
            <td colspan="6">
              <ul>
                <li v-for="(e, i) in c.evidence" :key="`${e.path}:${e.line}:${i}`" class="mono t2">
                  <a v-if="blobUrl(e)" :href="blobUrl(e)!" target="_blank" rel="noopener noreferrer">{{ e.path }}:{{ e.line }}</a>
                  <span v-else>{{ e.path }}:{{ e.line }}</span>
                </li>
              </ul>
            </td>
          </tr>
          </template>
        </tbody>
      </table>
      <button v-if="hiddenCount" type="button" class="btn ghost show-all" @click="expanded = true">
        Show all {{ rows.length }} contracts
      </button>
      <button v-else-if="expanded && !filter && rows.length > COLLAPSED_ROWS" type="button" class="btn ghost show-all" @click="expanded = false">
        Show fewer
      </button>
    </div>

    <div v-else class="empty">
      <h3>{{ filter ? "No contracts match that filter" : "Nothing in the inventory" }}</h3>
      <p class="t2" v-if="filter">Clear the filter to see all {{ contracts.length }} contracts.</p>
      <p class="t2" v-else-if="lowCount">Only low-confidence mentions were found. Tick the box above to see them.</p>
      <p class="t2" v-else>This scan found no external API contracts in the files it read.</p>
    </div>
  </div>
</template>

<style scoped>
.loc { display: inline-block; max-width: 34ch; vertical-align: bottom; color: var(--ink-soft); }
a.loc:hover { color: var(--ink); text-decoration: underline; }
.more {
  margin-left: var(--s2);
  background: none;
  border: 0;
  padding: 0;
  font: inherit;
  font-size: var(--t1);
  color: var(--ink-faint);
  cursor: pointer;
}
.more:hover { color: var(--ink); }
.more:focus-visible { outline: 2px solid var(--ink-accent); outline-offset: 2px; }
.locations td { padding-top: 0; }
.locations ul { margin: 0; padding-left: var(--s5); }
.locations li { color: var(--ink-soft); line-height: 1.7; }
.locations a { color: var(--ink-soft); }
.locations a:hover { color: var(--ink); text-decoration: underline; }
.show-all { margin-top: var(--s3); }
</style>
