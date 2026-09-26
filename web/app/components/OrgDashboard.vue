<script setup lang="ts">
import {
  ApiError, changesIn, openFindings, orgFinding, PROVIDER_NAME, providerRows, siteBase,
  type BlastRadius, type FindingAction, type HorizonMonth, type MapNode, type Me, type OrgApi,
  type Overview, type RepoFinding, type RepoSummary,
} from "~/utils/orgApi";
import { contractLabel, fmtDate } from "~/utils/format";
import { ingestEndpoint } from "~/utils/runtime";

/**
 * Every repository, one view: the organisation dashboard for a signed-in person.
 *
 * It shows only what the server returns for this session, which is already narrowed to the
 * repositories GitHub or GitLab says the person can see (docs/adr/0008-sign-in-with-github.md,
 * docs/adr/0011-gitlab.md). Everything is rendered as text.
 */
const props = defineProps<{ me: Me; api: OrgApi }>();
const emit = defineEmits<{ "signed-out": [] }>();

const orgs = computed(() => props.me.orgs ?? []);
const provider = computed(() => props.me.provider ?? "github");
const providerName = computed(() => PROVIDER_NAME[provider.value]);
const login = ref(orgs.value[0]?.login ?? "");

const overview = ref<Overview | null>(null);
const repos = ref<RepoSummary[]>([]);
const nodes = ref<MapNode[]>([]);
const months = ref<HorizonMonth[]>([]);
const loading = ref(false);
const failed = ref("");
const expired = ref(false);

const open = computed(() => openFindings(months.value));
const rows = computed(() => providerRows(nodes.value, open.value));
const horizonFindings = computed(() => open.value.map(orgFinding));
const changes = computed(() => changesIn(months.value));
const withFindings = computed(() => repos.value.filter((r) => r.openFindings > 0));
const quiet = computed(() => repos.value.length - withFindings.value.length);

function problem(e: unknown, what: string): string {
  if (e instanceof ApiError && e.status === 401) {
    expired.value = true;
    return "Your session has ended. Sign in again to carry on.";
  }
  if (e instanceof ApiError && e.status === 404) return `${what} is not available to you.`;
  return `${what} could not be loaded (${e instanceof Error ? e.message : String(e)}).`;
}

async function loadOrg() {
  if (!login.value) return;
  loading.value = true;
  failed.value = "";
  try {
    const [o, r, m, h] = await Promise.all([
      props.api.overview(login.value), props.api.repos(login.value), props.api.map(login.value), props.api.horizon(login.value),
    ]);
    overview.value = o; repos.value = r; nodes.value = m; months.value = h;
  } catch (e) {
    failed.value = problem(e, `The ${login.value} organisation`);
  } finally {
    loading.value = false;
  }
}

/* One repository's findings, with actions. */
const repoId = ref<number | null>(null);
const repoFindings = ref<RepoFinding[]>([]);
const repoFailed = ref("");
const busy = ref<string | null>(null);
const outcome = ref<Record<string, { ok: boolean; text: string }>>({});
const selectedRepo = computed(() => repos.value.find((r) => r.id === repoId.value) ?? null);

async function openRepo(id: number) {
  repoId.value = id;
  repoFailed.value = "";
  outcome.value = {};
  try {
    repoFindings.value = (await props.api.repoFindings(id)).filter((r) => r.finding.status !== "fixed");
  } catch (e) {
    repoFindings.value = [];
    repoFailed.value = problem(e, "This repository");
  }
}

async function act(r: RepoFinding, action: FindingAction) {
  const key = `${r.repoId}|${r.finding.contract}|${r.finding.change}`;
  busy.value = key;
  try {
    await props.api.act(r.repoId, action, r.finding, action === "snooze" ? 30 : undefined);
    outcome.value = {
      ...outcome.value,
      [key]: {
        ok: true,
        text: action === "snooze" ? "Snoozed for 30 days."
          : action === "not-in-prod" ? "Marked as not running in production."
          : `Fix requested. If ${r.repoFullName} has the DocsWatcher fix workflow, it will open a pull request.`,
      },
    };
    if (action !== "fix") {
      await Promise.all([openRepoQuietly(r.repoId), loadOrg()]);
    }
  } catch (e) {
    const text = e instanceof ApiError && e.status === 403 ? `You need write access to ${r.repoFullName} to do this.`
      : e instanceof ApiError && e.status === 409 ? "Fix pull requests are GitHub only. On GitLab, the finding's issue and this dashboard carry it."
      : e instanceof ApiError && e.status === 404 ? "That finding is no longer there. Reload to see the latest."
      : e instanceof ApiError && e.status === 401 ? (expired.value = true, "Your session has ended. Sign in again to carry on.")
      : `That did not work (${e instanceof Error ? e.message : String(e)}).`;
    outcome.value = { ...outcome.value, [key]: { ok: false, text } };
  } finally {
    busy.value = null;
  }
}

async function openRepoQuietly(id: number) {
  const keep = outcome.value;
  await openRepo(id);
  outcome.value = keep;
}

/* From a runtime row to the findings it confirms: open that repository's list and go there. */
async function showFinding(id: number) {
  await openRepo(id);
  await nextTick();
  document.getElementById("repo-findings")?.scrollIntoView({ behavior: "smooth", block: "start" });
}
const pageHref = () => (typeof location === "undefined" ? "http://localhost/" : location.href);

/* The blast radius of one change. */
const changeId = ref("");
const blast = ref<BlastRadius | null>(null);
const blastFailed = ref("");
const blastByRepo = computed(() => {
  const groups = new Map<string, RepoFinding[]>();
  for (const f of blast.value?.findings ?? []) {
    const list = groups.get(f.repoFullName) ?? [];
    list.push(f);
    groups.set(f.repoFullName, list);
  }
  return [...groups.entries()].map(([repo, list]) => ({ repo, list }));
});

async function loadBlast() {
  blast.value = null;
  blastFailed.value = "";
  if (!changeId.value) return;
  try {
    blast.value = await props.api.blastRadius(login.value, changeId.value);
  } catch (e) {
    blastFailed.value = problem(e, "That change");
  }
}

watch(login, () => {
  repoId.value = null;
  repoFindings.value = [];
  changeId.value = "";
  blast.value = null;
  loadOrg();
});
watch(changes, (list) => {
  if (!changeId.value && list.length) {
    changeId.value = list[0]!.id;
  }
});
watch(changeId, loadBlast);
onMounted(loadOrg);

async function signOut() {
  try {
    await props.api.logout();
  } finally {
    emit("signed-out");
  }
}

const sev = (s: string) => overview.value?.findingsBySeverity?.[s as "breaking"] ?? 0;
const where = (r: RepoFinding) => r.finding.evidence.slice(0, 3).map((e) => `${e.path}:${e.line}`).join(", ");
const what = (r: RepoFinding) => contractLabel(r.finding.contract).key;
</script>

<template>
  <div data-testid="org-dashboard">
    <section class="section" style="padding-bottom: 0">
      <div class="row between org-head">
        <div class="section-head" style="margin-bottom: 0">
          <h1>Every repository, one view</h1>
          <p>
            Signed in as <span class="mono" data-testid="signed-in-as">{{ me.user?.login }}</span>.
            You see the repositories {{ providerName }} lets you see, in organisations where DocsWatcher is
            {{ provider === "gitlab" ? "connected" : "installed" }}.
          </p>
        </div>
        <div class="row org-controls">
          <label v-if="orgs.length > 1" class="row t2 ink-soft" style="gap: var(--s2)">
            <span>Organisation</span>
            <select id="org-select" v-model="login" class="select" data-testid="org-select">
              <option v-for="o in orgs" :key="o.installationId" :value="o.login">{{ o.login }}</option>
            </select>
          </label>
          <span v-else-if="login" class="mono t2" data-testid="org-name">{{ login }}</span>
          <button type="button" class="btn quiet" data-testid="sign-out" @click="signOut">Sign out</button>
        </div>
      </div>
    </section>

    <p v-if="expired" class="section notice bad" role="alert">
      Your session has ended. <a :href="api.loginUrl(provider)">Sign in with {{ providerName }} again.</a>
    </p>

    <section v-if="!orgs.length" class="section empty" data-testid="no-orgs">
      <h3>No organisation to show yet</h3>
      <p v-if="provider === 'gitlab'">
        None of the GitLab groups you belong to is connected to DocsWatcher on this deployment, or you
        cannot read any of the projects it covers. A maintainer can connect one below, then sign in again.
      </p>
      <p v-else>
        None of the organisations you belong to has the DocsWatcher GitHub App installed on this
        deployment, or you cannot see any of the repositories it covers. Ask an organisation owner
        to install it, then sign in again.
      </p>
    </section>

    <template v-else>
      <p v-if="failed" class="section notice bad" role="alert">{{ failed }}</p>

      <section class="section" id="org-overview" aria-live="polite">
        <div v-if="overview" class="hud-grid">
          <div class="hud-card">
            <span class="hud-label">Repositories</span>
            <span class="hud-val" data-testid="stat-repos">{{ overview.repos }}</span>
            <span class="hud-sub">{{ overview.contracts }} external contracts</span>
          </div>
          <div class="hud-card" :class="{ 'alert-critical': sev('breaking') > 0 }">
            <span class="hud-label">Breaking</span>
            <span class="hud-val" data-testid="stat-breaking">{{ sev("breaking") }}</span>
            <span class="hud-sub">open findings that stop working on a date</span>
          </div>
          <div class="hud-card" :class="{ 'alert-warning': sev('warning') > 0 }">
            <span class="hud-label">Warnings</span>
            <span class="hud-val" data-testid="stat-warning">{{ sev("warning") }}</span>
            <span class="hud-sub">{{ sev("info") }} more worth knowing</span>
          </div>
          <div class="hud-card">
            <span class="hud-label">Next deadline</span>
            <span class="hud-val" style="font-size: 1.5rem" data-testid="stat-next">{{ fmtDate(overview.nearestEffective) }}</span>
            <span class="hud-sub" v-if="overview.knowledgeVersion">knowledge base {{ overview.knowledgeVersion }}</span>
          </div>
        </div>
        <p v-else-if="loading" class="t2 ink-faint">Loading {{ login }}…</p>
      </section>

      <section class="section" id="org-map">
        <div class="section-head">
          <h2>What the organisation depends on</h2>
          <p>Every external provider your repositories call, with the share of contracts that has a deadline.</p>
        </div>
        <ProviderMap :rows="rows" centre="your org" :subject="login" />
      </section>

      <section class="section" id="org-horizon">
        <div class="section-head">
          <h2>The next twelve months</h2>
          <p>Every open deadline across the organisation. Overdue ones sit to the left of today.</p>
        </div>
        <div class="board-ruler" style="margin-top: var(--s3)">
          <Horizon :findings="horizonFindings" />
        </div>
      </section>

      <section class="section" id="org-repos">
        <div class="section-head">
          <h2>Repositories with open findings</h2>
          <p v-if="quiet">{{ quiet }} more {{ quiet === 1 ? "repository has" : "repositories have" }} nothing open.</p>
        </div>
        <div v-if="withFindings.length" class="table-wrap">
          <table>
            <thead>
              <tr><th>Repository</th><th>Open findings</th><th>Last scanned</th><th></th></tr>
            </thead>
            <tbody>
              <tr v-for="r in withFindings" :key="r.id" data-testid="org-repo">
                <td class="mono">{{ r.fullName }}<span v-if="!r.production" class="state">not in production</span></td>
                <td class="num">{{ r.openFindings }}</td>
                <td class="mono t2">{{ r.lastScannedSha ? r.lastScannedSha.slice(0, 7) : "not yet" }}</td>
                <td>
                  <button type="button" class="linkish" :aria-expanded="repoId === r.id" @click="openRepo(r.id)">
                    {{ repoId === r.id ? "Showing" : "Show findings" }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else-if="!loading && overview" class="empty">
          <div style="margin-bottom: var(--s2)"><SeverityChip severity="healthy" /></div>
          <h3>Nothing open</h3>
          <p>No repository you can see has an open finding.</p>
        </div>

        <div v-if="selectedRepo" id="repo-findings" class="section" style="padding-bottom: 0">
          <div class="section-head">
            <h3 class="mono">{{ selectedRepo.fullName }}</h3>
          </div>
          <p v-if="repoFailed" class="notice bad" role="alert">{{ repoFailed }}</p>
          <OrgFindings v-else :findings="repoFindings" :busy="busy" :outcome="outcome" @act="act" />
        </div>
      </section>

      <section class="section" id="org-runtime">
        <div class="section-head">
          <h2>What actually runs</h2>
          <p>Which deprecated calls production really makes, and how often, from the OpenTelemetry you already run.</p>
        </div>
        <OrgRuntime :api="api" :repos="repos" :endpoint="ingestEndpoint(siteBase(pageHref()))" @show-finding="showFinding" @expired="expired = true" />
      </section>

      <section class="section" id="blast-radius">
        <div class="section-head">
          <h2>The blast radius of one shutdown</h2>
          <p>Pick a change and see every repository and place it touches.</p>
        </div>
        <label v-if="changes.length" class="row t2 ink-soft" style="gap: var(--s2); flex-wrap: wrap">
          <span>Change</span>
          <select id="change-select" v-model="changeId" class="select" data-testid="change-select">
            <option v-for="c in changes" :key="c.id" :value="c.id">
              {{ c.title }}{{ c.effective ? `, ${fmtDate(c.effective)}` : "" }} ({{ c.repos }} {{ c.repos === 1 ? "repository" : "repositories" }})
            </option>
          </select>
        </label>
        <p v-else-if="!loading" class="t2 ink-faint">No change touches a repository you can see.</p>
        <p v-if="blastFailed" class="notice bad" role="alert">{{ blastFailed }}</p>
        <div v-if="blast" class="blast" data-testid="blast">
          <p class="t2">
            <strong>{{ blast.title }}</strong>
            <span v-if="blast.effective">, {{ fmtDate(blast.effective) }}</span>:
            <span data-testid="blast-count">{{ blast.repos }} {{ blast.repos === 1 ? "repository" : "repositories" }}</span>.
          </p>
          <ul class="blast-list">
            <li v-for="g in blastByRepo" :key="g.repo">
              <span class="mono">{{ g.repo }}</span>
              <ul>
                <li v-for="f in g.list" :key="f.finding.contract" class="t2 ink-soft">
                  {{ what(f) }}<span v-if="f.finding.status !== 'open'" class="state">{{ f.finding.status === "snoozed" ? "snoozed" : f.finding.status === "not_in_prod" ? "not in production" : f.finding.status }}</span>
                  <span v-if="where(f)" class="mono"> at {{ where(f) }}</span>
                </li>
              </ul>
            </li>
          </ul>
        </div>
      </section>

      <AlertSettings :login="login" :api="api" />
    </template>

    <GitLabConnect v-if="provider === 'gitlab'" :api="api" />
  </div>
</template>

<style scoped>
.org-head { align-items: flex-end; gap: var(--s4); flex-wrap: wrap; }
.org-controls { gap: var(--s3); align-items: center; flex-wrap: wrap; }
.blast-list { list-style: none; padding: 0; margin: var(--s3) 0 0; display: grid; gap: var(--s3); }
.blast-list ul { list-style: none; padding: 0 0 0 var(--s4); margin: var(--s1) 0 0; }
.blast-list li { overflow-wrap: anywhere; }
td.mono { overflow-wrap: anywhere; }
</style>
