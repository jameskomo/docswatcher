<script setup lang="ts">
const store = useScanStore();
const { samples, defaultSample } = useKnowledge();
const scanner = useScanner();
const loading = ref(false);

const result = computed(() => store.current.value);
const findings = computed(() => (result.value?.findings ?? []).filter((f) => store.effectiveStatus(f) === "open"));
const hidden = computed(() => (result.value?.findings.length ?? 0) - findings.value.length);

onMounted(async () => {
  if (store.current.value || !samples.length) return;
  loading.value = true;
  try {
    const s = defaultSample!;
    const r = await scanner.run(s.files, { host: "fixture", owner: "docswatcher", name: s.name, ref: "fixture", sha: "0000000" });
    store.save({ inventory: r.inventory, findings: r.findings, source: { label: `Example · ${s.name}`, kind: "sample" }, at: new Date().toISOString() });
  } finally { loading.value = false; }
});
</script>

<template>
  <div class="stack" style="gap: 28px">
    <div v-if="loading" class="notice">Preparing an example dashboard…</div>
    <template v-if="result">
      <section class="row between">
        <div>
          <span class="eyebrow">{{ result.source.kind === 'sample' ? 'Example dashboard' : 'Dashboard' }}</span>
          <h1>{{ result.source.label }}</h1>
          <p class="muted small">scanned {{ new Date(result.at).toLocaleString() }} · <NuxtLink to="/">scan another repository</NuxtLink></p>
        </div>
        <label class="row small" style="gap: 6px"><input id="prod-toggle" type="checkbox" :checked="store.local.value.production" @change="store.setProduction(($event.target as HTMLInputElement).checked)" /> this repository runs in production</label>
      </section>

      <section class="block">
        <StatTiles :inventory="result.inventory" :findings="findings" />
      </section>

      <section class="block" id="map">
        <h2>The map</h2>
        <p class="ink2">Every external service this code depends on. Most teams have never seen this picture of their own system.</p>
        <ProviderMap :inventory="result.inventory" :findings="findings" />
      </section>

      <section class="block" id="horizon">
        <h2>The horizon</h2>
        <p class="ink2">What breaks when, over the next twelve months.</p>
        <Horizon :findings="findings" />
      </section>

      <section class="block">
        <div class="row between"><h2>Findings</h2><span class="muted small" v-if="hidden">{{ hidden }} snoozed or marked not in production</span></div>
        <FindingsList :findings="findings" compact show-actions />
      </section>

      <section class="block">
        <h2>Inventory</h2>
        <InventoryTable :contracts="result.inventory.contracts" :findings="findings" />
      </section>
    </template>
  </div>
</template>
