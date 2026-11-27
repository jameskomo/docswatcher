<script setup lang="ts">
import type { InputFile, RepoRef } from "~~/engine/types";
import { fetchViaApi, fetchViaRelay, readFolder, fetchViaJsDelivr } from "~/utils/fetchRepo";
import { fetchViaGitLab } from "~/utils/gitlab";
import { parseScanInput, parseShareParam, shareParam, targetLabel } from "~/utils/scanTarget";
import { daysBetween } from "~~/engine/matcher";
import type { Finding } from "~~/engine/types";
import type { ExcerptLine } from "~/components/ProofCard.vue";
import { contractLabel, fmtDate } from "~/utils/format";

const config = useRuntimeConfig();
const route = useRoute();
const router = useRouter();
const relay = (config.public.relayUrl as string) || "";
const { knowledge, samples, realSamples, fixtureSamples, defaultSample } = useKnowledge();
const scanner = useScanner();
const store = useScanStore();

const trackedChanges = knowledge.providers.reduce((n, p) => n + p.changes.length, 0);

// Every capability, one card each, linked to where it lives. Counts come from the knowledge base.
const DOCS = "https://github.com/jameskomo/docswatcher/blob/main/docs";
type CapLink = { label: string; to?: string; href?: string };
const capabilities: Array<{ id: string; icon: "search" | "check" | "chat" | "event" | "code" | "group"; title: string; text: string; links: CapLink[] }> = [
  {
    id: "scan", icon: "search", title: "Scan a repository",
    text: `A GitHub or GitLab URL (gitlab.com, or a self-managed GitLab where this site is allowed to reach it), a local folder, or a sample. Call sites in Java, Python, TypeScript, JavaScript, Go, Ruby, PHP and C#, matched against ${trackedChanges} records from ${knowledge.providers.length} providers, in your browser.`,
    links: [{ label: "Scan now", to: "/#scan-source" }, { label: "How the scan works", to: "/about" }],
  },
  {
    id: "ci", icon: "check", title: "In CI",
    text: "A GitHub Action, or a GitLab CI template that adds a Code Quality report to the merge request. Both fail the build on a dated shutdown. Anywhere else, one binary for Linux, macOS arm64 or Windows.",
    links: [{ label: "GitHub Actions", to: "/ci" }, { label: "GitLab CI", to: "/ci#gitlab" }, { label: "Downloads", href: "https://github.com/jameskomo/docswatcher/releases/latest" }],
  },
  {
    id: "agents", icon: "chat", title: "In your AI assistant",
    text: "An MCP server for Claude Code, Cursor and others: check_api, upcoming_deprecations and scan_repository, so the assistant checks a model or API before it writes the call. Try it here with your own Claude, OpenAI or Gemini key.",
    links: [{ label: "Set it up", to: "/agents" }, { label: "Try it with your own key", to: "/agents#try" }],
  },
  {
    id: "calendar", icon: "event", title: "Calendar and email alerts",
    text: "Every dated shutdown by month. Subscribe as a calendar (.ics), an Atom feed or open JSON, or ask for an email 30 and 7 days before each date, confirmed from your inbox first.",
    links: [{ label: "Calendar", to: "/calendar" }, { label: "Email alerts", to: "/calendar#email-alerts" }],
  },
  {
    id: "own", icon: "code", title: "Your own APIs",
    text: "Record your internal services' deprecations in a repository's .docswatcher/ folder, or once in your organisation's shared .docswatcher repository. Every scan, CI run and assistant check then finds them the same way.",
    links: [{ label: "How to write them", href: `${DOCS}/19-your-own-apis.md` }],
  },
  {
    id: "teams", icon: "group", title: "For teams",
    text: "Sign in with GitHub or GitLab: findings across every repository, the blast radius of one shutdown, email and Slack alerts before the date, and what production actually calls. The GitHub App and GitLab integration scan on every push and open an issue per finding.",
    links: [{ label: "Dashboard", to: "/app" }, { label: "What teams get", to: "/teams" }],
  },
];

useHead({
  title: "DocsWatcher, find the API calls in your code that have a deadline",
  meta: [{
    name: "description",
    content: `Scan a repository for the external APIs it calls and match them against ${trackedChanges} published provider deprecations. The scan runs in your browser.`,
  }],
});

const mode = ref<"sample" | "url" | "folder">("sample");
const networkBlocked = ref(false);
const url = ref("");
const token = ref("");
const sample = ref(defaultSample?.name ?? "");
const selectedSample = computed(() => samples.find((s) => s.name === sample.value));
const busy = ref(false);
const status = ref("");
const error = ref("");
const fetchNote = ref("");
/** What the URL field names as typed, so the token field can say where a token would go. */
const target = computed(() => parseScanInput(url.value));
const tokenHost = computed(() => (target.value?.host === "gitlab" ? new URL(target.value.project.origin).host : "GitHub"));

const result = computed(() => store.current.value);
const findings = computed(() => (result.value?.findings ?? []).filter((f) => store.effectiveStatus(f) === "open"));
const percent = computed(() => {
  const p = scanner.progress.value;
  return p && p.total ? Math.round((100 * p.done) / p.total) : null;
});

async function runScan(files: InputFile[], repo: RepoRef, source: { label: string; kind: "sample" | "github" | "gitlab" | "folder" }) {
  status.value = "Scanning AST";
  const r = await scanner.run(files, repo);
  store.save({ inventory: r.inventory, findings: r.findings, source, own: r.own, at: new Date().toISOString() });
  excerpt.value = excerptFor(files, headline.value);
}

/**
 * The finding the hero card leads with: something that stops working, the longest-dead first, then the
 * nearest date. Undated and informational findings only when there is nothing else.
 */
const headline = computed<Finding | null>(() => {
  const rank = (f: Finding) => (f.severity === "breaking" ? 0 : f.severity === "warning" ? 1 : 2);
  // Of two findings on the same line, the one whose name is written in the code (the SDK call, the
  // model string) shows better than one the scanner inferred (the endpoint behind that call).
  const literal = (f: Finding) => (f.evidence[0]!.snippet.includes(contractLabel(f.contract).key) ? 0 : 1);
  const sorted = [...findings.value].filter((f) => f.evidence.length).sort((a, b) =>
    rank(a) - rank(b) || (a.daysRemaining ?? Infinity) - (b.daysRemaining ?? Infinity) || literal(a) - literal(b));
  return sorted[0] ?? null;
});

/** Source lines around the headline finding, kept in memory only: the scanned files are not stored. */
const excerpt = ref<ExcerptLine[] | null>(null);
function excerptFor(files: InputFile[], f: Finding | null): ExcerptLine[] | null {
  const ev = f?.evidence[0];
  const file = ev && files.find((x) => x.path === ev.path);
  if (!f || !ev || !file) return null;
  const text = file.text.split("\n");
  const hits = new Set(f.evidence.filter((e) => e.path === ev.path).map((e) => e.line));
  const start = Math.max(1, ev.line - 2);
  // Reach a second hit a few lines further down, so one card can show both.
  const further = [...hits].filter((n) => n > ev.line && n <= ev.line + 8);
  const end = Math.min(text.length, Math.max(start + 6, ...further));
  const out: ExcerptLine[] = [];
  for (let n = start; n <= end; n++) out.push({ n, text: text[n - 1] ?? "", hit: hits.has(n) });
  return out;
}

/**
 * After a reload the scanned files are gone, but a bundled sample's are still here: rebuild its excerpt
 * from them, so the card shows the code around the call, not the one line the store kept.
 */
const shownExcerpt = computed<ExcerptLine[] | null>(() => {
  if (excerpt.value) return excerpt.value;
  const src = result.value?.source;
  if (src?.kind !== "sample") return null;
  const s = samples.find((x) => src.label === `${x.name} at ${x.sha}` || src.label === `Example ${x.name}`);
  return s ? excerptFor(s.files, headline.value) : null;
});

function showAll() {
  document.getElementById("findings")?.scrollIntoView({ behavior: "smooth", block: "start" });
}
function startScan() {
  mode.value = "url";
  document.getElementById("scan-source")?.scrollIntoView({ behavior: "smooth", block: "start" });
  nextTick(() => document.getElementById("repo-url")?.focus());
}

// The departures board: every dated shutdown still to come, nearest first.
const today = new Date();
const todayIso = today.toISOString().slice(0, 10);
const departures = knowledge.providers
  .flatMap((p) => p.changes.map((c) => ({ c, provider: p.info.name })))
  .filter(({ c }) => c.status === "active" && c.severity === "breaking" && !!c.effective && c.effective >= todayIso)
  .sort((a, b) => (a.c.effective! < b.c.effective! ? -1 : a.c.effective! > b.c.effective! ? 1 : 0))
  .slice(0, 9)
  .map(({ c, provider }) => {
    const days = daysBetween(today, c.effective!);
    return { id: c.id, provider, title: c.title, date: fmtDate(c.effective), days, final: days <= 7 };
  });
const horizon = departures.length ? departures[departures.length - 1]!.days : 0;

async function scanSample(targetName?: string) {
  if (targetName) sample.value = targetName;
  const s = samples.find((x) => x.name === sample.value);
  if (!s) return;
  await guard(async () => {
    fetchNote.value = "";
    const ref = s.real
      ? { host: "github", owner: s.name.split("/")[0], name: s.name.split("/")[1], ref: s.sha ?? "HEAD", sha: s.sha ?? "0000000" }
      : { host: "fixture", owner: "docswatcher", name: s.name, ref: "fixture", sha: "0000000" };
    await runScan(s.files, ref, { label: s.real ? `${s.name} at ${s.sha}` : `Example ${s.name}`, kind: "sample" });
    // A link to the previous repository would no longer describe what is on screen.
    if (route.query.repo) router.replace({ path: "/", query: {} });
  });
}

async function scanUrl() {
  const t = parseScanInput(url.value);
  if (!t) {
    error.value = "That is not a repository URL. Use https://github.com/owner/repo, or https://gitlab.com/group/project for GitLab. A self-managed GitLab works by its full URL.";
    return;
  }
  const label = targetLabel(t);
  await guard(async () => {
    const progress = (msg: string, done?: number, total?: number) => { status.value = total ? `${msg} ${done} of ${total}` : msg; };
    const routes: Array<{ name: string; run: () => Promise<any> }> = [];
    if (t.host === "gitlab") {
      // One route: the GitLab API answers cross-origin reads itself, so no mirror or relay is needed.
      routes.push({ name: "GitLab API", run: () => fetchViaGitLab(t.project, token.value.trim() || undefined, progress) });
    } else {
      if (relay) routes.push({ name: "relay", run: () => fetchViaRelay(relay, t.repo, progress) });
      routes.push({ name: "jsDelivr", run: () => fetchViaJsDelivr(t.repo, progress) });
      routes.push({ name: "GitHub API", run: () => fetchViaApi(t.repo, token.value.trim() || undefined, progress) });
    }

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
        `Could not reach ${label}. ${tried.join(". ")}. Sample repositories and local folders still work here and run the same engine. To scan by URL, run the site locally or deploy it to your own host.`,
      );
    }
    fetchNote.value = `${res.files.length} text files read`
      + (res.binaries ? `, ${res.binaries} binary files skipped` : "")
      + (res.truncated ? ". Large repository, so only the first 300 relevant files were read." : "");
    await runScan(res.files, res.repo, { label, kind: t.host });
    // The address bar becomes the share link: opening it runs the same scan again, fresh.
    router.replace({ path: "/", query: { repo: shareParam(res.repo) } });
  });
}

const folderChosen = ref("");
function onFolderChosen(ev: Event) {
  const files = (ev.target as HTMLInputElement).files;
  if (files && files.length) {
    const name = (files[0] as File & { webkitRelativePath?: string }).webkitRelativePath?.split("/")[0] || "Folder";
    folderChosen.value = `${name}: ${files.length} ${files.length === 1 ? "file" : "files"}`;
  }
  return scanFolder(ev);
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

function describe(e: any): string {
  const msg = e?.message ?? String(e);
  const blocked = e instanceof TypeError && /failed to fetch|load failed|networkerror/i.test(msg);
  if (!blocked) return msg;
  networkBlocked.value = true;
  mode.value = "sample";
  return "This page cannot reach GitHub. It is embedded in a sandbox that blocks outbound requests, so scanning by URL is unavailable here. Sample repositories and local folders run the same engine. To scan by URL, run the site locally or deploy it to your own host.";
}

async function guard(fn: () => Promise<void>) {
  busy.value = true; error.value = ""; status.value = "Initializing WebAssembly engine";
  try { await fn(); } catch (e: any) { error.value = describe(e); } finally { busy.value = false; status.value = ""; }
}

// A live scan link: /#/?repo=owner/name scans that GitHub repository on arrival, and
// /#/?repo=gitlab.com/group/project that GitLab project. See docs/15-feeds-and-sharing.md.
onMounted(() => {
  const linked = typeof route.query.repo === "string" ? parseShareParam(route.query.repo) : null;
  if (linked) {
    mode.value = "url";
    url.value = linked.host === "github" ? `https://github.com/${linked.repo.owner}/${linked.repo.name}` : `${linked.project.origin}/${linked.project.path}`;
    scanUrl();
    return;
  }
  if (!store.current.value && samples.length) scanSample();
});

/** The ?repo= value when the current result is a public GitHub repository or GitLab project, which is what a link can re-scan. */
const shareable = computed(() => shareParam(result.value?.inventory?.repo));
const base = ref("");
onMounted(() => { base.value = location.href.split("#")[0]; });
const shareLink = computed(() => (shareable.value ? `${base.value}#/?repo=${shareable.value}` : ""));
/**
 * A provenance mark, not a status badge. It cannot report a count: the scan runs in the
 * reader's browser, so nothing here knows what the repository looks like today. Naming it
 * "API deprecations" implied a number it never carried.
 */
const badge = computed(() =>
  `[![Scanned with DocsWatcher](https://img.shields.io/badge/Scanned%20with-DocsWatcher-2563eb)](${shareLink.value})`);
const linkCopied = ref(false);
async function copyLink() {
  try {
    await navigator.clipboard.writeText(shareLink.value);
    linkCopied.value = true;
    setTimeout(() => { linkCopied.value = false; }, 2000);
  } catch { /* the link is visible and selectable below */ }
}
</script>

<template>
  <div>
    <!-- Hero: what DocsWatcher does, the scanner, and the proof from the scan on screen -->
    <section class="hero" id="scan-source">
      <div class="hero-copy">
        <p class="eyebrow">{{ trackedChanges }} shutdown notices · {{ knowledge.providers.length }} providers · read in your browser</p>
        <h1>Some of your API calls have a shutdown date.</h1>
        <p class="hero-lede">
          DocsWatcher reads your repository, finds every external API and AI model it calls, and shows you the file,
          the line and the day each one stops working.
        </p>

        <div class="scan-box">
          <div class="tabs" role="tablist" aria-label="What to scan">
            <button role="tab" :aria-selected="mode === 'url'" @click="mode = 'url'">
              <Icon name="link" />
              <span>GitHub or GitLab URL</span>
            </button>
            <button role="tab" :aria-selected="mode === 'sample'" @click="mode = 'sample'">
              <Icon name="bolt" />
              <span>Sample repository</span>
            </button>
            <button role="tab" :aria-selected="mode === 'folder'" @click="mode = 'folder'">
              <Icon name="folder" />
              <span>Local folder</span>
            </button>
          </div>

          <p v-if="networkBlocked && mode === 'url'" class="notice" role="status">
            This host blocks outbound requests, so URL scanning is unavailable here. Use a sample or a
            local folder, or run the site yourself.
          </p>

          <!-- Repository URL Mode: GitHub, gitlab.com, or a self-managed GitLab -->
          <form v-if="mode === 'url'" class="scan-pane" @submit.prevent="scanUrl">
            <label for="repo-url" class="visually-hidden">Public GitHub repository or GitLab project URL</label>
            <div class="scan-row">
              <input
                id="repo-url"
                class="input"
                v-model="url"
                placeholder="github.com/owner/repo"
                aria-label="Repository URL"
                :disabled="busy"
              />
              <button id="scan-github" class="btn solid" type="submit" :disabled="busy">Scan repository</button>
            </div>
            <p class="hint">GitHub or GitLab, public, or private with a token. Files are read into this tab; nothing is uploaded.</p>
            <div v-if="!relay || target?.host === 'gitlab'" class="token">
              <label for="gh-token">Personal access token (optional)</label>
              <input
                id="gh-token"
                class="input"
                type="password"
                v-model="token"
                :placeholder="target?.host === 'gitlab' ? 'glpat-xxxxxxxxxxxxxxxxxxxx' : 'ghp_xxxxxxxxxxxxxxxxxxxx'"
                :aria-label="`${tokenHost} token`"
                autocomplete="off"
              />
              <span v-if="target?.host === 'gitlab'" class="hint" data-testid="token-hint">
                Stored only in memory and sent only to {{ tokenHost }}. Needed for a private project
                (read_api scope); gitlab.com allows 500 API requests a minute without one.
              </span>
              <span v-else class="hint" data-testid="token-hint">
                Stored only in memory. Increases GitHub API rate limit from 60 to 5,000 req/hr.
              </span>
            </div>
          </form>

          <!-- Sample Repo Mode -->
          <div v-else-if="mode === 'sample'" class="scan-pane">
            <div class="preset-chips">
              <button
                type="button"
                class="preset-pill"
                :class="{ active: sample === 'openai/openai-quickstart-python' }"
                @click="scanSample('openai/openai-quickstart-python')"
                :disabled="busy"
              >OpenAI Quickstart</button>
              <button
                type="button"
                class="preset-pill"
                :class="{ active: sample === 'Shopify/shopify-app-template-node' }"
                @click="scanSample('Shopify/shopify-app-template-node')"
                :disabled="busy"
              >Shopify App Template</button>
              <button
                type="button"
                class="preset-pill"
                :class="{ active: sample === 'stripe-java-sources' }"
                @click="scanSample('stripe-java-sources')"
                :disabled="busy"
              >Stripe Java SDK</button>
            </div>
            <label for="sample-select" class="visually-hidden">Sample repository</label>
            <div class="scan-row">
              <select id="sample-select" class="select" v-model="sample" :disabled="busy" aria-label="Sample repository">
                <optgroup label="Real public repositories">
                  <option v-for="s in realSamples" :key="s.name" :value="s.name">{{ s.name }} at {{ s.sha }}</option>
                </optgroup>
                <optgroup label="Knowledge base fixtures">
                  <option v-for="s in fixtureSamples" :key="s.name" :value="s.name">{{ s.name }}</option>
                </optgroup>
              </select>
              <button id="scan-sample" class="btn solid" @click="scanSample()" :disabled="busy">Scan sample</button>
            </div>
            <p v-if="selectedSample?.real" class="hint">
              Vendored copy of {{ selectedSample.name }} at commit {{ selectedSample.sha }}, scanned in your browser.
              {{ selectedSample.note }}
            </p>
            <p v-else class="hint">Knowledge base regression fixture with pinned expected findings.</p>
          </div>

          <!-- Local Folder Mode -->
          <div v-else-if="mode === 'folder'" class="scan-pane">
            <!-- The browser's own file button cannot be styled; this label is the button, and the real
                 input stays in the page (visually hidden) for the keyboard and screen readers. -->
            <div class="folder-pick">
              <label class="btn solid" :class="{ disabled: busy }">
                <span>Choose a folder</span>
                <input
                  id="folder-input"
                  class="visually-hidden"
                  type="file"
                  webkitdirectory
                  multiple
                  @change="onFolderChosen"
                  :disabled="busy"
                />
              </label>
              <span class="hint" data-testid="folder-chosen">{{ folderChosen || "No folder chosen yet" }}</span>
            </div>
            <p class="hint">Parsed with tree-sitter in WebAssembly, inside this tab. No file leaves your machine.</p>
          </div>

          <p v-if="error" class="notice bad" role="alert">{{ error }}</p>
        </div>
      </div>

      <ProofCard
        v-if="result || busy"
        class="hero-proof"
        :finding="busy ? null : headline"
        :excerpt="busy ? null : shownExcerpt"
        :subject="busy ? undefined : result?.source.label"
        :note="fetchNote || undefined"
        :total="findings.length"
        :busy="busy"
        :phase="scanner.phase.value || status"
        :percent="percent"
        @show-all="showAll"
      />
      <div v-else class="hero-proof hero-empty">
        <p class="display">Nothing scanned yet</p>
        <p>Paste a GitHub or GitLab URL, choose a sample repository, or pick a folder from your machine.</p>
      </div>
    </section>

    <!-- The scan on screen, in full -->
    <template v-if="result && !busy">
      <section v-if="result.own" class="section" data-testid="own-records">
        <div v-if="result.own.errors.length" class="notice bad" role="alert">
          <strong>{{ result.own.summary }}.</strong>
          This scan matched the bundled knowledge base only. Fix these in <code>.docswatcher/</code>,
          or run <code>docswatcher validate</code> there
          (<NuxtLink to="/teams">your own APIs</NuxtLink>):
          <ul class="t2" style="margin-block: var(--s2) 0">
            <li v-for="e in result.own.errors.slice(0, 20)" :key="e">{{ e }}</li>
          </ul>
          <p v-if="result.own.errors.length > 20" class="t2">…and {{ result.own.errors.length - 20 }} more.</p>
        </div>
        <p v-else class="notice">
          {{ result.own.summary }}, read from this repository's <code>.docswatcher/</code> and matched
          with the rest.
          <template v-if="result.own.warnings.length"> Warnings: {{ result.own.warnings.join("; ") }}</template>
        </p>
      </section>

      <section v-if="shareable" class="section share" data-testid="share">
        <div class="share-row">
          <button type="button" class="btn" @click="copyLink" data-testid="share-copy">
            {{ linkCopied ? "Link copied" : "Copy link to this scan" }}
          </button>
          <a class="t2 share-link" :href="shareLink">{{ shareLink }}</a>
        </div>
        <details class="share-badge">
          <summary class="t2">Add a badge to {{ shareable }}'s README</summary>
          <p class="t2 ink-soft">
            Paste this into <code>README.md</code> in {{ shareable }}, near the top.
            It marks the repository as scanned; clicking it runs a fresh scan in the reader's own
            browser, so it cannot go stale.
          </p>
          <Snippet :code="badge" lang="markdown" testid="share-badge" wrap />
        </details>
      </section>

      <section class="section">
        <div class="section-head">
          <p class="eyebrow">{{ result.source.label }}</p>
          <h2>What is expiring</h2>
          <p>Nearest date first. Open any finding for the code evidence, the migration and a prompt for your coding agent.</p>
        </div>
        <div id="findings">
          <FindingsList :findings="findings" />
        </div>
      </section>

      <section class="section" id="inventory">
        <div class="section-head">
          <h2>Everything this repository calls</h2>
          <p>Every external API contract found in the code: SDK methods, endpoints, API versions and model IDs.</p>
        </div>
        <InventoryTable
          :contracts="result.inventory?.contracts || []"
          :findings="findings"
          :repo="result.inventory?.repo"
        />
      </section>
    </template>

    <!-- Departures: what leaves next, for everyone -->
    <section class="band departures" aria-labelledby="departures-title">
      <div class="band-inner">
        <div class="departures-head">
          <div class="departures-intro">
            <p class="eyebrow">Departures · next {{ horizon }} days</p>
            <h2 id="departures-title">Leaving soon</h2>
            <p>Every dated shutdown in the knowledge base, nearest first. A scan only lists the ones your code actually calls.</p>
          </div>
          <div class="departures-subscribe">
            <a class="feed" :href="`${base}feeds/deprecations.ics`">.ics</a>
            <a class="feed" :href="`${base}feeds/deprecations.atom`">Atom</a>
            <a class="feed" :href="`${base}feeds/deprecations.json`">JSON</a>
            <NuxtLink class="btn signal" to="/calendar#email-alerts">Email me 30 and 7 days before</NuxtLink>
          </div>
        </div>
        <div class="board-list" role="table" aria-label="Upcoming shutdowns">
          <div class="board-row board-headrow" role="row">
            <span role="columnheader">In</span>
            <span class="board-meta"><span role="columnheader">Date</span><span role="columnheader">Provider</span></span>
            <span role="columnheader">Change</span>
            <span role="columnheader" class="board-status">Status</span>
          </div>
          <div v-for="d in departures" :key="d.id" class="board-row" :class="{ final: d.final }" role="row">
            <span role="cell" class="board-days">{{ d.days }}d</span>
            <span class="board-meta"><span role="cell" class="board-date">{{ d.date }}</span><span role="cell">{{ d.provider }}</span></span>
            <span role="cell" class="board-what">{{ d.title }}</span>
            <span role="cell" class="board-status">{{ d.final ? "Final call" : "Scheduled" }}</span>
          </div>
        </div>
        <p class="departures-more"><NuxtLink to="/calendar">Open the full calendar</NuxtLink></p>
      </div>
    </section>

    <section class="rule">
      <p class="display">
        Not a changelog feed. A deprecation becomes a finding only when your code calls it,
        <mark>with the file, the line and the date.</mark>
      </p>
    </section>

    <section class="section" id="what-it-does" data-testid="capabilities">
      <div class="section-head">
        <p class="eyebrow">One knowledge base</p>
        <h2>Wherever you want the answer</h2>
      </div>
      <div class="caps">
        <div v-for="c in capabilities" :key="c.id" class="cap" :data-testid="`cap-${c.id}`">
          <h3><Icon :name="c.icon" :size="18" />{{ c.title }}</h3>
          <p>{{ c.text }}</p>
          <div class="cap-links">
            <template v-for="l in c.links" :key="l.label">
              <NuxtLink v-if="l.to" :to="l.to">{{ l.label }}</NuxtLink>
              <a v-else :href="l.href" target="_blank" rel="noopener">{{ l.label }}</a>
            </template>
          </div>
        </div>
      </div>
    </section>

    <section class="closing">
      <h2>Find out before production does.</h2>
      <div class="closing-actions">
        <button type="button" class="btn solid" @click="startScan">Scan a repository</button>
        <NuxtLink class="btn" to="/about">How the scan works</NuxtLink>
      </div>
    </section>
  </div>
</template>

<style scoped>
/* Hero */
.hero {
  /* flex-direction is explicit: an older global .hero rule in main.css sets column. */
  display: flex; flex-direction: row; flex-wrap: wrap; align-items: flex-start; gap: 56px;
  padding: 72px 0 88px;
}
.hero-copy { flex: 1 1 440px; min-width: 0; display: flex; flex-direction: column; gap: 26px; }
.hero h1 { font-size: clamp(2.375rem, 4.8vw, 3.875rem); line-height: 1.02; }
.hero-lede { margin: 0; font-size: 1.0625rem; line-height: 1.55; color: var(--ink-soft); max-width: 34em; }
.hero-proof { flex: 1 1 500px; min-width: 0; }
.hero-empty {
  border: 1.5px dashed var(--hair-strong); padding: 32px 24px; display: grid; gap: 10px; color: var(--ink-soft);
}
.hero-empty .display { margin: 0; font-size: 1.5rem; color: var(--ink-max); }

.scan-box { display: flex; flex-direction: column; gap: 14px; }
.scan-pane { display: flex; flex-direction: column; gap: 12px; }
.scan-row { display: flex; flex-wrap: wrap; }
.scan-row .input, .scan-row .select { flex: 1 1 240px; min-width: 0; border-right: 0; }
.scan-row .btn { flex: 0 0 auto; min-height: 48px; }
.hint { margin: 0; font-size: var(--t2); color: var(--ink-faint); }
.token { display: grid; gap: 6px; }
.token label { font-size: var(--t2); font-weight: 700; }
.preset-chips { display: flex; flex-wrap: wrap; gap: 8px; }

/* The scan on screen */
.share { padding-block: var(--s3); }
.share-row { display: flex; flex-wrap: wrap; align-items: center; gap: var(--s3); }
.share-link { overflow-wrap: anywhere; color: var(--ink-soft); }
.share-badge { margin-top: var(--s3); }
.share-badge summary { cursor: pointer; }
.share-badge p { margin-block: var(--s2); }
.section-head .eyebrow { margin-bottom: 8px; }

/* Departures board */
.departures-head {
  display: flex; flex-wrap: wrap; justify-content: space-between; align-items: flex-end; gap: 24px; margin-bottom: 36px;
}
.departures-intro { display: grid; gap: 12px; max-width: 640px; }
.departures-intro h2 { font-size: clamp(2rem, 3.8vw, 3rem); line-height: 1; }
.departures-intro p:last-child { margin: 0; color: var(--night-soft); }
.departures-subscribe { display: flex; flex-wrap: wrap; gap: 8px; }
.feed {
  display: inline-flex; align-items: center; min-height: 44px; padding: 0 14px;
  border: 1px solid #4a4a45; color: var(--night-ink); text-decoration: none; font-size: var(--t2);
}
.feed:hover { border-color: var(--night-ink); }
.departures .btn.signal { color: var(--ink-black); }

.board-list { border-top: 1.5px solid var(--night-ink); }
.board-row {
  display: grid; grid-template-columns: 64px 128px 160px minmax(0, 1fr) 110px;
  column-gap: 16px; align-items: baseline;
  padding: 16px 0; border-top: 1px solid var(--night-line);
}
.board-headrow {
  border-top: 0; padding: 14px 0;
  color: var(--night-faint); font-size: var(--t1); letter-spacing: 0.06em; text-transform: uppercase;
}
.board-meta { display: contents; }
.board-days { font-size: 1.375rem; font-weight: 700; }
.board-date { color: var(--night-soft); }
.board-what { font-size: var(--t3); }
.board-status { text-align: right; font-size: var(--t1); letter-spacing: 0.06em; text-transform: uppercase; color: var(--night-faint); }
.board-row.final .board-days, .board-row.final .board-status { color: var(--signal); }
.departures-more { margin: 24px 0 0; }

/* The rule */
.rule { padding: 96px 0 64px; }
.rule .display { margin: 0; max-width: 22em; font-size: clamp(1.625rem, 3vw, 2.375rem); line-height: 1.18; }
.rule mark { background: var(--signal); color: var(--ink-black); padding: 0 6px; }

/* Capabilities: three across */
.caps { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 300px), 1fr)); }

/* Closing */
.closing {
  display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 24px;
  margin-top: var(--s7); padding: 64px 0 0; border-top: 1.5px solid var(--ink-black);
}
.closing h2 { font-size: clamp(1.75rem, 3.4vw, 2.75rem); line-height: 1.05; max-width: 14em; }
.closing-actions { display: flex; flex-wrap: wrap; gap: 12px; }

@media (max-width: 640px) {
  .hero { padding: 40px 0 56px; gap: 40px; }
  .scan-row .input, .scan-row .select { flex-basis: 100%; border-right: 1.5px solid var(--ink-black); }
  .scan-row .btn { flex: 1 1 100%; margin-top: 8px; }
  .board-headrow { display: none; }
  .board-row {
    grid-template-columns: 56px minmax(0, 1fr) auto;
    grid-template-areas: "days what what" "days meta status";
    row-gap: 6px;
  }
  .board-days { grid-area: days; }
  .board-what { grid-area: what; }
  .board-status { grid-area: status; }
  .board-meta { display: flex; gap: 12px; grid-area: meta; font-size: var(--t1); }
  .rule { padding: 64px 0 40px; }
}
</style>
