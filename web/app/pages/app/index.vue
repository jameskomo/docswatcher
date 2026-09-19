<script setup lang="ts">
const store = useScanStore();
const { samples, defaultSample } = useKnowledge();
const scanner = useScanner();
const loading = ref(false);
const failed = ref("");

useHead({ title: "Dashboard · DocsWatcher" });

const result = computed(() => store.current.value);
const findings = computed(() => (result.value?.findings ?? []).filter((f) => store.effectiveStatus(f) === "open"));
const hidden = computed(() => (result.value?.findings.length ?? 0) - findings.value.length);

onMounted(async () => {
  if (store.current.value || !samples.length) return;
  loading.value = true;
  try {
    const s = defaultSample!;
    const ref = s.real
      ? { host: "github", owner: s.name.split("/")[0], name: s.name.split("/")[1], ref: s.sha ?? "HEAD", sha: s.sha ?? "0000000" }
      : { host: "fixture", owner: "docswatcher", name: s.name, ref: "fixture", sha: "0000000" };
    const r = await scanner.run(s.files, ref);
    store.save({
      inventory: r.inventory, findings: r.findings,
      source: { label: s.real ? `${s.name} @ ${s.sha}` : `Example · ${s.name}`, kind: "sample" },
      at: new Date().toISOString(),
    });
  } catch (e: any) {
    failed.value = e?.message ?? String(e);
  } finally {
    loading.value = false;
  }
});
</script>

<template>
  <div class="stack" style="gap: var(--s6)">
    <!-- Loading: show the shape of the page, not a bare spinner. -->
    <template v-if="loading">
      <div class="stack" aria-live="polite">
        <span class="label">Preparing an example dashboard</span>
        <div class="skeleton" style="height: 28px; width: 60%"></div>
        <div class="skeleton" style="height: 14px; width: 90%"></div>
        <div class="skeleton" style="height: 180px"></div>
      </div>
    </template>

    <div v-else-if="failed" class="notice error" role="alert">
      Could not prepare a dashboard: {{ failed }}
      <NuxtLink to="/">Run a scan instead.</NuxtLink>
    </div>

    <template v-else-if="result">
      <section class="block">
        <div class="block-head">
          <div class="stack" style="gap: 2px">
            <span class="label">{{ result.source.kind === "sample" ? "Example dashboard" : "Dashboard" }}</span>
            <h1>{{ result.source.label }}</h1>
          </div>
          <div class="aside">
            scanned {{ new Date(result.at).toLocaleString() }}<br />
            <NuxtLink to="/">scan another repository →</NuxtLink>
          </div>
        </div>
        <ScanSummary :inventory="result.inventory" :findings="findings" />
        <label class="row small muted" style="gap: var(--s1)">
          <input id="prod-toggle" type="checkbox" :checked="store.local.value.production" @change="store.setProduction(($event.target as HTMLInputElement).checked)" />
          this repository runs in production
        </label>
      </section>

      <section class="block" id="map">
        <div class="block-head">
          <h2>Dependencies</h2>
          <span class="aside">every external service this code calls</span>
        </div>
        <ProviderMap :inventory="result.inventory" :findings="findings" />
      </section>

      <section class="block" id="horizon">
        <div class="block-head">
          <h2>The horizon</h2>
          <span class="aside">what breaks when, over the next twelve months</span>
        </div>
        <Horizon :findings="findings" />
      </section>

      <section class="block" id="findings">
        <div class="block-head">
          <h2>Findings</h2>
          <span class="aside" v-if="hidden">{{ hidden }} snoozed or marked not in production</span>
        </div>
        <FindingsList :findings="findings" compact />
      </section>

      <section class="block" id="inventory">
        <div class="block-head">
          <h2>Inventory</h2>
        </div>
        <InventoryTable :contracts="result.inventory.contracts" :findings="findings" />
      </section>
    </template>

    <section v-else class="empty">
      <h3>No scan stored</h3>
      <p class="small">The dashboard renders the last scan from this browser.</p>
      <NuxtLink class="btn primary" to="/">Scan a repository</NuxtLink>
    </section>
  </div>
</template>
