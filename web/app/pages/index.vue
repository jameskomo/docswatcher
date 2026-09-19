<script setup lang="ts">
import type { InputFile, RepoRef } from "~~/engine/types";
import { fetchViaApi, fetchViaRelay, parseGitHubUrl, readFolder, fetchViaJsDelivr } from "~/utils/fetchRepo";

const config = useRuntimeConfig();
const relay = (config.public.relayUrl as string) || "";
const { knowledge, samples, realSamples, fixtureSamples, defaultSample } = useKnowledge();
const scanner = useScanner();
const store = useScanStore();

const trackedChanges = knowledge.providers.reduce((n, p) => n + p.changes.length, 0);

useHead({
  title: "DocsWatcher · which of your API calls has an expiry date",
  meta: [{ name: "description", content: `Scan a repository for the external APIs it calls and match them against ${trackedChanges} published provider deprecations. The scan runs in your browser.` }],
});

const mode = ref<"sample" | "github" | "folder">("sample");
// Set when an outbound fetch is blocked by the page host, so the UI can explain it once.
const networkBlocked = ref(false);
const url = ref("");
const token = ref("");
const sample = ref(defaultSample?.name ?? "");
const selectedSample = computed(() => samples.find((s) => s.name === sample.value));
const busy = ref(false);
const status = ref("");
const error = ref("");
const fetchNote = ref("");
const folderInput = ref<HTMLInputElement | null>(null);

const result = computed(() => store.current.value);
const findings = computed(() => (result.value?.findings ?? []).filter((f) => store.effectiveStatus(f) === "open"));
const percent = computed(() => {
  const p = scanner.progress.value;
  return p && p.total ? Math.round((100 * p.done) / p.total) : null;
});

async function runScan(files: InputFile[], repo: RepoRef, source: { label: string; kind: "sample" | "github" | "folder" }) {
  status.value = "Scanning";
  const r = await scanner.run(files, repo);
  store.save({ inventory: r.inventory, findings: r.findings, source, at: new Date().toISOString() });
}

async function scanSample() {
  const s = samples.find((x) => x.name === sample.value);
  if (!s) return;
  await guard(async () => {
    fetchNote.value = "";
    const ref = s.real
      ? { host: "github", owner: s.name.split("/")[0], name: s.name.split("/")[1], ref: s.sha ?? "HEAD", sha: s.sha ?? "0000000" }
      : { host: "fixture", owner: "docswatcher", name: s.name, ref: "fixture", sha: "0000000" };
    await runScan(s.files, ref, { label: s.real ? `${s.name} @ ${s.sha}` : `Example · ${s.name}`, kind: "sample" });
  });
}

async function scanGitHub() {
  const t = parseGitHubUrl(url.value);
  if (!t) { error.value = "Enter a GitHub repository URL like https://github.com/owner/repo"; return; }
  await guard(async () => {
    const progress = (msg: string, done?: number, total?: number) => { status.value = total ? `${msg} ${done}/${total}` : msg; };
    // Try every route. Hosts differ in what they permit: an embedded sandbox may block
    // one origin and allow another, so a single blocked route must not end the scan.
    const routes: Array<{ name: string; run: () => Promise<any> }> = [];
    if (relay) routes.push({ name: "relay", run: () => fetchViaRelay(relay, t, progress) });
    routes.push({ name: "jsDelivr", run: () => fetchViaJsDelivr(t, progress) });
    routes.push({ name: "GitHub API", run: () => fetchViaApi(t, token.value.trim() || undefined, progress) });

    let res: any = null;
    const tried: string[] = [];
    for (const route of routes) {
      try { res = await route.run(); break; }
      catch (e: any) {
        const blocked = e instanceof TypeError && /failed to fetch|load failed|networkerror/i.test(e?.message ?? "");
        tried.push(`${route.name}: ${blocked ? "blocked by this page's host" : e?.message ?? String(e)}`);
        if (!blocked && route.name !== "relay") throw e;
      }
    }
    if (!res) {
      networkBlocked.value = true;
      mode.value = "sample";
      throw new Error(`Could not reach any source for ${t.owner}/${t.name}. ${tried.join(". ")}. Sample repositories and local folders still work here and run the same engine. To scan by URL, run the site locally or deploy the static export to your own host.`);
    }
    fetchNote.value = `${res.files.length} text files read`
      + (res.binaries ? `, ${res.binaries} binary skipped` : "")
      + (res.skipped ? `, ${res.skipped} skipped` : "")
      + (res.truncated ? ". Large repository: only the first 300 relevant files were read without a relay." : "");
    await runScan(res.files, res.repo, { label: `${t.owner}/${t.name}`, kind: "github" });
  });
}

async function scanFolder(ev: Event) {
  const list = (ev.target as HTMLInputElement).files;
  if (!list?.length) return;
  await guard(async () => {
    const progress = (msg: string, done?: number, total?: number) => { status.value = total ? `${msg} ${done}/${total}` : msg; };
    const res = await readFolder(list, progress);
    fetchNote.value = `${res.files.length} text files read` + (res.skipped ? `, ${res.skipped} skipped` : "");
    await runScan(res.files, { host: "local", owner: "local", name: res.root, ref: "working-tree", sha: "0000000" }, { label: `Folder · ${res.root}`, kind: "folder" });
  });
}

/**
 * A host that forbids outbound requests (a sandboxed embed with a restrictive
 * connect-src) fails fetch with a bare TypeError and no status. Say so plainly
 * instead of showing "Failed to fetch", which reads like a bug in the scanner.
 */
function describe(e: any): string {
  const msg = e?.message ?? String(e);
  const blocked = e instanceof TypeError && /failed to fetch|load failed|networkerror/i.test(msg);
  if (!blocked) return msg;
  networkBlocked.value = true;
  mode.value = "sample";
  return "This page cannot reach GitHub. The site is embedded in a sandbox that blocks outbound requests, so scanning by URL is unavailable here. Sample repositories and local folders still work, and both scan exactly the same way. To scan by URL, run the site locally or deploy the static export to your own host.";
}

async function guard(fn: () => Promise<void>) {
  busy.value = true; error.value = ""; status.value = "Starting";
  try { await fn(); } catch (e: any) { error.value = describe(e); } finally { busy.value = false; status.value = ""; }
}

onMounted(() => { if (!store.current.value && samples.length) scanSample(); });
</script>

<template>
  <div class="stack" style="gap: var(--s6)">
    <section class="hero">
      <h1>Which of your API calls has an expiry date?</h1>
      <p class="lede">
        DocsWatcher reads a repository and finds every external contract in it: SDK calls, endpoints,
        model IDs in config, pinned API versions. It matches them against
        <strong>{{ trackedChanges }}</strong> published provider deprecations and tells you which ones
        have a date on them, at the file and line.
      </p>
      <p class="small muted">
        The scan runs in this tab. Nothing is uploaded.
        <NuxtLink to="/about">How it works</NuxtLink>
      </p>
    </section>

    <section class="block">
      <div class="tabs" role="tablist" aria-label="Scan source">
        <button role="tab" :aria-selected="mode === 'sample'" @click="mode = 'sample'">Sample repository</button>
        <button role="tab" :aria-selected="mode === 'github'" @click="mode = 'github'">GitHub URL</button>
        <button role="tab" :aria-selected="mode === 'folder'" @click="mode = 'folder'">Local folder</button>
      </div>

      <p v-if="networkBlocked && mode === 'github'" class="notice" role="status">
        Unavailable on this host: outbound requests are blocked. Use a sample or a local folder, or run the site yourself.
      </p>

      <form v-if="mode === 'github'" class="stack" @submit.prevent="scanGitHub">
        <div class="row">
          <input id="repo-url" class="input grow" style="flex-basis: 320px" v-model="url" placeholder="https://github.com/owner/repo" aria-label="GitHub repository URL" :disabled="busy" />
          <button id="scan-github" class="btn primary" type="submit" :disabled="busy">Scan repository</button>
        </div>
        <div v-if="!relay" class="row small">
          <input id="gh-token" class="input grow" style="flex-basis: 260px" type="password" v-model="token" placeholder="Optional GitHub token" aria-label="GitHub token" autocomplete="off" />
          <span class="muted">Held in memory only. Files are read through jsDelivr, then the GitHub API, which allows 60 requests an hour per address.</span>
        </div>
      </form>

      <div v-else-if="mode === 'folder'" class="stack">
        <p class="ink2 small">Pick a project folder. Files are read in this tab. Vendored directories and files over 1 MB are skipped.</p>
        <input id="folder-input" ref="folderInput" type="file" webkitdirectory multiple @change="scanFolder" :disabled="busy" aria-label="Choose a folder" />
      </div>

      <div v-else class="stack">
        <div class="row">
          <select id="sample-select" class="select" v-model="sample" :disabled="busy" aria-label="Sample repository">
            <optgroup label="Real public repositories">
              <option v-for="s in realSamples" :key="s.name" :value="s.name">{{ s.name }} @ {{ s.sha }}</option>
            </optgroup>
            <optgroup label="Knowledge base fixtures">
              <option v-for="s in fixtureSamples" :key="s.name" :value="s.name">{{ s.name }}</option>
            </optgroup>
          </select>
          <button id="scan-sample" class="btn primary" @click="scanSample" :disabled="busy">Scan sample</button>
        </div>
        <p v-if="selectedSample?.real" class="ink2 small">
          A vendored copy of <strong>{{ selectedSample.name }}</strong> at commit
          <span class="mono">{{ selectedSample.sha }}</span>, scanned here by the same engine.
          {{ selectedSample.note }}
        </p>
        <p v-else class="muted small">A fixture from the open knowledge base, with a pinned expected result.</p>
      </div>

      <div v-if="busy" class="stack" style="gap: var(--s1)" aria-live="polite">
        <div class="row between small">
          <span>{{ scanner.phase.value || status }}<span v-if="percent !== null" class="muted"> · {{ percent }}%</span></span>
          <span class="mono muted trunc" style="max-width: 46ch" v-if="scanner.progress.value">{{ scanner.progress.value.path }}</span>
        </div>
        <div class="progress" :class="{ indeterminate: percent === null }">
          <div :style="{ width: percent !== null ? percent + '%' : undefined }"></div>
        </div>
      </div>

      <div v-if="error" class="notice error" role="alert">{{ error }}</div>
    </section>

    <template v-if="result">
      <section class="block" id="results">
        <div class="block-head">
          <div class="stack" style="gap: 2px">
            <span class="label">{{ result.source.kind === "sample" ? "Example scan" : "Scan result" }}</span>
            <h2>
              <NuxtLink to="/app" class="result-link">{{ result.source.label }}</NuxtLink>
            </h2>
          </div>
          <div class="aside">
            <span class="num">{{ result.inventory.stats.filesScanned }}</span> files in
            <span class="num">{{ result.inventory.stats.durationMs }}</span> ms
            <span v-if="fetchNote"><br />{{ fetchNote }}</span>
            <br /><NuxtLink to="/app">open dashboard →</NuxtLink>
          </div>
        </div>
        <ScanSummary :inventory="result.inventory" :findings="findings" />
      </section>

      <section class="block" id="findings">
        <div class="block-head">
          <h2>Needs attention</h2>
          <span class="aside">select a row for evidence, migration notes and a fix prompt</span>
        </div>
        <FindingsList :findings="findings" />
      </section>

      <section class="block" id="inventory">
        <div class="block-head">
          <h2>Inventory</h2>
          <span class="aside">every external contract found, including the ones nobody remembers adding</span>
        </div>
        <InventoryTable :contracts="result.inventory.contracts" :findings="findings" />
      </section>
    </template>

    <section v-else-if="!busy" class="empty">
      <h3>No scan yet</h3>
      <p class="small">Choose a sample, paste a repository URL, or pick a folder to begin.</p>
    </section>
  </div>
</template>

<style scoped>
.result-link { color: var(--ink); }
.result-link:hover { color: var(--accent); }
</style>
