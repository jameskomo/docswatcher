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
  try { await navigator.clipboard.writeText(prompt.value); copied.value = true; setTimeout(() => (copied.value = false), 2000); }
  catch { /* clipboard unavailable: the prompt is visible below */ }
}
</script>

<template>
  <div class="stack" style="gap: 24px">
    <NuxtLink to="/app" class="small">← dashboard</NuxtLink>
    <div v-if="!finding" class="notice">This finding is not in the current scan. <NuxtLink to="/">Run a scan</NuxtLink> first.</div>
    <template v-else>
      <section class="hero">
        <div class="row" style="gap: 8px"><SeverityChip :severity="finding.severity" /><span class="chip neutral" v-if="store.effectiveStatus(finding) !== 'open'">{{ store.effectiveStatus(finding) }}</span></div>
        <h1>{{ rec?.title }}</h1>
        <p>{{ rec?.summary }}</p>
      </section>

      <section class="panel panel-pad">
        <dl class="kv">
          <dt>Provider</dt><dd>{{ providerName(contractLabel(finding.contract).provider) }} <a v-if="provider(contractLabel(finding.contract).provider)?.info.changelog" :href="provider(contractLabel(finding.contract).provider)!.info.changelog" target="_blank" rel="noopener" class="small">changelog ↗</a></dd>
          <dt>Contract</dt><dd class="mono">{{ finding.contract }}</dd>
          <dt>Effective</dt><dd>{{ fmtDate(finding.effective) }} <span class="muted num">({{ daysLabel(finding.daysRemaining) }})</span></dd>
          <dt>Announced</dt><dd>{{ fmtDate(rec?.announced) }}</dd>
          <dt>SDK</dt><dd v-if="contract?.context?.sdk" class="mono">{{ contract.context.sdk.package }} {{ contract.context.sdk.version ?? "" }}</dd><dd v-else class="muted">no SDK package detected</dd>
          <dt>Sources</dt><dd><span v-for="s in rec?.sources" :key="s.url ?? s.kind" class="small"><a v-if="s.url" :href="s.url" target="_blank" rel="noopener">{{ s.kind }} ↗</a><span v-else>{{ s.kind }}</span> observed {{ s.observed }} </span></dd>
        </dl>
      </section>

      <section class="block">
        <h2>Evidence</h2>
        <div class="table-wrap">
          <table>
            <thead><tr><th>File</th><th>Line</th><th>Snippet</th><th>Detector</th></tr></thead>
            <tbody>
              <tr v-for="e in finding.evidence" :key="e.path + e.line + e.column">
                <td class="mono">{{ e.path }}</td><td class="num">{{ e.line }}:{{ e.column }}</td><td class="mono">{{ e.snippet }}</td><td class="small muted">{{ e.detector }} · {{ e.layer }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="block" v-if="rec?.migration">
        <h2>Migration</h2>
        <dl class="kv panel panel-pad">
          <dt>Replacement</dt><dd class="mono">{{ rec.migration.replacement ?? "none published" }}</dd>
          <dt>Effort</dt><dd>{{ rec.migration.effort }}</dd>
          <dt>Guide</dt><dd><a v-if="rec.migration.guide" :href="rec.migration.guide" target="_blank" rel="noopener">{{ rec.migration.guide }}</a></dd>
          <dt>Notes</dt><dd>{{ rec.migration.notes }}</dd>
        </dl>
      </section>

      <section class="block">
        <div class="row">
          <button id="fix-pr" class="btn primary" @click="copy">{{ copied ? "Copied" : "Fix PR · copy agent prompt" }}</button>
          <button class="btn" @click="store.effectiveStatus(finding) === 'snoozed' ? store.unsnooze(finding.id) : store.snooze(finding.id, 30)">{{ store.effectiveStatus(finding) === 'snoozed' ? 'Unsnooze' : 'Snooze 30 days' }}</button>
          <button class="btn" @click="store.toggleNotInProd(finding.id)">{{ store.effectiveStatus(finding) === 'not_in_prod' ? 'Mark used in prod' : 'Not used in prod' }}</button>
        </div>
        <p class="small muted">The GitHub App opens this as a pull request through a Claude Code action in your own CI. Here, the same context is ready to paste into any coding agent.</p>
        <pre>{{ prompt }}</pre>
      </section>
    </template>
  </div>
</template>
