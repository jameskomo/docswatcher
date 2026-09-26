<script setup lang="ts">
import { createOrgApi, PROVIDER_NAME, signInProviders, siteBase, type Me, type OrgApi } from "~/utils/orgApi";

const store = useScanStore();
const route = useRoute();

/*
 * Signed in with GitHub or GitLab, this page is the organisation dashboard (components/OrgDashboard.vue).
 * Signed out, or served where no app stands behind the site, it is what it always was: the last
 * scan run in this browser, with an invitation to sign in when sign-in exists here.
 */
const me = ref<Me | null>(null);
const api = shallowRef<OrgApi | null>(null);
const signedIn = computed(() => !!me.value?.signedIn);
const providers = computed(() => signInProviders(me.value));
/* The callback does not say which provider it came back from, so the words name what is offered. */
const offered = computed(() => providers.value.map((p) => PROVIDER_NAME[p]).join(" or ") || "GitHub");
const SIGNIN_PROBLEMS: Record<string, (by: string) => string> = {
  denied: (by) => `Sign-in was cancelled on ${by}.`,
  failed: (by) => `Sign-in with ${by} did not complete. Try again.`,
  unavailable: (by) => `Sign-in with ${by} is not configured on this deployment.`,
};
const signinProblem = computed(() => SIGNIN_PROBLEMS[String(route.query.signin ?? "")]?.(offered.value) ?? "");

async function refreshMe() {
  api.value ??= createOrgApi(siteBase(location.href));
  me.value = await api.value.me();
}

/**
 * The scan is kept in browser storage so a reload does not lose it, and that record holds the
 * verbatim matched source lines. This is the control that removes it; the about page says it
 * exists, so it has to.
 */
function forgetScan() {
  store.forget();
  navigateTo("/");
}
const { samples, defaultSample } = useKnowledge();
const scanner = useScanner();
const loading = ref(false);
const failed = ref("");

useHead({ title: "Telemetry Dashboard, DocsWatcher" });

const result = computed(() => store.current.value);
const findings = computed(() => (result.value?.findings ?? []).filter((f) => store.effectiveStatus(f) === "open"));
const hidden = computed(() => (result.value?.findings.length ?? 0) - findings.value.length);

onMounted(async () => {
  await refreshMe();
  if (signedIn.value) return;
  await prepareExample();
});

async function onSignedOut() {
  await refreshMe();
  if (!signedIn.value) await prepareExample();
}

async function prepareExample() {
  if (store.current.value || !samples.length) return;
  loading.value = true;
  try {
    const s = defaultSample!;
    const ref = s.real
      ? { host: "github", owner: s.name.split("/")[0], name: s.name.split("/")[1], ref: s.sha ?? "HEAD", sha: s.sha ?? "0000000" }
      : { host: "fixture", owner: "docswatcher", name: s.name, ref: "fixture", sha: "0000000" };
    const r = await scanner.run(s.files, ref);
    store.save({
      inventory: r.inventory,
      findings: r.findings,
      own: r.own,
      source: { label: s.real ? `${s.name} at ${s.sha}` : `Example ${s.name}`, kind: "sample" },
      at: new Date().toISOString(),
    });
  } catch (e: any) {
    failed.value = e?.message ?? String(e);
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <OrgDashboard v-if="signedIn && api && me" :me="me" :api="api" @signed-out="onSignedOut" />
  <div v-else>
    <p v-if="signinProblem" class="section notice bad" role="alert" data-testid="signin-problem">{{ signinProblem }}</p>
    <div v-if="providers.length && api" class="section signin-cta" data-testid="signin-cta">
      <div>
        <h2>See your organisation</h2>
        <p class="t2 ink-soft">
          Sign in with {{ offered }} to see every repository DocsWatcher watches for your organisation, one
          view, and the blast radius of a single shutdown. You see only what {{ offered }} lets you see.
        </p>
      </div>
      <div class="row signin-buttons">
        <a v-for="p in providers" :key="p" class="btn solid" :href="api.loginUrl(p)" :data-testid="`signin-${p}`">
          Sign in with {{ PROVIDER_NAME[p] }} to see your organisation
        </a>
      </div>
    </div>

    <Board
      v-if="loading || result"
      :inventory="result?.inventory"
      :findings="findings"
      :subject="result?.source.label"
      heading
      :busy="loading"
      phase="Preparing an example dashboard"
      :percent="null"
    />

    <p v-if="failed" class="section notice bad" role="alert">
      The example dashboard could not be prepared: {{ failed }}
      <NuxtLink to="/">Run a scan instead.</NuxtLink>
    </p>

    <template v-if="result">
      <div class="row between section" style="align-items: center; margin-top: var(--s5); gap: var(--s3)">
        <p class="t2 ink-faint">
          Scanned <span class="mono">{{ new Date(result.at).toLocaleString() }}</span>.
          <NuxtLink to="/">Scan another repository.</NuxtLink>
          <button type="button" class="linkish" @click="forgetScan">Clear this scan.</button>
        </p>

        <label class="row t2 ink-soft" style="gap: var(--s2); cursor: pointer; user-select: none; background: rgba(255,255,255,0.03); padding: 6px 12px; border-radius: var(--radius-sm); border: 1px solid var(--hair)">
          <input
            id="prod-toggle"
            type="checkbox"
            :checked="store.local.value.production"
            @change="store.setProduction(($event.target as HTMLInputElement).checked)"
            style="cursor: pointer"
          />
          <span style="font-weight: 600; color: var(--ink)">This repository runs in production</span>
        </label>
      </div>

      <section class="section" id="map">
        <div class="section-head">
          <h2>What this code depends on</h2>
          <p>Service dependency topology and health split for all tracked external APIs.</p>
        </div>
        <ProviderMap :inventory="result.inventory" :findings="findings" />
      </section>

      <section class="section" id="horizon">
        <div class="section-head">
          <h2>The next twelve months</h2>
          <p>Chronological timeline radar. Overdue deadlines sit to the left of the today marker.</p>
        </div>
        <div class="board-ruler" style="margin-top: var(--s3)">
          <Horizon :findings="findings" />
        </div>
      </section>

      <section class="section" id="findings">
        <div class="section-head">
          <h2>What is expiring</h2>
          <p v-if="hidden">{{ hidden }} hidden because they are snoozed or marked as not running in production.</p>
          <p v-else>Active deprecation warnings matching callsites in this codebase.</p>
        </div>
        <FindingsList :findings="findings" compact />
      </section>

      <section class="section" id="inventory">
        <div class="section-head">
          <h2>Everything this repository calls</h2>
          <p>Complete external contract ledger indexed from local AST.</p>
        </div>
        <InventoryTable :contracts="result.inventory.contracts" :findings="findings" />
      </section>
    </template>

    <section v-else-if="!loading && !failed" class="section empty">
      <h3>No scan stored in this browser</h3>
      <p>The dashboard renders the last scan you ran. Run one and it will appear here.</p>
      <p style="margin-top: var(--s4)"><NuxtLink class="btn solid" to="/">Scan a repository</NuxtLink></p>
    </section>
  </div>
</template>

<style scoped>
.signin-cta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--s4);
  padding: var(--s4);
  border: 1px solid var(--hair);
  border-radius: var(--radius-lg);
  background: var(--bg-surface);
}
.signin-cta h2 { margin-bottom: var(--s1); }
.signin-cta p { max-width: var(--measure); }
.signin-buttons { gap: var(--s3); flex-wrap: wrap; }
</style>
