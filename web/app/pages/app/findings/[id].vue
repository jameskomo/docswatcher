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

useHead({ title: () => (rec.value ? `${rec.value.title}, DocsWatcher` : "Finding, DocsWatcher") });

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
    copyFailed.value = true;
  }
}
const status = computed(() => (finding.value ? store.effectiveStatus(finding.value) : "open"));
</script>

<template>
  <div class="stack" style="gap: var(--s5); padding-top: var(--s3)">
    <NuxtLink to="/app" class="t2" style="display: inline-flex; align-items: center; gap: 6px; font-weight: 600">
      <span>←</span>
      <span>Back to dashboard</span>
    </NuxtLink>

    <div v-if="!finding" class="empty">
      <h3>This finding is not in the current scan</h3>
      <p class="t2">Findings live in the scan held by this browser. Run a scan to see it again.</p>
      <NuxtLink class="btn solid" to="/" style="margin-top: var(--s3)">Scan a repository</NuxtLink>
    </div>

    <template v-else>
      <section class="hero" style="background: var(--bg-surface); border: 1px solid var(--hair-strong); padding: var(--s5); border-radius: var(--radius-xl); box-shadow: var(--shadow-card)">
        <div class="row between" style="align-items: center">
          <div class="row" style="gap: var(--s2)">
            <SeverityChip :severity="finding.severity" />
            <span class="sev neutral" v-if="status !== 'open'">{{ status === "snoozed" ? "snoozed" : "not in production" }}</span>
          </div>
          <span class="mono t2" v-if="finding.effective" :style="{ color: (finding.daysRemaining ?? 0) < 0 ? 'var(--overdue)' : 'var(--soon)', fontWeight: 700 }">
            {{ (finding.daysRemaining ?? 0) < 0 ? `${-(finding.daysRemaining ?? 0)} days overdue` : `Due in ${finding.daysRemaining} days` }}
          </span>
        </div>
        <h1 style="margin-top: var(--s3)">{{ rec?.title }}</h1>
        <p class="lede" style="margin-top: var(--s2)">{{ rec?.summary }}</p>

        <!-- Primary Action Bar -->
        <div style="margin-top: var(--s5); border-top: 1px solid var(--hair); padding-top: var(--s4)">
          <div class="row" style="gap: var(--s3)">
            <button id="fix-pr" class="btn solid" @click="copy">
              <span>⚡</span>
              <span>{{ copied ? "Copied" : "Copy fix prompt for a coding agent" }}</span>
            </button>
            <button class="btn" @click="status === 'snoozed' ? store.unsnooze(finding.id) : store.snooze(finding.id, 30)">
              {{ status === "snoozed" ? "Unsnooze" : "Snooze 30 days" }}
            </button>
            <button class="btn" @click="store.toggleNotInProd(finding.id)">
              {{ status === "not_in_prod" ? "Mark used in production" : "Not used in production" }}
            </button>
          </div>
          <p class="t2 ink-faint" style="margin-top: var(--s3)">
            Snooze and production flags are kept in this browser only. With the GitHub App installed, the
            same actions are issue labels and the fix runs as a pull request in your own CI, on your own key.
          </p>
          <p v-if="copyFailed" class="notice bad" role="alert" style="margin-top: var(--s3)">
            The clipboard is not available here. Select the prompt at the bottom of this page and copy it manually.
          </p>
        </div>
      </section>

      <section class="section">
        <div class="section-head"><h2>What and where</h2></div>
        <dl class="kv">
          <dt>Provider</dt>
          <dd>
            <strong>{{ providerName(contractLabel(finding.contract).provider) }}</strong>
            <a v-if="safeUrl(provider(contractLabel(finding.contract).provider)?.info.changelog)" :href="safeUrl(provider(contractLabel(finding.contract).provider)!.info.changelog)!" target="_blank" rel="noopener" class="t2" style="margin-left: 8px">changelog ↗</a>
          </dd>
          <dt>Contract</dt><dd class="mono" style="color: var(--ink-accent)">{{ finding.contract }}</dd>
          <dt>Effective</dt><dd>{{ fmtDate(finding.effective) }} <span class="ink-faint num">({{ daysLabel(finding.daysRemaining) }})</span></dd>
          <dt>Announced</dt><dd>{{ fmtDate(rec?.announced) }}</dd>
          <dt>SDK</dt>
          <dd v-if="contract?.context?.sdk" class="mono">{{ contract.context.sdk.package }} {{ contract.context.sdk.version ?? "" }}</dd>
          <dd v-else class="ink-faint">no SDK package detected</dd>
          <dt>Sources</dt>
          <dd>
            <span v-for="s in rec?.sources" :key="s.url ?? s.kind" class="t2">
              <a v-if="safeUrl(s.url)" :href="safeUrl(s.url)!" target="_blank" rel="noopener">{{ s.kind }} ↗</a><span v-else>{{ s.kind }}</span>
              observed {{ s.observed }}&nbsp;
            </span>
          </dd>
        </dl>
      </section>

      <section class="section">
        <div class="section-head">
          <h2>Evidence</h2>
          <span class="aside">{{ finding.evidence.length }} location{{ finding.evidence.length === 1 ? "" : "s" }}</span>
        </div>
        <div class="table-wrap">
          <table>
            <thead><tr><th>File</th><th>Line</th><th>Snippet</th><th>Detector</th></tr></thead>
            <tbody>
              <tr v-for="e in finding.evidence" :key="e.path + e.line + e.column">
                <td class="mono" style="color: var(--ink-accent)">{{ e.path }}</td>
                <td class="n mono">{{ e.line }}:{{ e.column }}</td>
                <td><code class="code-pill">{{ e.snippet }}</code></td>
                <td class="ink-faint">{{ e.detector }} ({{ e.layer }} layer)</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="section" v-if="rec?.migration">
        <div class="section-head"><h2>Migration</h2></div>
        <dl class="kv">
          <dt>Replacement</dt><dd class="mono" style="color: var(--ok)">{{ rec.migration.replacement ?? "none published" }}</dd>
          <dt>Effort</dt><dd><span class="state">{{ rec.migration.effort }}</span></dd>
          <dt>Guide</dt><dd><a v-if="safeUrl(rec.migration.guide)" :href="safeUrl(rec.migration.guide)!" target="_blank" rel="noopener">{{ rec.migration.guide }}</a><span v-else class="ink-faint">none published</span></dd>
          <dt>Notes</dt><dd>{{ rec.migration.notes }}</dd>
        </dl>
      </section>

      <section class="section">
        <div class="section-head">
          <h2>Fix prompt</h2>
          <span class="aside">the exact context handed to a coding agent</span>
        </div>
        <div style="background: #05070b; border: 1px solid var(--hair-strong); border-radius: var(--radius-lg); overflow: hidden; box-shadow: var(--shadow-card)">
          <div class="row between" style="background: rgba(255,255,255,0.03); padding: 8px 16px; border-bottom: 1px solid var(--hair)">
            <div class="row" style="gap: 6px">
              <span style="width: 10px; height: 10px; border-radius: 50%; background: #ef4444; display: inline-block"></span>
              <span style="width: 10px; height: 10px; border-radius: 50%; background: #f59e0b; display: inline-block"></span>
              <span style="width: 10px; height: 10px; border-radius: 50%; background: #10b981; display: inline-block"></span>
              <span class="mono t1 ink-faint" style="margin-left: 8px">agent-fix-prompt.md</span>
            </div>
            <button class="btn quiet t1" @click="copy" style="padding: 2px 8px">
              {{ copied ? "Copied" : "Copy" }}
            </button>
          </div>
          <pre style="margin: 0; border: none; border-radius: 0; background: transparent">{{ prompt }}</pre>
        </div>
      </section>
    </template>
  </div>
</template>
