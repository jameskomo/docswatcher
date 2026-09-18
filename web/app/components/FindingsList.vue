<script setup lang="ts">
import type { Finding, Severity } from "~~/engine/types";
import { contractLabel, daysLabel, encodeId, fmtDate, KIND_LABEL, SEVERITY_LABEL } from "~/utils/format";

const props = defineProps<{ findings: Finding[]; compact?: boolean; showActions?: boolean }>();
const { change, providerName } = useKnowledge();
const store = useScanStore();

const groups = computed(() => {
  const order: Severity[] = ["breaking", "warning", "info"];
  return order.map((sev) => ({
    sev,
    heading: sev === "breaking" ? "Breaking · act before a date" : sev === "warning" ? "Warning · behavior changed" : "Informational",
    items: props.findings.filter((f) => f.severity === sev),
  })).filter((g) => g.items.length);
});

function what(f: Finding): string {
  const c = contractLabel(f.contract);
  return `${KIND_LABEL[c.kind] ?? c.kind} ${c.key}`;
}
function migrationLine(f: Finding): string | null {
  const ch = change(f.change);
  if (!ch?.migration) return null;
  return ch.migration.replacement ? `Provider guidance: migrate to ${ch.migration.replacement}` : ch.migration.notes ?? null;
}
</script>

<template>
  <div class="stack" v-if="findings.length">
    <template v-for="g in groups" :key="g.sev">
      <h3 class="row" style="gap: 8px"><SeverityChip :severity="g.sev" /> <span>{{ g.heading }}</span> <span class="muted small num">{{ g.items.length }}</span></h3>
      <article v-for="f in g.items" :key="f.id" class="finding" :class="f.severity">
        <div class="stripe" aria-hidden="true"></div>
        <div class="body">
          <div class="title">
            <strong>{{ providerName(contractLabel(f.contract).provider) }}</strong>
            <span class="mono">{{ what(f) }}</span>
            <span class="muted">·</span>
            <span>{{ change(f.change)?.title }}</span>
            <span v-if="store.effectiveStatus(f) !== 'open'" class="chip neutral">{{ store.effectiveStatus(f) === 'snoozed' ? 'snoozed' : 'not in prod' }}</span>
          </div>
          <div class="meta">
            <span v-if="f.effective"><span class="muted">{{ f.daysRemaining !== null && f.daysRemaining < 0 ? "effective since" : "effective" }}</span> {{ fmtDate(f.effective) }} <span class="num">({{ daysLabel(f.daysRemaining) }})</span></span>
            <span v-else class="muted">no date announced</span>
            <span v-if="migrationLine(f)">{{ migrationLine(f) }}</span>
            <a v-if="change(f.change)?.migration?.guide" :href="change(f.change)!.migration!.guide!" target="_blank" rel="noopener">migration guide ↗</a>
          </div>
          <div class="evidence" v-if="!compact">
            <div v-for="e in f.evidence.slice(0, 4)" :key="e.path + e.line + e.column"><span class="loc">{{ e.path }}:{{ e.line }}</span> <span class="muted">{{ e.snippet }}</span></div>
            <div v-if="f.evidence.length > 4" class="muted">+{{ f.evidence.length - 4 }} more locations</div>
          </div>
          <div v-else class="evidence"><span class="loc">{{ f.evidence[0]?.path }}:{{ f.evidence[0]?.line }}</span><span v-if="f.evidence.length > 1" class="muted"> and {{ f.evidence.length - 1 }} more</span></div>
          <div class="actions" v-if="showActions">
            <NuxtLink class="btn sm primary" :to="`/app/findings/${encodeId(f.id)}`">Open fix</NuxtLink>
            <button class="btn sm" @click="store.effectiveStatus(f) === 'snoozed' ? store.unsnooze(f.id) : store.snooze(f.id, 30)">{{ store.effectiveStatus(f) === 'snoozed' ? 'Unsnooze' : 'Snooze 30d' }}</button>
            <button class="btn sm" @click="store.toggleNotInProd(f.id)">{{ store.effectiveStatus(f) === 'not_in_prod' ? 'Used in prod' : 'Not used in prod' }}</button>
          </div>
        </div>
      </article>
    </template>
  </div>
  <div v-else class="notice ok">Nothing pending. Every contract in this repository is healthy against the current knowledge base.</div>
</template>
