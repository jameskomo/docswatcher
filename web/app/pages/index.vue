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
  title: "DocsWatcher, find the API calls in your code that have a deadline",
  meta: [{
    name: "description",
    content: `Scan a repository for the external APIs it calls and match them against ${trackedChanges} published provider deprecations. The scan runs in your browser.`,
  }],
});

const mode = ref<"sample" | "github" | "folder">("sample");
const networkBlocked = ref(false);
const url = ref("");
const token = ref("");
const sample = ref(defaultSample?.name ?? "");
const selectedSample = computed(() => samples.find((s) => s.name === sample.value));
const busy = ref(false);
const status = ref("");
const error = ref("");
const fetchNote = ref("");

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
    await runScan(s.files, ref, { label: s.real ? `${s.name} at ${s.sha}` : `Example ${s.name}`, kind: "sample" });
  });
}

async function scanGitHub() {
  const t = parseGitHubUrl(url.value);
  if (!t) {
    error.value = "That is not a repository URL. Use the form https://github.com/owner/repo";
    return;
  }
  await guard(async () => {
    const progress = (msg: string, done?: number, total?: number) => { status.value = total ? `${msg} ${done} of ${total}` : msg; };
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
        tried.push(`${route.name} was ${blocked ? "blocked by this page's host" : e?.message ?? String(e)}`);
        if (!blocked && route.name !== "relay") throw e;
      }
    }
    if (!res) {
      networkBlocked.value = true;
      mode.value = "sample";
      throw new Error(
        `Could not reach ${t.owner}/${t.name}. ${tried.join(". ")}. Sample repositories and local folders still work here and run the same engine. To scan by URL, run the site locally or deploy it to your own host.`,
      );
    }
    fetchNote.value = `${res.files.length} text files read`
      + (res.binaries ? `, ${res.binaries} binary files skipped` : "")
      + (res.truncated ? ". Large repository, so only the first 300 relevant files were read." : "");
    await runScan(res.files, res.repo, { label: `${t.owner}/${t.name}`, kind: "github" });
  });
}

async function scanFolder(ev: Event) {
  const list = (ev.target as HTMLInputElement).files;
  if (!list?.length) return;
  await guard(async () => {
    const progress = (msg: string, done?: number, total?: number) => { status.value = total ? `${msg} ${done} of ${total}` : msg; };
    const res = await readFolder(list, progress);
    fetchNote.value = `${res.files.length} text files read`;
    await runScan(res.files, { host: "local", owner: "local", name: res.root, ref: "working-tree", sha: "0000000" }, { label: `Folder ${res.root}`, kind: "folder" });
  });
}

/**
 * A host that forbids outbound requests fails fetch with a bare TypeError and
 * no status. Say what happened and what still works, rather than surfacing
 * "Failed to fetch", which reads like a bug in the scanner.
 */
function describe(e: any): string {
  const msg = e?.message ?? String(e);
  const blocked = e instanceof TypeError && /failed to fetch|load failed|networkerror/i.test(msg);
  if (!blocked) return msg;
  networkBlocked.value = true;
  mode.value = "sample";
  return "This page cannot reach GitHub. It is embedded in a sandbox that blocks outbound requests, so scanning by URL is unavailable here. Sample repositories and local folders run the same engine. To scan by URL, run the site locally or deploy it to your own host.";
}

async function guard(fn: () => Promise<void>) {
  busy.value = true; error.value = ""; status.value = "Starting";
  try { await fn(); } catch (e: any) { error.value = describe(e); } finally { busy.value = false; status.value = ""; }
}

onMounted(() => { if (!store.current.value && samples.length) scanSample(); });
</script>

<template>
  <div>
    <!-- The hero is the product doing its job, not a claim about it. -->
    <Board
      :inventory="result?.inventory"
      :findings="findings"
      :subject="result?.source.label"
      subject-to="/app"
      :note="fetchNote || undefined"
      :busy="busy"
      :phase="scanner.phase.value || status"
      :percent="percent"
    />

    <section class="section" id="scan-source">
      <div class="section-head">
        <h1>Which of your API calls has a deadline?</h1>
        <p>
          DocsWatcher reads a repository and finds every external contract in it, from SDK calls
          and endpoints to model IDs in configuration and pinned API versions. It matches them
          against {{ trackedChanges }} published provider deprecations and tells you which ones
          have a date on them, at the file and the line. The scan runs in this tab and nothing is
          uploaded.
        </p>
      </div>

      <div class="tabs" role="tablist" aria-label="What to scan">
        <button role="tab" :aria-selected="mode === 'sample'" @click="mode = 'sample'">Sample repository</button>
        <button role="tab" :aria-selected="mode === 'github'" @click="mode = 'github'">GitHub URL</button>
        <button role="tab" :aria-selected="mode === 'folder'" @click="mode = 'folder'">Local folder</button>
      </div>

      <p v-if="networkBlocked && mode === 'github'" class="notice" role="status">
        This host blocks outbound requests, so URL scanning is unavailable here. Use a sample or a
        local folder, or run the site yourself.
      </p>

      <form v-if="mode === 'github'" class="stack" @submit.prevent="scanGitHub">
        <div class="row">
          <input
            id="repo-url"
            class="input grow"
            style="flex-basis: 320px"
            v-model="url"
            placeholder="https://github.com/owner/repo"
            aria-label="GitHub repository URL"
            :disabled="busy"
          />
          <button id="scan-github" class="btn solid" type="submit" :disabled="busy">Scan repository</button>
        </div>
        <div v-if="!relay" class="row t2">
          <input
            id="gh-token"
            class="input grow"
            style="flex-basis: 260px"
            type="password"
            v-model="token"
            placeholder="GitHub token, optional"
            aria-label="GitHub token"
            autocomplete="off"
          />
          <span class="ink-faint">
            Held in memory only. Files come from jsDelivr first, then the GitHub API, which allows
            60 requests an hour for each address.
          </span>
        </div>
      </form>

      <div v-else-if="mode === 'folder'" class="stack">
        <p class="t2 ink-soft">
          Pick a project folder. Files are read in this tab. Vendored directories and files over
          1 MB are skipped.
        </p>
        <input
          id="folder-input"
          type="file"
          webkitdirectory
          multiple
          @change="scanFolder"
          :disabled="busy"
          aria-label="Choose a folder"
        />
      </div>

      <div v-else class="stack">
        <div class="row">
          <select id="sample-select" class="select" v-model="sample" :disabled="busy" aria-label="Sample repository">
            <optgroup label="Real public repositories">
              <option v-for="s in realSamples" :key="s.name" :value="s.name">{{ s.name }} at {{ s.sha }}</option>
            </optgroup>
            <optgroup label="Knowledge base fixtures">
              <option v-for="s in fixtureSamples" :key="s.name" :value="s.name">{{ s.name }}</option>
            </optgroup>
          </select>
          <button id="scan-sample" class="btn solid" @click="scanSample" :disabled="busy">Scan sample</button>
        </div>
        <p v-if="selectedSample?.real" class="t2 ink-soft">
          A vendored copy of <strong>{{ selectedSample.name }}</strong> at commit
          <span class="mono">{{ selectedSample.sha }}</span>, scanned here by the same engine that
          runs in continuous integration. {{ selectedSample.note }}
        </p>
        <p v-else class="t2 ink-faint">
          A fixture from the open knowledge base, with a pinned expected result.
        </p>
      </div>

      <p v-if="error" class="notice bad" role="alert">{{ error }}</p>
    </section>

    <template v-if="result">
      <section class="section">
        <div class="section-head">
          <h2>What is expiring</h2>
          <p>Ordered by how soon it bites. Open any row for the evidence, the migration notes and a fix prompt.</p>
        </div>
        <div id="findings">
          <FindingsList :findings="findings" />
        </div>
      </section>

      <section class="section" id="inventory">
        <div class="section-head">
          <h2>Everything this repository calls</h2>
          <p>Every external contract found, including the ones nobody remembers adding.</p>
        </div>
        <InventoryTable :contracts="result.inventory.contracts" :findings="findings" />
      </section>
    </template>

    <section v-else-if="!busy" class="section empty">
      <h3>Nothing scanned yet</h3>
      <p>Choose a sample repository, paste a GitHub URL, or pick a folder from your machine.</p>
    </section>
  </div>
</template>
