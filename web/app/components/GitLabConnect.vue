<script setup lang="ts">
import { ApiError, type GitLabConnection, type OrgApi } from "~/utils/orgApi";
import { fmtDate } from "~/utils/format";

/**
 * Connecting a GitLab group or project: a maintainer's access token in, a webhook to add out.
 * docs/adr/0012-gitlab.md.
 *
 * The token is sent once to the app on this site and not kept by the page: the field is cleared
 * whatever the answer. The webhook's secret token is shown once, the only time the app has it.
 */
const props = defineProps<{ api: OrgApi }>();
const emit = defineEmits<{ connected: [c: GitLabConnection] }>();

const connections = ref<GitLabConnection[]>([]);
const namespace = ref("");
const token = ref("");
const busy = ref(false);
const failed = ref("");
const created = ref<GitLabConnection | null>(null);
const updated = ref<GitLabConnection | null>(null);

async function load() {
  try {
    connections.value = await props.api.gitlabConnections();
  } catch {
    connections.value = [];
  }
}

async function connect() {
  busy.value = true;
  failed.value = "";
  created.value = null;
  updated.value = null;
  try {
    const c = await props.api.connectGitLab(namespace.value.trim(), token.value);
    if (c.webhookToken) created.value = c;
    else updated.value = c;
    namespace.value = "";
    emit("connected", c);
    await load();
  } catch (e) {
    failed.value = e instanceof ApiError && e.status === 401 ? "Your session has ended. Sign in again to carry on."
      : e instanceof Error ? e.message : String(e);
  } finally {
    token.value = "";
    busy.value = false;
  }
}

onMounted(load);
</script>

<template>
  <section class="section" id="gitlab-connect" data-testid="gitlab-connect">
    <div class="section-head">
      <h2>Connect a GitLab group</h2>
      <p>
        DocsWatcher scans every project in the group on each push to its default branch, opens an issue per
        finding and sets a commit status. It works with a group or project access token you create for it.
      </p>
    </div>

    <div v-if="connections.length" class="table-wrap" style="margin-bottom: var(--s4)">
      <table>
        <thead><tr><th>Connected</th><th>Projects</th><th>Token expires</th></tr></thead>
        <tbody>
          <tr v-for="c in connections" :key="c.id" data-testid="gitlab-connection">
            <td class="mono">{{ c.namespace }}</td>
            <td class="num">{{ c.projects }}</td>
            <td class="mono t2">{{ c.tokenExpiresAt ? fmtDate(c.tokenExpiresAt) : "never" }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <ol class="t2 ink-soft steps">
      <li>In the group, open Settings, Access tokens, and add a token with the Maintainer role and the <span class="mono">api</span> scope.</li>
      <li>Enter the group's path and the token here. The token is stored encrypted and used only for this group.</li>
      <li>Add the webhook DocsWatcher gives you, with Push events and Issues events.</li>
    </ol>

    <form class="form gitlab-form" @submit.prevent="connect" data-testid="gitlab-connect-form">
      <label>
        <span class="t2 ink-soft">Group or project path</span>
        <input class="input mono" v-model="namespace" required maxlength="255" placeholder="acme or acme/platform" autocomplete="off" name="namespace" />
      </label>
      <label>
        <span class="t2 ink-soft">Access token</span>
        <input class="input mono" v-model="token" required maxlength="512" type="password" autocomplete="off" name="gitlab-token" />
      </label>
      <button type="submit" class="btn solid" :disabled="busy">{{ busy ? "Connecting…" : "Connect" }}</button>
    </form>

    <p v-if="failed" class="notice bad" role="alert" data-testid="gitlab-connect-failed">{{ failed }}</p>

    <div v-if="created" class="notice webhook" role="status" data-testid="gitlab-connected">
      <p>
        <strong>{{ created.namespace }}</strong> is connected, with {{ created.projects }}
        {{ created.projects === 1 ? "project" : "projects" }} queued for a first scan.
        Now add a webhook to the {{ created.kind }} (Settings, Webhooks), with Push events and Issues events:
      </p>
      <dl>
        <dt>URL</dt>
        <dd class="mono" data-testid="gitlab-webhook-url">{{ created.webhookUrl }}</dd>
        <dt>Secret token</dt>
        <dd class="mono" data-testid="gitlab-webhook-token">{{ created.webhookToken }}</dd>
      </dl>
      <p class="t2">
        This secret token is shown once and cannot be shown again. On GitLab Free, where groups have no
        webhooks, add it to each project. Sign in again to see the group on this dashboard.
      </p>
    </div>
    <p v-if="updated" class="notice" role="status" data-testid="gitlab-reconnected">
      {{ updated.namespace }} was already connected. Its token is replaced; the webhook stays as it was.
    </p>
  </section>
</template>

<style scoped>
.steps { margin: 0 0 var(--s4); padding-left: var(--s5); display: grid; gap: var(--s1); }
.gitlab-form { display: flex; flex-wrap: wrap; gap: var(--s3); align-items: flex-end; }
.gitlab-form label { display: grid; gap: var(--s1); flex: 1 1 14rem; min-width: 0; }
.gitlab-form .input { width: 100%; }
.webhook dl { display: grid; grid-template-columns: max-content 1fr; gap: var(--s1) var(--s3); margin: var(--s3) 0; }
.webhook dd { margin: 0; overflow-wrap: anywhere; }
</style>
