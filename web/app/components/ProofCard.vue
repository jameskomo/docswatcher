<script setup lang="ts">
import type { Finding } from "~~/engine/types";
import { contractLabel, fmtDate } from "~/utils/format";
import { highlight, langFromPath, type CodeToken } from "~/utils/highlight";

/** One line of source around the headline finding; `hit` marks a line the finding points at. */
export interface ExcerptLine { n: number; text: string; hit: boolean }

const props = defineProps<{
  /** The finding the card leads with, or null when the scan found nothing dated. */
  finding: Finding | null;
  /** Source lines around it, when the scanned files are still in memory. */
  excerpt: ExcerptLine[] | null;
  /** What was scanned, as the scan names it ("openai/openai-quickstart-python at ec8890d"). */
  subject?: string;
  /** What a URL or folder scan read ("41 text files read"), including the warning when it stopped short. */
  note?: string;
  total: number;
  busy?: boolean;
  phase?: string;
  percent?: number | null;
}>();

const emit = defineEmits<{ (e: "show-all"): void }>();
const { change, providerName } = useKnowledge();

const evidence = computed(() => props.finding?.evidence[0] ?? null);
const record = computed(() => (props.finding ? change(props.finding.change) : undefined));
const days = computed(() => props.finding?.daysRemaining ?? null);
const stamp = computed(() => {
  const when = props.finding?.effective ? fmtDate(props.finding.effective).toUpperCase() : "";
  if (days.value === null) return "NO DATE SET";
  return days.value < 0 ? `SHUT DOWN ${when}` : `SHUTS DOWN ${when}`;
});
const says = computed(() => {
  const title = record.value?.title ?? props.finding?.change ?? "";
  const who = record.value ? `${providerName(record.value.provider)}: ` : "";
  const d = days.value;
  if (d === null) return `${who}${title}. The provider has not set a date yet.`;
  if (d < 0) return `${who}${title}. Calls to it have failed for ${-d} ${-d === 1 ? "day" : "days"}.`;
  if (d === 0) return `${who}${title}. Today is the last day.`;
  return `${who}${title}. ${d} ${d === 1 ? "day" : "days"} left.`;
});

/** The lines to draw: the excerpt, or the evidence line alone after a reload. */
const lines = computed<ExcerptLine[]>(() => {
  if (props.excerpt?.length) return props.excerpt;
  const ev = evidence.value;
  return ev ? [{ n: ev.line, text: ev.snippet, hit: true }] : [];
});

/** The excerpt highlighted in one pass, so the grammar sees each line in context. Plain until it arrives. */
const tokens = ref<CodeToken[][] | null>(null);
let request = 0;
watch(lines, async (ls) => {
  const mine = ++request;
  tokens.value = null;
  if (!ls.length) return;
  const out = await highlight(ls.map((l) => l.text).join("\n"), langFromPath(evidence.value?.path ?? ""));
  if (mine === request) tokens.value = out;
}, { immediate: true });

/** One line as coloured runs, the struck-through call cut out of whatever tokens it spans. */
function segments(l: ExcerptLine, i: number): Array<CodeToken & { struck: boolean }> {
  const toks = tokens.value?.[i] ?? [{ content: l.text }];
  if (!l.hit) return toks.map((t) => ({ ...t, struck: false }));
  const { lead, struck } = split(l.text);
  const from = lead.length, to = lead.length + struck.length;
  const out: Array<CodeToken & { struck: boolean }> = [];
  let at = 0;
  for (const t of toks) {
    const end = at + t.content.length;
    for (const [a, b, s] of [[at, Math.min(end, from), false], [Math.max(at, from), Math.min(end, to), true], [Math.max(at, to), end, false]] as const) {
      if (b > a) out.push({ ...t, content: t.content.slice(a - at, b - at), struck: s });
    }
    at = end;
  }
  return out;
}

/**
 * Splits a hit line so the call itself can be struck through: the contract's key ("beta.assistants.create")
 * widened left over the receiver it hangs off ("client."). Without the key in the line, the whole statement.
 */
function split(text: string): { lead: string; struck: string; tail: string } {
  const key = props.finding ? contractLabel(props.finding.contract).key : "";
  let at = key ? text.indexOf(key) : -1;
  if (at >= 0) {
    let start = at;
    while (start > 0 && /[\w.$]/.test(text[start - 1]!)) start--;
    let end = at + key.length;
    if (text[end] === "(") end++;
    return { lead: text.slice(0, start), struck: text.slice(start, end), tail: text.slice(end) };
  }
  const indent = text.length - text.trimStart().length;
  return { lead: text.slice(0, indent), struck: text.slice(indent), tail: "" };
}
</script>

<template>
  <figure class="proof" id="results" :aria-busy="busy ? 'true' : 'false'">
    <figcaption class="proof-head">
      <span class="proof-path">{{ evidence?.path ?? (busy ? "Scanning" : "No findings") }}</span>
      <span class="proof-subject">{{ subject }}</span>
      <span v-if="note && !busy" class="proof-note">{{ note }}</span>
    </figcaption>

    <div v-if="busy" class="proof-busy" role="status">
      <p>{{ phase || "Scanning" }}<template v-if="percent !== null && percent !== undefined"> · {{ percent }}%</template></p>
      <div class="proof-bar" :class="{ indeterminate: percent === null || percent === undefined }">
        <span :style="percent !== null && percent !== undefined ? { width: `${percent}%` } : undefined"></span>
      </div>
    </div>

    <template v-else-if="finding">
      <pre class="proof-code code-on-surface"><span v-for="(l, i) in lines" :key="l.n" class="proof-line" :class="{ hit: l.hit }"><span class="ln">{{ l.n }}</span><span class="c"><template v-for="(g, k) in segments(l, i)" :key="k"><s v-if="g.struck" :style="g.color ? { color: g.color } : undefined">{{ g.content }}</s><span v-else :style="g.color ? { color: g.color } : undefined" :class="{ 'tok-italic': g.italic }">{{ g.content }}</span></template></span></span></pre>
      <div class="proof-foot">
        <span class="stamp">{{ stamp }}</span>
        <p class="proof-says">{{ says }}</p>
        <button v-if="total > 1" type="button" class="proof-more" @click="emit('show-all')">All {{ total }} findings</button>
      </div>
    </template>

    <div v-else class="proof-clear">
      <p class="display">Nothing here has a shutdown date.</p>
      <p>No call in this repository matches a dated change in the knowledge base. New records arrive every week; scan again later, or add the GitHub Action so CI checks on every push.</p>
    </div>
  </figure>
</template>

<style scoped>
.proof {
  margin: 0;
  min-width: 0;
  background: var(--bg-surface);
  border: 1.5px solid var(--rule);
  box-shadow: var(--lift);
}
.proof-head {
  display: flex; flex-wrap: wrap; justify-content: space-between; gap: 4px 16px;
  padding: 14px 18px; border-bottom: 1px solid var(--hair); font-size: var(--t2);
}
.proof-path { overflow-wrap: anywhere; }
.proof-subject { color: var(--ink-faint); overflow-wrap: anywhere; }
.proof-note { flex-basis: 100%; color: var(--ink-faint); font-size: var(--t1); }
.proof-code {
  margin: 0; padding: 14px 0; border: 0; background: none;
  font-family: var(--face); font-size: 13px; line-height: 1.8; color: var(--ink);
  white-space: normal; overflow: visible;
}
.proof-line { display: grid; grid-template-columns: 44px minmax(0, 1fr); padding-right: 18px; }
.proof-line .ln { padding-left: 18px; color: var(--ink-faint); }
.proof-line .c { white-space: pre-wrap; overflow-wrap: anywhere; }
.proof-line.hit { background: var(--signal-tint); box-shadow: inset 4px 0 0 var(--signal); }
.proof-line.hit .ln { color: var(--signal-ink); font-weight: 700; }
.proof-line s { text-decoration-color: var(--signal-ink); text-decoration-thickness: 2px; }
.proof-foot {
  display: flex; flex-wrap: wrap; align-items: center; gap: 14px 22px;
  padding: 18px; background: var(--night); color: var(--night-ink);
}
.stamp {
  border: 2px solid var(--signal); color: var(--signal);
  padding: 6px 12px; font-weight: 700; font-size: 13px; letter-spacing: 0.08em;
  transform: rotate(-2deg);
}
.proof-says { flex: 1 1 220px; min-width: 0; margin: 0; font-size: var(--t2); line-height: 1.5; }
.proof-more {
  background: none; border: 0; padding: 10px 0; color: var(--night-ink); cursor: pointer;
  font: inherit; font-weight: 700; text-decoration: underline; text-underline-offset: 3px;
}
.proof-more:hover { color: var(--signal); }
.proof-busy, .proof-clear { padding: 28px 18px; display: grid; gap: 14px; }
.proof-clear .display { font-size: 1.5rem; margin: 0; }
.proof-clear p { margin: 0; color: var(--ink-soft); }
.proof-bar { height: 6px; background: var(--hair); overflow: hidden; }
.proof-bar span { display: block; height: 100%; background: var(--strong); transition: width 0.2s ease; }
.proof-bar.indeterminate span { width: 30%; animation: proof-sweep 1.1s ease-in-out infinite; }
@keyframes proof-sweep { from { transform: translateX(-100%); } to { transform: translateX(340%); } }
@media (prefers-reduced-motion: reduce) {
  .proof-bar.indeterminate span { animation: none; width: 100%; opacity: 0.4; }
}
@media (max-width: 640px) {
  .proof-code { font-size: 12px; }
  .proof-line { grid-template-columns: 36px minmax(0, 1fr); padding-right: 12px; }
  .proof-line .ln { padding-left: 12px; }
}
</style>
