<script setup lang="ts">
import type { Finding, Severity } from "~~/engine/types";
import { contractLabel, daysLabel, encodeId, fmtDate, KIND_LABEL } from "~/utils/format";

/**
 * Findings are rows, and the whole row is the link to its detail page.
 *
 * The previous design repeated three buttons on every card, which was noisy and
 * left no obvious target. Snooze and "not in production" now live on the detail
 * page, so nothing competes with the row's own click and the row can stay a
 * plain anchor: keyboard reachable, middle clickable, and focusable.
 */
const props = defineProps<{ findings: Finding[]; compact?: boolean }>();
const { change, providerName } = useKnowledge();
const store = useScanStore();

const groups = computed(() => {
  const order: Severity[] = ["breaking", "warning", "info"];
  return order
    .map((sev) => ({
      sev,
      heading:
        sev === "breaking" ? "Breaking · act before a date"
        : sev === "warning" ? "Warning · behaviour changed"
        : "Informational",
      items: props.findings.filter((f) => f.severity === sev),
    }))
    .filter((g) => g.items.length);
});

function what(f: Finding): string {
  const c = contractLabel(f.contract);
  return `${KIND_LABEL[c.kind] ?? c.kind} ${c.key}`;
}
function migrationLine(f: Finding): string | null {
  const ch = change(f.change);
  if (!ch?.migration) return null;
  return ch.migration.replacement ? `migrate to ${ch.migration.replacement}` : ch.migration.notes ?? null;
}
const evidenceShown = computed(() => (props.compact ? 1 : 2));
</script>

<template>
  <div v-if="findings.length" class="findings">
    <template v-for="g in groups" :key="g.sev">
      <h3 class="findings-group-head">
        <SeverityChip :severity="g.sev" />
        <span>{{ g.heading }}</span>
        <span class="count">{{ g.items.length }}</span>
      </h3>

      <NuxtLink
        v-for="f in g.items"
        :key="f.id"
        class="finding-row"
        :class="f.severity"
        :to="`/app/findings/${encodeId(f.id)}`"
      >
        <span class="gutter" aria-hidden="true"></span>

        <span class="fr-main">
          <span class="fr-title">
            <span class="what">{{ providerName(contractLabel(f.contract).provider) }}</span>
            <span class="mono ink2">{{ what(f) }}</span>
            <span class="chip neutral" v-if="store.effectiveStatus(f) !== 'open'">
              {{ store.effectiveStatus(f) === "snoozed" ? "snoozed" : "not in production" }}
            </span>
          </span>
          <span class="fr-meta">
            <span>{{ change(f.change)?.title }}</span>
            <span v-if="migrationLine(f)" class="muted">{{ migrationLine(f) }}</span>
          </span>
          <span class="fr-ev">
            <span v-for="e in f.evidence.slice(0, evidenceShown)" :key="e.path + e.line + e.column" class="trunc">
              <span class="loc">{{ e.path }}:{{ e.line }}</span>
            </span>
            <span v-if="f.evidence.length > evidenceShown">
              +{{ f.evidence.length - evidenceShown }} more location{{ f.evidence.length - evidenceShown === 1 ? "" : "s" }}
            </span>
          </span>
        </span>

        <span class="fr-side">
          <span class="when" v-if="f.effective">{{ fmtDate(f.effective) }}</span>
          <span class="when muted" v-else>no date announced</span>
          <span class="when" v-if="f.effective">{{ daysLabel(f.daysRemaining) }}</span>
          <span class="go" aria-hidden="true">details →</span>
        </span>
      </NuxtLink>
    </template>
  </div>

  <div v-else class="empty">
    <SeverityChip severity="healthy" />
    <h3>Nothing pending</h3>
    <p class="small">
      Every contract found in this repository is healthy against the current knowledge base.
      That is a real result, not an empty page.
    </p>
  </div>
</template>
