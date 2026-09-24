<script setup lang="ts">
import type { FindingAction, RepoFinding } from "~/utils/orgApi";
import { contractLabel, daysParts, fmtDate, KIND_LABEL } from "~/utils/format";
import { safeUrl } from "~/utils/safeUrl";

/**
 * A repository's findings as the server holds them, with the three things a team does about one:
 * snooze it, say the code does not run in production, or ask for a fix pull request. Each row
 * reports its own outcome. Everything is rendered as text; nothing from the server is HTML.
 */
const props = defineProps<{
  findings: RepoFinding[];
  /** Key of the row whose action is in flight, so only that row's buttons wait. */
  busy?: string | null;
  /** Outcome per row, by key: what happened, or why it did not. */
  outcome?: Record<string, { ok: boolean; text: string }>;
}>();
const emit = defineEmits<{ act: [r: RepoFinding, action: FindingAction] }>();
const { providerName } = useKnowledge();

const keyOf = (r: RepoFinding) => `${r.repoId}|${r.finding.contract}|${r.finding.change}`;
const subject = (r: RepoFinding) => {
  const c = contractLabel(r.finding.contract);
  return `${providerName(c.provider)} ${KIND_LABEL[c.kind] ?? c.kind} ${c.key}`;
};
const stateLabel = (r: RepoFinding) =>
  r.finding.status === "snoozed" ? `snoozed until ${fmtDate(r.finding.snoozedUntil)}`
  : r.finding.status === "not_in_prod" ? "not in production"
  : r.finding.status === "fixed" ? "fixed"
  : null;

const sorted = computed(() =>
  [...props.findings].sort((a, b) => {
    const ea = a.finding.effective ?? "9999", eb = b.finding.effective ?? "9999";
    return ea < eb ? -1 : ea > eb ? 1 : a.finding.contract.localeCompare(b.finding.contract);
  }),
);
</script>

<template>
  <div v-if="findings.length" class="findings org-findings">
    <div v-for="r in sorted" :key="keyOf(r)" class="finding-row" :class="r.finding.severity" data-testid="org-finding">
      <span class="when">
        <span class="count num">{{ daysParts(r.finding.daysRemaining).n }}</span>
        <span class="unit">{{ daysParts(r.finding.daysRemaining).unit }}</span>
        <span class="on num" v-if="r.finding.effective">{{ fmtDate(r.finding.effective) }}</span>
      </span>

      <span class="finding-body">
        <span class="headline">
          {{ r.changeTitle }}
          <span class="state" v-if="stateLabel(r)">{{ stateLabel(r) }}</span>
        </span>
        <span class="subject">{{ subject(r) }}</span>
        <span class="where">
          <span v-for="e in r.finding.evidence.slice(0, 3)" :key="e.path + e.line + e.column">{{ e.path }}:{{ e.line }}</span>
          <span v-if="r.finding.evidence.length > 3" style="color: var(--ink-soft)">
            and {{ r.finding.evidence.length - 3 }} more
          </span>
        </span>
        <span v-if="safeUrl(r.finding.fixPr)" class="t2">
          <a :href="safeUrl(r.finding.fixPr)!" rel="noopener noreferrer" target="_blank">Fix pull request</a>
        </span>

        <span class="actions" v-if="r.finding.status !== 'fixed'">
          <button type="button" class="btn quiet" :disabled="busy === keyOf(r)" @click="emit('act', r, 'snooze')">Snooze 30 days</button>
          <button type="button" class="btn quiet" :disabled="busy === keyOf(r)" @click="emit('act', r, 'not-in-prod')">Not in production</button>
          <button type="button" class="btn" :disabled="busy === keyOf(r)" @click="emit('act', r, 'fix')">Request a fix</button>
        </span>
        <span
          v-if="outcome?.[keyOf(r)]"
          class="t2"
          :class="outcome[keyOf(r)]!.ok ? 'ink-soft' : 'notice bad'"
          role="status"
          data-testid="org-finding-outcome"
        >{{ outcome[keyOf(r)]!.text }}</span>
      </span>
    </div>
  </div>
  <div v-else class="empty">
    <div style="margin-bottom: var(--s2)"><SeverityChip severity="healthy" /></div>
    <h3>Nothing in this repository has a deadline</h3>
    <p>Every contract DocsWatcher found here is healthy against the current knowledge base.</p>
  </div>
</template>

<style scoped>
.org-findings .finding-row:hover { transform: none; }
.actions { display: flex; flex-wrap: wrap; gap: var(--s2); margin-top: var(--s2); }
.actions .btn { padding: 6px 12px; font-size: var(--t1); }
</style>
