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
      <div class="board-what">
        <div class="subject-badge-row">
          <span class="target-badge">
            <span class="mono">TARGET REPO</span>
          </span>
          <span class="registry-badge" v-if="!busy && inventory">
            <span class="live-dot"></span>
            <span>Contract Radar Active</span>
          </span>
        </div>

        <component :is="heading ? 'h1' : 'div'" class="subject">
          <NuxtLink v-if="subjectTo && subject" :to="subjectTo">{{ subject }}</NuxtLink>
          <span v-else-if="subject">{{ subject }}</span>
          <span v-else>No repository scanned yet</span>
        </component>
        <p class="says" v-if="note">{{ note }}</p>
      </div>

      <!-- Verdict Banner -->
      <div class="verdict" :class="verdict.tone" v-if="!busy && inventory">
        <span class="n num">{{ verdict.n }}</span>
        <span class="u">{{ verdict.unit }}</span>
      </div>
    </div>

    <!-- Live Telemetry HUD Grid -->
    <div v-if="!busy && inventory" class="hud-grid">
      <div class="hud-card" :class="{ 'alert-critical': expired > 0 }">
        <div class="hud-label">
          <span>●</span>
          <span>Expired Calls</span>
        </div>
        <div class="hud-val num">
          {{ expired }}
        </div>
        <div class="hud-sub">
          {{ expired === 0 ? "No expired contracts" : (expired === 1 ? "1 call stopped working" : `${expired} calls stopped working`) }}
        </div>
      </div>

      <div class="hud-card" :class="{ 'alert-warning': next && (next.daysRemaining ?? 999) <= 90 }">
        <div class="hud-label">
          <span>▲</span>
          <span>Next Expiry</span>
        </div>
        <div class="hud-val num">
          <template v-if="next">
            {{ next.daysRemaining }}<span style="font-size: 1rem; font-weight: 600; color: var(--ink-soft)">d</span>
          </template>
          <template v-else-if="expired > 0">
            0<span style="font-size: 1rem; font-weight: 600; color: var(--ink-soft)">d</span>
          </template>
          <template v-else>
            —
          </template>
        </div>
        <div class="hud-sub">
          {{ next ? `Due ${fmtDate(next.effective)}` : (expired ? "Active overdue debt" : "No pending dates") }}
        </div>
      </div>

      <div class="hud-card">
        <div class="hud-label">
          <span>◈</span>
          <span>External APIs</span>
        </div>
        <div class="hud-val num">
          {{ contracts.length }}
        </div>
        <div class="hud-sub">
          Across {{ providers }} {{ providers === 1 ? "provider" : "providers" }}
        </div>
      </div>

      <div class="hud-card">
        <div class="hud-label">
          <span>⚡</span>
          <span>Engine Performance</span>
        </div>
        <div class="hud-val num" style="font-size: 1.875rem">
          {{ inventory.stats.durationMs }}<span style="font-size: 1rem; font-weight: 600; color: var(--ink-soft)">ms</span>
        </div>
        <div class="hud-sub">
          {{ inventory.stats.filesScanned }} files · 100% in-browser AST
        </div>
      </div>
    </div>

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

    <!-- Telemetry Footnote -->
    <p class="board-note" v-if="!busy && inventory">
      <span class="meta-pill">⚡ {{ inventory.stats.filesScanned }} files read in {{ inventory.stats.durationMs }} ms</span>
      <span class="meta-pill">📦 {{ contracts.length }} external {{ contracts.length === 1 ? "contract" : "contracts" }} across {{ providers }} {{ providers === 1 ? "provider" : "providers" }}</span>
      <span class="meta-pill" v-if="second">{{ second }}.</span>
    </p>
  </section>
</template>
