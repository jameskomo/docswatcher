<script setup lang="ts">
import {
  ApiError, parseThresholds, splitEmails,
  type AlertSettings, type AlertUpdate, type OrgApi,
} from "~/utils/orgApi";

/**
 * Warned before the date: where an organisation's alerts go, and how early.
 * docs/adr/0010-alerts-before-the-date.md.
 *
 * Anyone who can see the organisation sees the settings; people with write access to one of its
 * repositories can change them. The Slack webhook is a credential, so the server never sends it
 * back: the field stays empty, and a blank field keeps the one already saved.
 */
const props = defineProps<{ login: string; api: OrgApi }>();

const settings = ref<AlertSettings | null>(null);
const failed = ref("");
const form = reactive({ enabled: true, emails: "", slack: "", removeSlack: false, thresholds: "30, 7" });
const state = ref<"idle" | "saving" | "testing">("idle");
const outcome = ref<{ ok: boolean; text: string } | null>(null);

function fill(s: AlertSettings) {
  settings.value = s;
  form.enabled = s.enabled;
  form.emails = s.emails.join("\n");
  form.slack = "";
  form.removeSlack = false;
  form.thresholds = s.thresholds.join(", ");
}

function why(e: unknown, fallback: string): string {
  if (e instanceof ApiError) {
    if (e.detail) return e.detail;
    if (e.status === 403) return "You need write access to one of this organisation's repositories to change its alerts.";
    if (e.status === 401) return "Your session has ended. Sign in again to carry on.";
    if (e.status === 429) return "Too many tests. Try again in an hour.";
  }
  return fallback;
}

async function load() {
  failed.value = "";
  outcome.value = null;
  settings.value = null;
  try {
    fill(await props.api.alerts(props.login));
  } catch (e) {
    failed.value = e instanceof ApiError && e.status === 404 ? "" : why(e, "Alert settings could not be loaded.");
  }
}

async function save() {
  state.value = "saving";
  outcome.value = null;
  const update: AlertUpdate = { enabled: form.enabled, emails: splitEmails(form.emails), thresholds: parseThresholds(form.thresholds) };
  if (form.removeSlack) update.slackWebhook = "";
  else if (form.slack.trim()) update.slackWebhook = form.slack.trim();
  try {
    fill(await props.api.saveAlerts(props.login, update));
    outcome.value = { ok: true, text: "Saved." };
  } catch (e) {
    outcome.value = { ok: false, text: why(e, "That did not save.") };
  } finally {
    state.value = "idle";
  }
}

async function sendTest() {
  state.value = "testing";
  outcome.value = null;
  try {
    const r = await props.api.testAlerts(props.login);
    const parts = [
      r.emails ? `${r.emails} ${r.emails === 1 ? "email" : "emails"}` : "",
      r.slackMessages ? "a Slack message" : "",
    ].filter(Boolean);
    outcome.value = r.failures
      ? { ok: false, text: `Sent ${parts.join(" and ") || "nothing"}; ${r.failures} could not be delivered. Check the addresses and the webhook.` }
      : { ok: true, text: `Sent ${parts.join(" and ")}.` };
  } catch (e) {
    outcome.value = { ok: false, text: why(e, "The test did not go.") };
  } finally {
    state.value = "idle";
  }
}

const editable = computed(() => !!settings.value?.canEdit);
const channels = computed(() => (settings.value?.emails.length ?? 0) > 0 || !!settings.value?.slack.configured);
/** The test goes to what is saved, not what is typed, so say so rather than leave a grey button unexplained. */
const unsaved = computed(() => !!settings.value && (
  splitEmails(form.emails).join() !== settings.value.emails.join() || !!form.slack.trim() || form.removeSlack));
const testHint = computed(() => {
  if (!channels.value) return unsaved.value
    ? "Save alerts first: the test goes to the saved addresses and webhook."
    : "Add an email address or a Slack webhook and save, then send a test.";
  return unsaved.value ? "The test goes to the saved addresses and webhook, not the changes above until you save." : "";
});

watch(() => props.login, load);
onMounted(load);
</script>

<template>
  <section class="section" id="org-alerts" data-testid="org-alerts">
    <div class="section-head">
      <h2>Warned before the date</h2>
      <p>An email or a Slack message when a repository here calls something due to shut down, once for each number of days before the date.</p>
    </div>

    <p v-if="failed" class="notice bad" role="alert">{{ failed }}</p>

    <template v-else-if="settings">
      <p v-if="!settings.emailAvailable" class="t2 ink-faint" data-testid="alerts-no-email">
        This deployment has no email service set up, so only Slack alerts are sent.
      </p>

      <form class="alerts-form" @submit.prevent="save" data-testid="alerts-form">
        <label class="check">
          <input type="checkbox" v-model="form.enabled" :disabled="!editable" data-testid="alerts-enabled" />
          <span>Send alerts for {{ login }}</span>
        </label>

        <label>
          <span>Email addresses <em>one per line, up to ten</em></span>
          <textarea class="input" rows="3" v-model="form.emails" :disabled="!editable" maxlength="2600"
            autocomplete="off" spellcheck="false" data-testid="alerts-emails"></textarea>
        </label>

        <label>
          <span>Slack incoming webhook
            <em v-if="settings.slack.configured" data-testid="alerts-slack-set">one is saved, ending {{ settings.slack.hint }}; leave empty to keep it</em>
            <em v-else>starts with https://hooks.slack.com/services/</em>
          </span>
          <input class="input" type="url" v-model="form.slack" :disabled="!editable || form.removeSlack" maxlength="200"
            autocomplete="off" spellcheck="false" placeholder="https://hooks.slack.com/services/..." data-testid="alerts-slack" />
        </label>
        <label v-if="settings.slack.configured && editable" class="check">
          <input type="checkbox" v-model="form.removeSlack" data-testid="alerts-slack-remove" />
          <span>Stop posting to Slack</span>
        </label>

        <label>
          <span>Days before the date <em>up to four, for example 30, 7</em></span>
          <input class="input narrow" v-model="form.thresholds" :disabled="!editable" maxlength="40" inputmode="numeric" data-testid="alerts-thresholds" />
        </label>

        <p v-if="outcome" class="t2" :class="outcome.ok ? 'ok' : 'notice bad'" role="status" data-testid="alerts-outcome">{{ outcome.text }}</p>

        <div v-if="editable" class="row actions">
          <button class="btn solid" type="submit" :disabled="state !== 'idle'" data-testid="alerts-save">
            {{ state === "saving" ? "Saving" : "Save alerts" }}
          </button>
          <button class="btn" type="button" :disabled="state !== 'idle' || !channels" @click="sendTest" data-testid="alerts-test">
            {{ state === "testing" ? "Sending" : "Send a test" }}
          </button>
        </div>
        <p v-if="editable && testHint" class="t1 ink-faint" data-testid="alerts-test-hint">{{ testHint }}</p>
        <p v-else class="t2 ink-faint" data-testid="alerts-readonly">
          People with write access to one of {{ login }}'s repositories can change these.
        </p>
        <p v-if="settings.updatedBy" class="t1 ink-faint">Last changed by {{ settings.updatedBy }}.</p>
      </form>
    </template>
  </section>
</template>

<style scoped>
.alerts-form { display: grid; gap: var(--s3); max-width: 560px; }
.alerts-form label { display: grid; gap: 6px; }
.alerts-form label > span { font-size: var(--t2); font-weight: 600; color: var(--ink-max); }
.alerts-form em { font-style: normal; font-weight: 400; color: var(--ink-faint); margin-left: 6px; overflow-wrap: anywhere; }
.alerts-form .input { width: 100%; min-width: 0; }
.alerts-form .narrow { max-width: 200px; }
.alerts-form textarea { resize: vertical; font: inherit; }
.alerts-form .check { display: flex; align-items: center; gap: 8px; }
.alerts-form .check span { font-weight: 400; color: var(--ink); }
.actions { gap: var(--s2); flex-wrap: wrap; }
.ok { color: var(--ink); }
</style>
