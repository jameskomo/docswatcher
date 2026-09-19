<script setup lang="ts">
import type { Finding } from "~~/engine/types";
import { contractLabel, encodeId, fmtDate, fmtMonth } from "~/utils/format";

/**
 * The time ruler. One component, two grounds.
 *
 * This is the product's central claim rendered directly: a deadline exists,
 * it has a position in time, and some of them are already behind you. It runs
 * on the dark board at the top of a scan, and again on light paper in the
 * dashboard. Nothing else on the site is allowed to be this loud.
 *
 * Items with a date in the past clamp to the left edge and sit behind the now
 * line, so "already expired" is a position rather than a label.
 */
const props = defineProps<{
  findings: Finding[];
  today?: Date;
  variant?: "board" | "paper";
  months?: number;
}>();

const { providerName } = useKnowledge();

const W = 1000;
const PAD_L = 8;
const PAD_R = 8;
const TOP = 26;   // headroom: the today label lives above every lane
const ROW = 20;
const GAP = 5;
const AXIS_GAP = 16;

const span = computed(() => props.months ?? 12);
const today = computed(() => props.today ?? new Date());
const start = computed(() => Date.UTC(today.value.getUTCFullYear(), today.value.getUTCMonth(), 1));
const end = computed(() => Date.UTC(today.value.getUTCFullYear(), today.value.getUTCMonth() + span.value, 1));

const ticks = computed(() =>
  Array.from({ length: span.value }, (_, i) => {
    const d = new Date(start.value);
    d.setUTCMonth(d.getUTCMonth() + i);
    return { key: d.toISOString().slice(0, 7), t: d.getTime() };
  }),
);

const x = (t: number) => PAD_L + ((t - start.value) / (end.value - start.value)) * (W - PAD_L - PAD_R);

interface Pin {
  f: Finding;
  t: number;
  label: string;
  x: number;
  lane: number;
  past: boolean;
}

const pins = computed<Pin[]>(() => {
  const items = props.findings
    .filter((f) => f.effective)
    .map((f) => {
      const [y, m, d] = f.effective!.split("-").map(Number);
      const t = Date.UTC(y, m - 1, d);
      const c = contractLabel(f.contract);
      return {
        f,
        t,
        label: `${providerName(c.provider)} ${c.key}`,
        x: x(Math.min(Math.max(t, start.value), end.value - 1)),
        lane: 0,
        past: t < today.value.getTime(),
      };
    })
    .filter((p) => p.t < end.value)
    .sort((a, b) => a.t - b.t || a.f.contract.localeCompare(b.f.contract));

  // Greedy lane packing so labels never overlap.
  const laneEnd: number[] = [];
  for (const p of items) {
    const width = 14 + p.label.length * 6.1;
    let lane = laneEnd.findIndex((e) => e + 8 < p.x);
    if (lane < 0) {
      lane = laneEnd.length;
      laneEnd.push(0);
    }
    laneEnd[lane] = p.x + width;
    p.lane = lane;
  }
  return items;
});

const lanes = computed(() => Math.max(1, ...pins.value.map((p) => p.lane + 1)));
const axisY = computed(() => TOP + lanes.value * (ROW + GAP) + AXIS_GAP);
const H = computed(() => axisY.value + 22);
const nowX = computed(() => x(today.value.getTime()));

const tone = (f: Finding) => {
  const board = props.variant === "board";
  if (f.severity === "breaking") return board ? "var(--overdue-on-board)" : "var(--overdue)";
  if (f.severity === "warning") return board ? "var(--soon-on-board)" : "var(--soon)";
  return board ? "var(--note-on-board)" : "var(--note)";
};

const label = (p: Pin) => `${p.label}, ${p.past ? "expired" : "expires"} ${fmtDate(p.f.effective)}`;

const CHAR = 6.9;

/**
 * Overdue pins sit left of the today line and their labels read rightward, so a
 * long one runs into that line and looks like it belongs to the future. Clip it
 * to the space actually available. The full text stays in the circle's <title>,
 * so nothing is lost to a reader or to assistive technology.
 */
function fitted(p: Pin): string {
  const available = (p.past ? nowX.value - 6 : W - PAD_R) - (p.x + 10);
  const max = Math.floor(available / CHAR);
  if (max < 4) return "";
  return p.label.length <= max ? p.label : p.label.slice(0, max - 1).trimEnd() + "\u2026";
}
</script>

<template>
  <div class="ruler" :class="variant ?? 'paper'">
    <svg
      :viewBox="`0 0 ${W} ${H}`"
      preserveAspectRatio="xMidYMin meet"
      role="img"
      :aria-label="`Time ruler covering ${span} months from today, with ${pins.length} dated deadlines`"
    >
      <!-- Overdue Danger Zone shading -->
      <rect
        :x="PAD_L"
        :y="TOP - 16"
        :width="Math.max(0, nowX - PAD_L)"
        :height="axisY - (TOP - 16)"
        fill="rgba(255, 56, 92, 0.05)"
        rx="4"
      />

      <!-- Month ticks. The rule is the axis, so it carries information. -->
      <g>
        <line
          v-for="t in ticks"
          :key="t.key"
          class="tick-line"
          :x1="x(t.t)"
          :x2="x(t.t)"
          :y1="TOP - 16"
          :y2="axisY"
        />
      </g>
      <line class="axis-line" :x1="PAD_L" :x2="W - PAD_R" :y1="axisY" :y2="axisY" />
      <text
        v-for="t in ticks"
        :key="`l-${t.key}`"
        class="tick-text"
        :x="x(t.t) + 5"
        :y="axisY + 15"
      >{{ fmtMonth(t.key) }}</text>

      <!-- Now. -->
      <line class="now-line" :x1="nowX" :x2="nowX" :y1="TOP - 16" :y2="axisY" />
      <text class="now-text" :x="nowX + 5" :y="TOP - 18">today</text>

      <!-- Deadlines. -->
      <g
        v-for="(p, i) in pins"
        :key="p.f.id"
        class="pin"
        :style="{ animationDelay: `${Math.min(i * 45, 400)}ms` }"
      >
        <circle
          :cx="p.x"
          :cy="TOP + p.lane * (ROW + GAP) + ROW / 2"
          :r="p.past ? 4.5 : 4"
          :fill="p.past ? tone(p.f) : 'none'"
          :stroke="tone(p.f)"
          stroke-width="2"
        >
          <title>{{ label(p) }}</title>
        </circle>
        <text
          class="pin-label"
          :x="p.x + 10"
          :y="TOP + p.lane * (ROW + GAP) + ROW / 2 + 4"
        >{{ fitted(p) }}</text>
      </g>

      <text v-if="!pins.length" class="tick-text" :x="PAD_L" :y="TOP + 14">
        Nothing dated in the next {{ span }} months.
      </text>
    </svg>
  </div>
</template>

<style scoped>
.ruler svg { width: 100%; height: auto; overflow: visible; }

.ruler .tick-line,
.ruler .axis-line { stroke: var(--hair-strong); }
.ruler .tick-text { fill: var(--ink-faint); font-family: var(--face); font-size: 11px; font-weight: 600; }
.ruler .now-line {
  stroke: var(--ink-accent);
  stroke-width: 2;
  filter: drop-shadow(0 0 6px rgba(56, 189, 248, 0.6));
}
.ruler .now-text {
  fill: var(--ink-accent);
  font-family: var(--face);
  font-size: 11px;
  font-weight: 800;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}
.ruler .pin-label { fill: var(--ink); font-family: var(--face-mono); font-size: 12px; font-weight: 500; }
:root:not([data-theme="light"]) .ruler.board .pin-label { fill: #f1f5f9; }
[data-theme="light"] .ruler .pin-label,
[data-theme="light"] .ruler.board .pin-label { fill: #0f172a; font-weight: 600; }
[data-theme="light"] .ruler .tick-line { stroke: #cbd5e1; }
[data-theme="light"] .ruler .axis-line { stroke: #94a3b8; }
[data-theme="light"] .ruler .tick-text { fill: #64748b; }
[data-theme="light"] .ruler .now-line { filter: none; stroke: #0284c7; }
[data-theme="light"] .ruler .now-text { fill: #0284c7; }

/* At phone width the labels are unreadable and redundant: the list directly
   below names every finding. The dots and the today line still carry the
   message, which is how many sit behind today and how many ahead. */
@media (max-width: 640px) {
  .pin-label { display: none; }
}

/* The single orchestrated moment on the site: pins land once, after a scan. */
@media (prefers-reduced-motion: no-preference) {
  .pin { animation: land 360ms cubic-bezier(0.2, 0.9, 0.3, 1) backwards; }
}
@keyframes land {
  from { opacity: 0; transform: translateY(-8px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
