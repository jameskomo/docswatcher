<script setup lang="ts">
import type { Finding, Severity } from "~~/engine/types";
import { contractLabel, daysParts, encodeId, fmtDate, KIND_LABEL } from "~/utils/format";

/**
 * Findings, ordered by when they bite and anchored on the day count.
 *
 * The number of days is the largest thing in the row because it is the only
 * figure that changes a reader's behaviour. Everything else, provider, key,
 * migration, file and line, is support for that number.
 *
 * The whole row is the link to its detail page: a plain anchor, so keyboard,
 * middle click and focus all work, and nothing competes for the click.
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
        sev === "breaking" ? "Stops working on a date"
        : sev === "warning" ? "Behaviour changed"
        : "Worth knowing",
      items: [...props.findings.filter((f) => f.severity === sev)].sort((a, b) => {
        if (a.effective && b.effective) return a.effective < b.effective ? -1 : 1;
        return a.effective ? -1 : b.effective ? 1 : 0;
      }),
    }))
    .filter((g) => g.items.length);
});

function subject(f: Finding): string {
  const c = contractLabel(f.contract);
  return `${KIND_LABEL[c.kind] ?? c.kind} ${c.key}`;
}

function guidance(f: Finding): string | null {
  const ch = change(f.change);
  if (!ch?.migration) return null;
  return ch.migration.replacement ? `Move to ${ch.migration.replacement}` : ch.migration.notes ?? null;
}

const shown = computed(() => (props.compact ? 1 : 2));
</script>

<template>
  <div v-if="findings.length" class="findings">
    <template v-for="g in groups" :key="g.sev">
      <h3 class="findings-group">
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
        <span class="when">
          <span class="count num">{{ daysParts(f.daysRemaining).n }}</span>
          <span class="unit">{{ daysParts(f.daysRemaining).unit }}</span>
          <span class="on num" v-if="f.effective">{{ fmtDate(f.effective) }}</span>
        </span>

        <span class="finding-body">
          <span class="headline">
            {{ change(f.change)?.title }}
            <span class="state" v-if="store.effectiveStatus(f) !== 'open'">
              {{ store.effectiveStatus(f) === "snoozed" ? "snoozed" : "not in production" }}
            </span>
          </span>
          <span class="subject">{{ providerName(contractLabel(f.contract).provider) }} {{ subject(f) }}</span>
          <span class="guidance" v-if="guidance(f)">{{ guidance(f) }}</span>
          <span class="where">
            <span v-for="e in f.evidence.slice(0, shown)" :key="e.path + e.line + e.column">{{ e.path }}:{{ e.line }}</span>
            <span v-if="f.evidence.length > shown">
              and {{ f.evidence.length - shown }} more
              {{ f.evidence.length - shown === 1 ? "place" : "places" }}
            </span>
          </span>
        </span>
      </NuxtLink>
    </template>
  </div>

  <div v-else class="empty">
    <SeverityChip severity="healthy" />
    <h3>Nothing in this repository has a deadline</h3>
    <p>
      Every contract found here is healthy against the current knowledge base. Scan another
      repository, or read how the matching works.
    </p>
  </div>
</template>
