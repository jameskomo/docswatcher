<script setup lang="ts">
import type { Finding, Inventory } from "~~/engine/types";
import { fmtDate, nearest } from "~/utils/format";

const props = defineProps<{
  inventory?: Inventory | null;
  findings?: Finding[];
  subject?: string;
  subjectTo?: string;
  note?: string;
  busy?: boolean;
  phase?: string;
  percent?: number | null;
  today?: Date;
  /** True where the scanned repository is the page's subject, so it becomes the h1. */
  heading?: boolean;
}>();

const findings = computed(() => props.findings ?? []);
const contracts = computed(() => (props.inventory?.contracts ?? []).filter((c) => c.confidence !== "low"));
const providers = computed(() => new Set(contracts.value.map((c) => c.provider)).size);

const expired = computed(() => findings.value.filter((f) => f.daysRemaining !== null && f.daysRemaining < 0).length);
const next = computed(() => nearest(findings.value.filter((f) => (f.daysRemaining ?? -1) >= 0)));
const undated = computed(() => findings.value.filter((f) => !f.effective).length);

/** One sentence, chosen by what is actually true of this repository. */
const verdict = computed(() => {
  if (expired.value) {
    return {
      n: String(expired.value),
      unit: expired.value === 1 ? "call already stopped working" : "calls already stopped working",
      tone: "is-overdue",
    };
  }
  if (next.value) {
    return {
      n: String(next.value.daysRemaining),
      unit: `days until ${fmtDate(next.value.effective)}`,
      tone: (next.value.daysRemaining ?? 999) <= 60 ? "is-soon" : "",
    };
  }
  if (undated.value) {
    return { n: String(undated.value), unit: undated.value === 1 ? "change with no date set" : "changes with no date set", tone: "is-soon" };
  }
  return { n: "0", unit: "deadlines in this repository", tone: "is-ok" };
});

const rulerMonths = computed(() => {
  const dated = findings.value.filter((f) => f.daysRemaining !== null && f.daysRemaining > 0);
  if (!dated.length) return 3;
  const furthest = Math.max(...dated.map((f) => f.daysRemaining as number));
  return Math.min(12, Math.max(3, Math.ceil(furthest / 30) + 1));
});

const second = computed(() => {
  const bits: string[] = [];
  if (expired.value && next.value) bits.push(`${next.value.daysRemaining} days until the next one`);
  if (undated.value && (expired.value || next.value)) {
    bits.push(`${undated.value} more without a date`);
  }
  return bits.join(", ");
});
</script>

<template>
  <section class="board" id="results">
    <div class="board-top">
      <component :is="heading ? 'h1' : 'div'" class="subject">
        <NuxtLink v-if="subjectTo && subject" :to="subjectTo">{{ subject }}</NuxtLink>
        <span v-else-if="subject">{{ subject }}</span>
        <span v-else>No repository scanned yet</span>
      </component>
      <p class="verdict-line" :class="verdict.tone" v-if="!busy && inventory">{{ verdict.n }} {{ verdict.unit }}.</p>
      <p class="says" v-if="note">{{ note }}</p>
    </div>

    <!-- The scan in four facts: a ruled strip, not a row of stat cards. -->
    <dl v-if="!busy && inventory" class="board-facts">
      <div :class="{ bad: expired > 0 }">
        <dt>Stopped working</dt>
        <dd class="num">{{ expired }}</dd>
        <span>{{ expired === 0 ? "No expired calls" : expired === 1 ? "call past its date" : "calls past their date" }}</span>
      </div>
      <div :class="{ soon: next && (next.daysRemaining ?? 999) <= 90 }">
        <dt>Next date</dt>
        <dd class="num">{{ next ? `${next.daysRemaining} days` : "None" }}</dd>
        <span>{{ next ? fmtDate(next.effective) : "Nothing still to come" }}</span>
      </div>
      <div>
        <dt>External APIs</dt>
        <dd class="num">{{ contracts.length }}</dd>
        <span>across {{ providers }} {{ providers === 1 ? "provider" : "providers" }}</span>
      </div>
      <div>
        <dt>Files read</dt>
        <dd class="num">{{ inventory.stats.filesScanned }}</dd>
        <span>in {{ inventory.stats.durationMs }} ms, in this browser</span>
      </div>
    </dl>

    <!-- Scanning Radar Animation -->
    <div v-if="busy" class="board-scanning" aria-live="polite">
      <div class="scanning-header">
        <div class="scanning-pulse">
          <div class="radar-spinner"></div>
          <span>{{ phase || "Analyzing AST and manifests" }}</span>
        </div>
        <span class="num mono" style="font-weight: 700; color: var(--ink-accent)" v-if="percent !== null && percent !== undefined">
          {{ percent }}%
        </span>
      </div>
      <div class="board-bar" :class="{ indeterminate: percent === null || percent === undefined }">
        <span :style="percent !== null && percent !== undefined ? { width: percent + '%' } : undefined"></span>
      </div>
    </div>

    <!-- Horizon Timeline -->
    <div v-else-if="inventory" class="board-ruler">
      <Horizon :findings="findings" variant="board" :today="today" :months="rulerMonths" />
    </div>

    <p class="board-note" v-if="!busy && inventory && second">{{ second }}.</p>
  </section>
</template>

<style scoped>
.board-top { display: flex; flex-direction: column; gap: 6px; }
.subject { font-family: var(--face-display); font-stretch: 75%; word-spacing: -0.3em; font-size: clamp(1.5rem, 2.6vw, 2rem); font-weight: 800; line-height: 1.1; overflow-wrap: anywhere; }
.subject a { text-decoration: none; }
.verdict-line { margin: 0; font-size: var(--t4); font-weight: 700; color: var(--ink-max); }
.verdict-line.is-overdue { color: var(--signal-ink); }
.verdict-line.is-soon { color: var(--soon); }
.says { margin: 0; color: var(--ink-faint); font-size: var(--t2); }
.board-facts { margin: 20px 0; display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 180px), 1fr)); border-top: 1.5px solid var(--rule); }
.board-facts > div { display: flex; flex-direction: column; gap: 2px; padding: 14px 16px 14px 0; border-bottom: 1px solid var(--hair); }
.board-facts dt { font-size: var(--t1); font-weight: 500; letter-spacing: 0.06em; text-transform: uppercase; color: var(--ink-faint); }
.board-facts dd { margin: 4px 0 0; font-size: 1.75rem; font-weight: 700; line-height: 1.1; color: var(--ink-max); }
.board-facts span { font-size: var(--t2); color: var(--ink-soft); }
.board-facts .bad dd { color: var(--signal-ink); }
.board-facts .soon dd { color: var(--soon); }
.board-note { margin: 12px 0 0; font-size: var(--t2); color: var(--ink-soft); }
</style>
