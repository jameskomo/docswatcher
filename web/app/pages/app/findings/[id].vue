<script setup lang="ts">
import { contractLabel, daysLabel, fmtDate, KIND_LABEL } from "~/utils/format";

const route = useRoute();
const store = useScanStore();
const { change, providerName, provider } = useKnowledge();
const id = computed(() => decodeURIComponent(String(route.params.id)));
const finding = computed(() => store.current.value?.findings.find((f) => f.id === id.value) ?? null);
const rec = computed(() => (finding.value ? change(finding.value.change) : undefined));
const contract = computed(() => store.current.value?.inventory.contracts.find((c) => c.id === finding.value?.contract));
const copied = ref(false);
const copyFailed = ref(false);

useHead({ title: () => (rec.value ? `${rec.value.title} · DocsWatcher` : "Finding · DocsWatcher") });

const prompt = computed(() => {
  const f = finding.value, ch = rec.value;
  if (!f || !ch) return "";
  const c = contractLabel(f.contract);
  const lines = [
    `Fix a deprecated external API contract in this repository.`,
    ``,
    `Provider: ${providerName(c.provider)}`,
    `Contract: ${KIND_LABEL[c.kind] ?? c.kind} ${c.key}`,
    `Change: ${ch.title} (${ch.id})`,
    `Severity: ${ch.severity}${f.effective ? `, effective ${f.effective} (${daysLabel(f.daysRemaining)})` : ""}`,
    ``,
    `Summary: ${ch.summary}`,
    ``,
    `Affected locations:`,
    ...f.evidence.map((e) => `- ${e.path}:${e.line}:${e.column}  ${e.snippet}`),
    ``,
    `Migration:`,
    `- Replacement: ${ch.migration?.replacement ?? "none published"}`,
    `- Guide: ${ch.migration?.guide ?? "n/a"}`,
    `- Effort: ${ch.migration?.effort ?? "unknown"}`,
    ch.migration?.notes ? `- Notes: ${ch.migration.notes}` : "",
    ``,
    `Steps: read the guide, change every affected location, keep behavior identical, run the tests that cover these files, and open a pull request titled "Migrate ${c.key} (${ch.title})" that links back to this finding.`,
  ];
  return lines.filter((l) => l !== undefined).join("\n");
});

async function copy() {
  copyFailed.value = false;
  try {
    await navigator.clipboard.writeText(prompt.value);
    copied.value = true;
    setTimeout(() => (copied.value = false), 2000);
  } catch {
    // Clipboard is unavailable in some contexts. The prompt is on the page, so say so.
    copyFailed.value = true;
  }
}
const status = computed(() => (finding.value ? store.effectiveStatus(finding.value) : "open"));
</script>

<template>
  <div class="stack" style="gap: var(--s5)">
    <NuxtLink to="/app" class="small">← back to dashboard</NuxtLink>

    <div v-if="!finding" class="empty">
      <h3>This finding is not in the current scan</h3>
      <p class="small">Findings live in the scan held by this browser. Run a scan to see it again.</p>
      <NuxtLink class="btn primary" to="/">Scan a repository</NuxtLink>
    </div>

    <template v-else>
      <section class="hero">
        <div class="row" style="gap: var(--s2)">
          <SeverityChip :severity="finding.severity" />
          <span class="chip neutral" v-if="status !== 'open'">{{ status === "snoozed" ? "snoozed" : "not in production" }}</span>
        </div>
        <h1>{{ rec?.title }}</h1>
        <p class="lede">{{ rec?.summary }}</p>
      </section>

      <!-- The primary action sits directly under the summary, where a reader
           who has decided to act will look for it. -->
      <section class="block">
        <div class="row">
          <button id="fix-pr" class="btn primary" @click="copy">{{ copied ? "Copied" : "Copy fix prompt for a coding agent" }}</button>
          <button class="btn" @click="status === 'snoozed' ? store.unsnooze(finding.id) : store.snooze(finding.id, 30)">
            {{ status === "snoozed" ? "Unsnooze" : "Snooze 30 days" }}
          </button>
          <button class="btn" @click="store.toggleNotInProd(finding.id)">
            {{ status === "not_in_prod" ? "Mark used in production" : "Not used in production" }}
          </button>
        </div>
        <p class="small muted">
          Snooze and production flags are kept in this browser only. With the GitHub App installed, the
          same actions are issue labels and the fix runs as a pull request in your own CI, on your own key.
        </p>
        <p v-if="copyFailed" class="notice error" role="alert">
          The clipboard is not available here. Select the prompt at the bottom of this page and copy it manually.
        </p>
      </section>

      <section class="block">
        <div class="block-head"><h2>What and where</h2></div>
        <dl class="kv">
          <dt>Provider</dt>
          <dd>
            {{ providerName(contractLabel(finding.contract).provider) }}
            <a v-if="provider(contractLabel(finding.contract).provider)?.info.changelog" :href="provider(contractLabel(finding.contract).provider)!.info.changelog" target="_blank" rel="noopener" class="small">changelog ↗</a>
          </dd>
          <dt>Contract</dt><dd class="mono">{{ finding.contract }}</dd>
          <dt>Effective</dt><dd>{{ fmtDate(finding.effective) }} <span class="muted num">({{ daysLabel(finding.daysRemaining) }})</span></dd>
          <dt>Announced</dt><dd>{{ fmtDate(rec?.announced) }}</dd>
          <dt>SDK</dt>
          <dd v-if="contract?.context?.sdk" class="mono">{{ contract.context.sdk.package }} {{ contract.context.sdk.version ?? "" }}</dd>
          <dd v-else class="muted">no SDK package detected</dd>
          <dt>Sources</dt>
          <dd>
            <span v-for="s in rec?.sources" :key="s.url ?? s.kind" class="small">
              <a v-if="s.url" :href="s.url" target="_blank" rel="noopener">{{ s.kind }} ↗</a><span v-else>{{ s.kind }}</span>
              observed {{ s.observed }}&nbsp;
            </span>
          </dd>
        </dl>
      </section>

      <section class="block">
        <div class="block-head">
          <h2>Evidence</h2>
          <span class="aside">{{ finding.evidence.length }} location{{ finding.evidence.length === 1 ? "" : "s" }}</span>
        </div>
        <div class="table-wrap">
          <table>
            <thead><tr><th>File</th><th>Line</th><th>Snippet</th><th>Detector</th></tr></thead>
            <tbody>
              <tr v-for="e in finding.evidence" :key="e.path + e.line + e.column">
                <td class="mono">{{ e.path }}</td>
                <td class="n mono">{{ e.line }}:{{ e.column }}</td>
                <td class="mono">{{ e.snippet }}</td>
                <td class="muted">{{ e.detector }} · {{ e.layer }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="block" v-if="rec?.migration">
        <div class="block-head"><h2>Migration</h2></div>
        <dl class="kv">
          <dt>Replacement</dt><dd class="mono">{{ rec.migration.replacement ?? "none published" }}</dd>
          <dt>Effort</dt><dd>{{ rec.migration.effort }}</dd>
          <dt>Guide</dt><dd><a v-if="rec.migration.guide" :href="rec.migration.guide" target="_blank" rel="noopener">{{ rec.migration.guide }}</a><span v-else class="muted">none published</span></dd>
          <dt>Notes</dt><dd>{{ rec.migration.notes }}</dd>
        </dl>
      </section>

      <section class="block">
        <div class="block-head">
          <h2>Fix prompt</h2>
          <span class="aside">the exact context handed to a coding agent</span>
        </div>
        <pre>{{ prompt }}</pre>
      </section>
    </template>
  </div>
</template>
