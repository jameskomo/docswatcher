<script setup lang="ts">
import type { Finding, Inventory } from "~~/engine/types";
import { fmtDate, nearest } from "~/utils/format";

/**
 * The board. The hero of the site and the only loud surface on it.
 *
 * It answers the product's question before any explanation: how many of this
 * repository's API calls have already stopped working, and when the next one
 * goes. The figures are here rather than in a row of tiles because a tile row
 * is the default treatment and says nothing a sentence cannot.
 *
 * While a scan runs the board keeps its shape and shows progress on the same
 * horizontal axis the ruler will use, so it is never an empty rectangle.
 */
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

/**
 * How much time to draw. A fixed twelve months spends most of its width on
 * empty axis when every deadline is inside two, which makes urgent items look
 * distant. Cover from today to the last deadline plus a month of air, clamped
 * so the ruler never gets uselessly short or longer than a year.
 */
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
      <div class="board-what">
        <component :is="heading ? 'h1' : 'p'" class="subject">
          <NuxtLink v-if="subjectTo && subject" :to="subjectTo">{{ subject }}</NuxtLink>
          <span v-else-if="subject">{{ subject }}</span>
          <span v-else>No repository scanned yet</span>
        </component>
        <p class="says" v-if="note">{{ note }}</p>
      </div>

      <p class="verdict" :class="verdict.tone" v-if="!busy && inventory">
        <span class="n num">{{ verdict.n }}</span>
        <span class="u">{{ verdict.unit }}</span>
      </p>
    </div>

    <div v-if="busy" class="board-scanning" aria-live="polite">
      <span>{{ phase || "Reading files" }}</span>
      <span class="board-bar" :class="{ indeterminate: percent === null || percent === undefined }">
        <span :style="percent !== null && percent !== undefined ? { width: percent + '%' } : undefined"></span>
      </span>
      <span class="num" v-if="percent !== null && percent !== undefined">{{ percent }}%</span>
    </div>

    <div v-else-if="inventory" class="board-ruler">
      <Horizon :findings="findings" variant="board" :today="today" :months="rulerMonths" />
    </div>

    <p class="board-note" v-if="!busy && inventory">
      {{ inventory.stats.filesScanned }} files read in {{ inventory.stats.durationMs }} ms.
      {{ contracts.length }} external {{ contracts.length === 1 ? "contract" : "contracts" }}
      across {{ providers }} {{ providers === 1 ? "provider" : "providers" }}.
      <span v-if="second">{{ second }}.</span>
    </p>
  </section>
</template>
