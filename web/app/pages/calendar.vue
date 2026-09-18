<script setup lang="ts">
import type { ChangeRecord } from "~~/engine/types";
import { daysBetween } from "~~/engine/matcher";
import { fmtDate, fmtMonth } from "~/utils/format";

const { knowledge, providerName } = useKnowledge();
const today = new Date();
const all = knowledge.providers.flatMap((p) => p.changes).filter((c) => c.status === "active" || c.status === "expired");

function group(list: ChangeRecord[]) {
  const m = new Map<string, ChangeRecord[]>();
  for (const c of list) {
    const k = c.effective ? c.effective.slice(0, 7) : "none";
    m.set(k, [...(m.get(k) ?? []), c]);
  }
  return [...m.entries()].sort(([a], [b]) => (a === "none" ? 1 : b === "none" ? -1 : a < b ? -1 : 1))
    .map(([key, items]) => ({ key, items: items.sort((a, b) => (a.effective ?? "9") < (b.effective ?? "9") ? -1 : 1) }));
}
const todayIso = today.toISOString().slice(0, 10);
const upcoming = computed(() => group(all.filter((c) => !c.effective || c.effective >= todayIso)));
const past = computed(() => group(all.filter((c) => c.effective && c.effective < todayIso)).reverse());
const showPast = ref(false);
const days = (c: ChangeRecord) => (c.effective ? daysBetween(today, c.effective) : null);
</script>

<template>
  <div class="stack" style="gap: 28px">
    <section class="hero">
      <span class="eyebrow">Public deprecation calendar</span>
      <h1>What breaks when</h1>
      <p>Every dated change across {{ knowledge.providers.length }} tracked providers, generated from the open knowledge base. {{ all.length }} records.</p>
    </section>

    <section class="block" v-for="g in upcoming" :key="g.key">
      <div class="month">
        <h3>{{ g.key === "none" ? "Announced, no date yet" : fmtMonth(g.key) }}</h3>
        <div v-for="c in g.items" :key="c.id" class="cal-row">
          <div class="date">{{ c.effective ? fmtDate(c.effective) : "—" }}<div class="small muted num" v-if="days(c) !== null">in {{ days(c) }} days</div></div>
          <div class="stack" style="gap: 4px">
            <div class="row" style="gap: 8px"><SeverityChip :severity="c.severity" /><strong>{{ providerName(c.provider) }}</strong><span>{{ c.title }}</span></div>
            <p class="ink2 small">{{ c.summary }}</p>
            <div class="row small" style="gap: 14px">
              <span class="mono muted">{{ c.affects.map(a => a.match).slice(0, 3).join(", ") }}<span v-if="c.affects.length > 3"> +{{ c.affects.length - 3 }}</span></span>
              <a v-if="c.migration?.guide" :href="c.migration.guide" target="_blank" rel="noopener">migration guide ↗</a>
              <NuxtLink to="/">check whether your repo is affected →</NuxtLink>
            </div>
          </div>
        </div>
      </div>
    </section>

    <section class="block">
      <button class="btn" @click="showPast = !showPast">{{ showPast ? "Hide" : "Show" }} already effective ({{ past.reduce((n, g) => n + g.items.length, 0) }})</button>
      <template v-if="showPast">
        <div class="month" v-for="g in past" :key="g.key">
          <h3>{{ fmtMonth(g.key) }}</h3>
          <div v-for="c in g.items" :key="c.id" class="cal-row">
            <div class="date">{{ fmtDate(c.effective) }}<div class="small muted num">{{ -days(c)! }} days ago</div></div>
            <div class="stack" style="gap: 4px">
              <div class="row" style="gap: 8px"><SeverityChip :severity="c.severity" /><strong>{{ providerName(c.provider) }}</strong><span>{{ c.title }}</span></div>
              <div class="row small" style="gap: 14px">
                <span class="mono muted">{{ c.affects.map(a => a.match).slice(0, 3).join(", ") }}</span>
                <NuxtLink to="/">still referenced in your repo? check →</NuxtLink>
              </div>
            </div>
          </div>
        </div>
      </template>
    </section>
  </div>
</template>
