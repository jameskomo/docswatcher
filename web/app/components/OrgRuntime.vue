<script setup lang="ts">
import { ApiError, type OrgApi, type RepoSummary } from "~/utils/orgApi";
import {
  ago, endpointLabel, fmtCount, noticeLabel,
  type CreatedToken, type IngestToken, type RuntimeCall, type RuntimeFindingRef, type RuntimeSummary,
} from "~/utils/runtime";
import { contractLabel, fmtDate, KIND_LABEL } from "~/utils/format";

/**
 * What actually runs, per repository: the deprecated calls production made and how often, the
 * findings production has never been seen making, the ingest tokens telemetry is sent with, and
 * how to set it up. docs/13-runtime-observation.md.
 *
 * Everything from the server is rendered as text. A token's secret is shown once, straight after
 * it is created, and kept only in this component until the repository changes.
 */
const props = defineProps<{ api: OrgApi; repos: RepoSummary[]; endpoint: string }>();
const emit = defineEmits<{ "show-finding": [repoId: number]; expired: [] }>();
const { providerName } = useKnowledge();

const repoId = ref<number | null>(null);
const summary = ref<RuntimeSummary | null>(null);
const tokens = ref<IngestToken[]>([]);
const failed = ref("");
const loading = ref(false);
const label = ref("");
const busy = ref(false);
const tokenProblem = ref("");
const created = ref<CreatedToken | null>(null);
const now = ref(Date.now());

const repo = computed(() => props.repos.find((r) => r.id === repoId.value) ?? null);
const reported = computed(() => !!summary.value?.lastReportAt);

/** The repository with the most open findings first: that is where runtime evidence pays. */
function defaultRepo(list: RepoSummary[]): number | null {
  if (!list.length) return null;
  return [...list].sort((a, b) => b.openFindings - a.openFindings || a.fullName.localeCompare(b.fullName))[0]!.id;
}

watch(() => props.repos, (list) => {
  if (!list.some((r) => r.id === repoId.value)) repoId.value = defaultRepo(list);
}, { immediate: true });

watch(repoId, () => {
  created.value = null;
  tokenProblem.value = "";
  label.value = "";
  load();
}, { immediate: true });

function problem(e: unknown, what: string): string {
  if (e instanceof ApiError && e.status === 401) {
    emit("expired");
    return "Your session has ended. Sign in again to carry on.";
  }
  if (e instanceof ApiError && e.status === 404) return `${what} is not available to you.`;
  return `${what} could not be loaded (${e instanceof Error ? e.message : String(e)}).`;
}

async function load() {
  const id = repoId.value;
  summary.value = null;
  tokens.value = [];
  failed.value = "";
  if (id === null) return;
  loading.value = true;
  try {
    const [s, t] = await Promise.all([props.api.runtimeSummary(id), props.api.ingestTokens(id)]);
    if (repoId.value !== id) return;
    summary.value = s;
    tokens.value = t;
    now.value = Date.now();
  } catch (e) {
    if (repoId.value === id) failed.value = problem(e, "Runtime observation for this repository");
  } finally {
    if (repoId.value === id) loading.value = false;
  }
}

function writeProblem(e: unknown, doing: string): string {
  const name = repo.value?.fullName ?? "this repository";
  if (e instanceof ApiError && e.status === 403) return `You need write access to ${name} to ${doing}.`;
  if (e instanceof ApiError && e.status === 409) return `${name} already has the most tokens it can. Revoke one first.`;
  if (e instanceof ApiError && e.status === 401) {
    emit("expired");
    return "Your session has ended. Sign in again to carry on.";
  }
  return `That did not work (${e instanceof Error ? e.message : String(e)}).`;
}

async function createToken() {
  if (repoId.value === null) return;
  busy.value = true;
  tokenProblem.value = "";
  try {
    created.value = await props.api.createIngestToken(repoId.value, label.value.trim());
    label.value = "";
    tokens.value = await props.api.ingestTokens(repoId.value);
  } catch (e) {
    tokenProblem.value = writeProblem(e, "create a token");
  } finally {
    busy.value = false;
  }
}

async function revoke(t: IngestToken) {
  if (repoId.value === null) return;
  busy.value = true;
  tokenProblem.value = "";
  try {
    await props.api.revokeIngestToken(repoId.value, t.id);
    if (created.value?.token.id === t.id) created.value = null;
    tokens.value = await props.api.ingestTokens(repoId.value);
  } catch (e) {
    tokenProblem.value = e instanceof ApiError && e.status === 404
      ? "That token was already revoked."
      : writeProblem(e, "revoke a token");
    tokens.value = await props.api.ingestTokens(repoId.value).catch(() => tokens.value);
  } finally {
    busy.value = false;
  }
}

const provider = (c: RuntimeCall) => (c.provider ? providerName(c.provider) : "unknown provider");
const subject = (f: RuntimeFindingRef) => {
  const c = contractLabel(f.contract);
  return `${providerName(c.provider)} ${KIND_LABEL[c.kind] ?? c.kind} ${c.key}`;
};
const statusNote = (f: RuntimeFindingRef) =>
  f.status === "snoozed" ? "snoozed" : f.status === "not_in_prod" ? "marked not in production" : null;
</script>

<template>
  <div class="org-runtime" data-testid="org-runtime">
    <label v-if="repos.length" class="row t2 ink-soft" style="gap: var(--s2); flex-wrap: wrap">
      <span>Repository</span>
      <select id="runtime-repo" v-model="repoId" class="select" data-testid="runtime-repo">
        <option v-for="r in repos" :key="r.id" :value="r.id">{{ r.fullName }}</option>
      </select>
    </label>
    <p v-else class="t2 ink-faint">No repository to show.</p>

    <p v-if="failed" class="notice bad" role="alert">{{ failed }}</p>
    <p v-else-if="loading && !summary" class="t2 ink-faint">Loading…</p>

    <template v-if="summary && repo">
      <p class="t2 ink-soft" data-testid="runtime-last-report">
        <template v-if="reported">
          Telemetry last arrived {{ ago(summary.lastReportAt, now) }}.
        </template>
        <template v-else>
          No telemetry has arrived for <span class="mono">{{ repo.fullName }}</span> yet, so nothing
          can be said about what production calls. There is nothing to do unless this repository runs
          as a service; if it does, the setup below takes an ingest token and a few lines of Collector
          config.
        </template>
      </p>

      <h3>Deprecated calls production made</h3>
      <div v-if="summary.deprecated.length" class="table-wrap">
        <table data-testid="runtime-deprecated">
          <thead>
            <tr><th>Endpoint</th><th>Provider</th><th>Calls</th><th>Last seen</th><th>What it confirms</th></tr>
          </thead>
          <tbody>
            <tr v-for="c in summary.deprecated" :key="c.host + c.method + c.path" data-testid="runtime-call">
              <td class="mono">{{ endpointLabel(c) }}<span class="t2 ink-faint block">{{ c.host }}</span></td>
              <td>{{ provider(c) }}</td>
              <td class="num">
                {{ fmtCount(c.totalCalls) }}
                <span class="t2 ink-faint block">{{ fmtCount(c.callsPerDay) }} a day over {{ c.daysObserved }} {{ c.daysObserved === 1 ? "day" : "days" }}</span>
              </td>
              <td class="t2">{{ ago(c.lastSeen, now) }}</td>
              <td>
                <span v-for="f in c.findings" :key="f.contract + f.change" class="confirms">
                  <SeverityChip :severity="f.severity" />
                  <span>{{ f.changeTitle }}<span v-if="f.effective" class="ink-faint">, {{ fmtDate(f.effective) }}</span></span>
                  <span v-if="statusNote(f)" class="state">{{ statusNote(f) }}</span>
                  <button type="button" class="linkish" @click="emit('show-finding', repo.id)">Show finding</button>
                </span>
                <span v-if="noticeLabel(c)" class="t2 ink-soft block mono" data-testid="runtime-notice">Provider sent {{ noticeLabel(c) }}</span>
                <span v-if="!c.findings.length" class="t2 ink-faint block">No finding yet: the provider announced this, the knowledge base has no record.</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="t2 ink-faint" data-testid="runtime-deprecated-none">
        {{ reported ? "None seen. Nothing production called is going away, as far as telemetry shows." : "Nothing yet." }}
      </p>
      <p v-if="summary.alsoObserved.length" class="t2 ink-faint">
        {{ summary.alsoObserved.length }} more tracked {{ summary.alsoObserved.length === 1 ? "endpoint was" : "endpoints were" }}
        called, with nothing announced against {{ summary.alsoObserved.length === 1 ? "it" : "them" }}.
      </p>

      <template v-if="reported">
        <h3>In code, not observed in production</h3>
        <ul v-if="summary.notObserved.length" class="unseen" data-testid="runtime-unseen">
          <li v-for="f in summary.notObserved" :key="f.contract + f.change" data-testid="runtime-unseen-item">
            <SeverityChip :severity="f.severity" />
            <span>
              {{ f.changeTitle }}<span v-if="f.effective" class="ink-faint">, {{ fmtDate(f.effective) }}</span>
              <span class="t2 ink-soft block mono">{{ subject(f) }}</span>
            </span>
            <span v-if="statusNote(f)" class="state">{{ statusNote(f) }}</span>
          </li>
        </ul>
        <p v-else class="t2 ink-faint">Every open finding on an endpoint has been seen in production.</p>
        <p class="t2 ink-faint">
          Referenced in the code but never seen in telemetry: check whether the path is still live
          before migrating it. It may be dead code, or run from a service that does not report here.
          <template v-if="summary.notObservable">
            {{ summary.notObservable }} more {{ summary.notObservable === 1 ? "finding is" : "findings are" }}
            on a model or SDK version, which an HTTP span cannot show, so {{ summary.notObservable === 1 ? "it is" : "they are" }} not listed.
          </template>
        </p>
      </template>

      <h3>Ingest tokens</h3>
      <p class="t2 ink-soft" data-testid="runtime-token-explainer">
        An ingest token is the password your OpenTelemetry Collector sends with its data, so
        DocsWatcher knows the data is yours and which repository it belongs to. It can only upload
        call data for <span class="mono">{{ repo.fullName }}</span>: it cannot read this dashboard,
        see findings or change anything. DocsWatcher keeps only a fingerprint of it, so the token is
        shown once, when you create it. Revoke it and the next upload is refused. You need one only
        when you connect a running service.
      </p>
      <div v-if="tokens.length" class="table-wrap">
        <table data-testid="runtime-tokens">
          <thead><tr><th>Token</th><th>Label</th><th>Created</th><th>Last used</th><th></th></tr></thead>
          <tbody>
            <tr v-for="t in tokens" :key="t.id" data-testid="runtime-token">
              <td class="mono">{{ t.prefix }}…</td>
              <td>{{ t.label }}</td>
              <td class="t2">{{ ago(t.createdAt, now) }} by <span class="mono">{{ t.createdBy }}</span></td>
              <td class="t2">{{ t.lastUsedAt ? ago(t.lastUsedAt, now) : "not yet" }}</td>
              <td><button type="button" class="btn quiet" :disabled="busy" @click="revoke(t)">Revoke</button></td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="t2 ink-faint">No token yet.</p>

      <div v-if="created" class="callout created" role="status" data-testid="runtime-created">
        <strong>Copy this token now. It is not shown again.</strong>
        <Snippet :code="created.secret" wrap testid="runtime-secret" />
        <span class="t2">Set it as <span class="mono">DOCSWATCHER_INGEST_TOKEN</span> where your Collector runs.</span>
      </div>

      <form class="row create" @submit.prevent="createToken">
        <input v-model="label" class="input" maxlength="80" placeholder="Label, e.g. production collector" aria-label="Token label" data-testid="runtime-token-label" />
        <button type="submit" class="btn" :disabled="busy" data-testid="runtime-create">Create ingest token</button>
      </form>
      <p v-if="tokenProblem" class="notice bad" role="alert" data-testid="runtime-token-problem">{{ tokenProblem }}</p>

      <details class="setup" :open="!reported" data-testid="runtime-setup-details">
        <summary>How to send telemetry</summary>
        <RuntimeSetup :endpoint="endpoint" :repo="repo.fullName" />
      </details>
    </template>
  </div>
</template>

<style scoped>
.org-runtime { display: grid; gap: var(--s3); min-width: 0; }
.org-runtime > * { min-width: 0; }
.org-runtime h3 { margin: var(--s3) 0 0; }
.block { display: block; }
td.mono { overflow-wrap: anywhere; }
.confirms { display: flex; flex-wrap: wrap; align-items: center; gap: var(--s2); }
.confirms + .confirms { margin-top: var(--s2); }
.unseen { list-style: none; padding: 0; margin: 0; display: grid; gap: var(--s2); }
.unseen li { display: flex; flex-wrap: wrap; align-items: baseline; gap: var(--s2); overflow-wrap: anywhere; }
.created { display: grid; gap: var(--s2); }
.create { gap: var(--s2); flex-wrap: wrap; }
.create .input { flex: 1 1 220px; min-width: 0; }
.setup summary { cursor: pointer; font-weight: 600; }
.setup[open] summary { margin-bottom: var(--s3); }
</style>
