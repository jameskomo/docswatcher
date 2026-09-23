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
useHead({
  title: "Deprecation Calendar, DocsWatcher",
  meta: [{ name: "description", content: "Every dated API deprecation DocsWatcher tracks, grouped by month, generated from the open knowledge base." }],
});

const todayIso = today.toISOString().slice(0, 10);
const upcoming = computed(() => group(all.filter((c) => !c.effective || c.effective >= todayIso)));
const past = computed(() => group(all.filter((c) => c.effective && c.effective < todayIso)).reverse());
const showPast = ref(false);
const days = (c: ChangeRecord) => (c.effective ? daysBetween(today, c.effective) : null);

// Subscribe links are resolved against wherever the site is served, so they work at any host or subpath.
const feedProvider = ref("");
const base = ref("");
onMounted(() => { base.value = location.href.split("#")[0].replace(/[^/]*$/, ""); });
const icsUrl = computed(() => `${base.value}feeds/${feedProvider.value || "deprecations"}.ics`);
const webcalUrl = computed(() => icsUrl.value.replace(/^https?:/, "webcal:"));
const googleUrl = computed(() => `https://calendar.google.com/calendar/r?cid=${encodeURIComponent(webcalUrl.value)}`);

// What the chosen calendar will hold. Most dates are in the past, so a subscriber who opens this
// week sees nothing; saying where the next date is saves them wondering whether it worked.
const feedDates = computed(() => all.filter((c) => c.effective && (!feedProvider.value || c.provider === feedProvider.value)));
const feedUpcoming = computed(() => feedDates.value.filter((c) => c.effective! >= todayIso).sort((a, b) => (a.effective! < b.effective! ? -1 : 1)));
</script>

<template>
  <div class="stack" style="gap: var(--s6)">
    <section class="page">
      <h1>What breaks when</h1>
      <p class="lede">
        Continuous timeline of published API deprecations across {{ knowledge.providers.length }} tracked cloud and AI providers.
        {{ all.length }} active and historical records from the open knowledge base.
      </p>

      <div class="subscribe" data-testid="subscribe">
        <label for="feed-provider" class="t2" style="font-weight: 600; color: var(--ink-max)">Put these dates in your calendar</label>
        <div class="row" style="gap: var(--s2); align-items: center; flex-wrap: wrap">
          <select id="feed-provider" class="select" v-model="feedProvider" aria-label="Which provider's shutdowns">
            <option value="">All providers</option>
            <option v-for="p in knowledge.providers" :key="p.info.id" :value="p.info.id">{{ p.info.name }}</option>
          </select>
          <a class="btn" :href="googleUrl" target="_blank" rel="noopener" data-testid="subscribe-google">Google Calendar</a>
          <a class="btn" :href="webcalUrl" data-testid="subscribe-webcal">Apple or Outlook</a>
          <a class="btn" :href="icsUrl" download data-testid="subscribe-ics">.ics file</a>
        </div>
        <p class="t2" data-testid="feed-summary">
          {{ feedDates.length }} dates, {{ feedUpcoming.length }} still to come<template v-if="feedUpcoming.length">.
          Next: <strong>{{ fmtDate(feedUpcoming[0].effective) }}</strong>, {{ providerName(feedUpcoming[0].provider) }} {{ feedUpcoming[0].title }}.</template><template v-else>.</template>
          Google Calendar can take up to an hour to show a new subscription.
        </p>
        <p class="t1 ink-faint">
          Updates itself when the knowledge base changes, with reminders 30 and 7 days before each date.
          Also as an <a :href="`${base}feeds/deprecations.atom`" data-testid="feed-atom">Atom feed</a>
          and <a :href="`${base}feeds/deprecations.json`" data-testid="feed-json">open JSON</a>.
        </p>
      </div>
    </section>

    <section class="section" v-for="g in upcoming" :key="g.key" style="margin-top: 0">
      <div class="month">
        <h3>
          <span style="color: var(--ink-accent); margin-right: 8px">◈</span>
          <span>{{ g.key === "none" ? "Announced, no date yet" : fmtMonth(g.key) }}</span>
        </h3>
        <div v-for="c in g.items" :key="c.id" class="cal-row">
          <div class="date">
            <span style="display: block; font-size: 1.125rem; font-weight: 700; color: var(--ink-max)">
              {{ c.effective ? fmtDate(c.effective) : "—" }}
            </span>
            <span class="t1 num" v-if="days(c) !== null" :style="{ color: (days(c) ?? 99) <= 60 ? 'var(--soon)' : 'var(--ink-faint)', fontWeight: 600 }">
              in {{ days(c) }} days
            </span>
          </div>

          <div class="stack" style="gap: 6px">
            <div class="row" style="gap: 8px; align-items: center">
              <SeverityChip :severity="c.severity" />
              <strong style="color: var(--ink-max)">{{ providerName(c.provider) }}</strong>
              <span style="font-weight: 600; color: var(--ink)">{{ c.title }}</span>
            </div>
            <p class="ink-soft t2">{{ c.summary }}</p>
            <div class="row t2" style="gap: 14px; align-items: center">
              <span class="code-pill">
                {{ c.affects.map(a => a.match).slice(0, 3).join(", ") }}<span v-if="c.affects.length > 3"> +{{ c.affects.length - 3 }}</span>
              </span>
              <a v-if="safeUrl(c.migration?.guide)" :href="safeUrl(c.migration!.guide)!" target="_blank" rel="noopener">migration guide ↗</a>
              <NuxtLink to="/">check whether your repo is affected</NuxtLink>
            </div>
          </div>
        </div>
      </div>
    </section>

    <section class="section" style="margin-top: var(--s4)">
      <button class="btn" @click="showPast = !showPast">
        <span>⏱</span>
        <span>{{ showPast ? "Hide" : "Show" }} already effective ({{ past.reduce((n, g) => n + g.items.length, 0) }})</span>
      </button>

      <template v-if="showPast">
        <div class="month" v-for="g in past" :key="g.key" style="margin-top: var(--s4); opacity: 0.9">
          <h3 style="color: var(--ink-soft)">
            <span style="color: var(--overdue); margin-right: 8px">✕</span>
            <span>{{ fmtMonth(g.key) }} (Already Effective)</span>
          </h3>
          <div v-for="c in g.items" :key="c.id" class="cal-row">
            <div class="date">
              <span style="display: block; font-size: 1.125rem; font-weight: 700; color: var(--overdue)">
                {{ fmtDate(c.effective) }}
              </span>
              <div class="t1 num" style="color: var(--overdue); font-weight: 600">
                {{ -days(c)! }} days ago
              </div>
            </div>
            <div class="stack" style="gap: 6px">
              <div class="row" style="gap: 8px; align-items: center">
                <SeverityChip :severity="c.severity" />
                <strong style="color: var(--ink-max)">{{ providerName(c.provider) }}</strong>
                <span style="font-weight: 600; color: var(--ink)">{{ c.title }}</span>
              </div>
              <div class="row t2" style="gap: 14px">
                <span class="code-pill">
                  {{ c.affects.map(a => a.match).slice(0, 3).join(", ") }}
                </span>
                <NuxtLink to="/">still referenced in your repo? check</NuxtLink>
              </div>
            </div>
          </div>
        </div>
      </template>
    </section>
  </div>
</template>

<style scoped>
.subscribe {
  display: grid;
  gap: var(--s2);
  margin-top: var(--s4);
  padding: var(--s3);
  border: 1px solid var(--hair);
  border-radius: var(--radius-sm);
}
.subscribe .select { min-width: 0; max-width: 100%; }
</style>
