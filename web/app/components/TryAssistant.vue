<script setup lang="ts">
// Try DocsWatcher with your own assistant: the same prompt, the same model, with and without the
// check_api tool. The key goes from this browser to the provider and nowhere else.
import { ASSISTANTS, defaultModel, type Answer, type Assistant, type AssistantId } from "~/utils/assistants";
import { checkApi, type Verdict } from "~~/engine/checkApi";

const { knowledge } = useKnowledge();

const examples = [
  { label: "A gpt-4-turbo script", prompt: "Write a minimal Python script that summarises a paragraph with OpenAI's gpt-4-turbo model." },
  { label: "Gemini 2.0 Flash captions", prompt: "Add image captions to my Node.js app with Gemini 2.0 Flash." },
  { label: "Review a dall-e-2 call", prompt: "Is this still fine to ship? openai.images.generate({ model: \"dall-e-2\", prompt })" },
];

const assistantId = ref<AssistantId>("anthropic");
const assistant = computed<Assistant>(() => ASSISTANTS.find((a) => a.id === assistantId.value)!);
const key = ref("");
const remember = ref(false);
const models = ref<string[]>([]);
const model = ref("");
const modelsState = ref<"idle" | "loading" | "ready" | "error">("idle");
const modelsError = ref("");
const prompt = ref(examples[0].prompt);
const compare = ref(true);

type Side = { state: "idle" | "running" | "done" | "error"; answer?: Answer; error?: string };
const withDw = ref<Side>({ state: "idle" });
const without = ref<Side>({ state: "idle" });
const sides = computed(() => [
  ...(compare.value ? [{ title: "Without DocsWatcher", testid: "try-without", tool: false, side: without.value }] : []),
  { title: "With DocsWatcher", testid: "try-with", tool: true, side: withDw.value },
]);
const running = computed(() => withDw.value.state === "running" || without.value.state === "running");
let controller: AbortController | null = null;

// Remembering is opt-in and per provider. Storage can be blocked; the page works without it.
const storageKey = (id: AssistantId) => `docswatcher.try.${id}.key`;
function stored(id: AssistantId): string {
  try { return localStorage.getItem(storageKey(id)) ?? ""; } catch { return ""; }
}
function store(id: AssistantId, value: string | null) {
  try { value ? localStorage.setItem(storageKey(id), value) : localStorage.removeItem(storageKey(id)); } catch { /* blocked */ }
}

function loadKey() {
  const saved = stored(assistantId.value);
  key.value = saved;
  remember.value = saved !== "";
  models.value = [];
  model.value = "";
  modelsState.value = "idle";
  if (saved) loadModels();
}
onMounted(loadKey);
watch(assistantId, () => { stop(); withDw.value = { state: "idle" }; without.value = { state: "idle" }; loadKey(); });
watch(remember, (on) => store(assistantId.value, on && key.value ? key.value : null));

async function loadModels() {
  const k = key.value.trim();
  if (!k) return;
  if (remember.value) store(assistantId.value, k);
  modelsState.value = "loading";
  modelsError.value = "";
  try {
    const list = await assistant.value.models(k);
    models.value = list;
    model.value = defaultModel(assistant.value, list, knowledge);
    modelsState.value = list.length ? "ready" : "error";
    if (!list.length) modelsError.value = "This key can use no chat models.";
  } catch (e) {
    modelsState.value = "error";
    modelsError.value = (e as Error).message;
  }
}

/** DocsWatcher's own view of each model in the list: going away is marked before anyone picks it. */
function modelLabel(id: string): string {
  const r = checkApi(knowledge, { value: id, kind: "model" }, new Date());
  const m = r.matches[0];
  if (r.verdict === "RETIRED") return `${id} (retired ${m.effective})`;
  if (r.verdict === "RETIRING") return `${id} (shuts down ${m.effective})`;
  return id;
}

async function runSide(side: Ref<Side>, withDocsWatcher: boolean, signal: AbortSignal) {
  side.value = { state: "running" };
  try {
    const answer = await assistant.value.run({
      key: key.value.trim(), model: model.value, prompt: prompt.value, withDocsWatcher, knowledge, signal,
    });
    side.value = { state: "done", answer };
  } catch (e) {
    if ((e as Error).name === "AbortError") { side.value = { state: "idle" }; return; }
    side.value = { state: "error", error: (e as Error).message };
  }
}

async function run() {
  stop();
  controller = new AbortController();
  const { signal } = controller;
  without.value = { state: "idle" };
  await Promise.all([
    runSide(withDw, true, signal),
    compare.value ? runSide(without, false, signal) : Promise.resolve(),
  ]);
}

function stop() {
  controller?.abort();
  controller = null;
}
onBeforeUnmount(stop);

/** Splits an answer at markdown code fences: prose and code alternate. Text only, never HTML. */
function blocks(text: string): { code: boolean; text: string }[] {
  return text.split(/^```[^\n]*\n?/m)
    .map((t, i) => ({ code: i % 2 === 1, text: i % 2 === 1 ? t.replace(/\n$/, "") : t.trim() }))
    .filter((b) => b.text !== "");
}

const CHIP: Record<Verdict, { severity: "breaking" | "warning" | "info" | "healthy"; label: string }> = {
  RETIRED: { severity: "breaking", label: "Retired" },
  RETIRING: { severity: "warning", label: "Retiring" },
  CHANGED: { severity: "info", label: "Changed" },
  NO_KNOWN_DEPRECATION: { severity: "healthy", label: "No known deprecation" },
};
</script>

<template>
  <section class="try" data-testid="try-assistant">
    <div class="tabs" role="tablist" aria-label="Your AI provider">
      <button
        v-for="a in ASSISTANTS" :key="a.id" role="tab" type="button"
        :aria-selected="assistantId === a.id" :data-testid="`try-provider-${a.id}`" @click="assistantId = a.id"
      >{{ a.name }}</button>
    </div>

    <form class="form" @submit.prevent="run">
      <div class="pair">
        <label>
          <span>Your {{ assistant.name.split(" ")[0] }} API key</span>
          <input
            v-model="key" class="input" type="password" :placeholder="assistant.keyHint"
            autocomplete="off" spellcheck="false" data-testid="try-key" @change="loadModels"
          />
        </label>
        <label>
          <span>Model</span>
          <select v-model="model" class="select" :disabled="modelsState !== 'ready'" data-testid="try-model">
            <option v-if="modelsState !== 'ready'" value="">
              {{ modelsState === "loading" ? "Loading models…" : "Enter your key first" }}
            </option>
            <option v-for="m in models" :key="m" :value="m">{{ modelLabel(m) }}</option>
          </select>
        </label>
      </div>
      <p class="t1 ink-faint keyline">
        <label class="check"><input v-model="remember" type="checkbox" /> Remember the key on this device</label>
        <span>· <a :href="assistant.keyUrl" target="_blank" rel="noopener">Get a key</a></span>
      </p>
      <p v-if="modelsState === 'error'" class="notice bad" role="alert" data-testid="try-models-error">{{ modelsError }}</p>

      <label>
        <span>Ask it to write something</span>
        <textarea v-model="prompt" class="input" rows="3" maxlength="4000" data-testid="try-prompt"></textarea>
      </label>
      <div class="preset-chips">
        <span class="preset-label">Or try</span>
        <button
          v-for="e in examples" :key="e.label" type="button" class="preset-pill"
          :class="{ active: prompt === e.prompt }" @click="prompt = e.prompt"
        >{{ e.label }}</button>
      </div>

      <div class="actions">
        <button class="btn solid" type="submit" :disabled="!model || !prompt.trim() || running" data-testid="try-run">
          {{ running ? "Asking…" : "Ask" }}
        </button>
        <button v-if="running" class="btn" type="button" @click="stop">Stop</button>
        <label class="check t2"><input v-model="compare" type="checkbox" /> Also ask without DocsWatcher</label>
      </div>
    </form>

    <div v-if="withDw.state !== 'idle' || without.state !== 'idle'" class="results" :class="{ single: !compare }">
      <div v-for="c in sides" :key="c.testid" class="side" :data-testid="c.testid">
        <h3>{{ c.title }}</h3>
        <p v-if="c.side.state === 'running'" class="ink-faint">Thinking…</p>
        <p v-else-if="c.side.state === 'error'" class="notice bad" role="alert">{{ c.side.error }}</p>
        <template v-else-if="c.side.answer">
          <ul v-if="c.side.answer.calls.length" class="calls" data-testid="try-calls">
            <li v-for="(call, i) in c.side.answer.calls" :key="i">
              <code>check_api("{{ call.value }}")</code>
              <SeverityChip v-if="call.result" :severity="CHIP[call.result.verdict].severity" :label="CHIP[call.result.verdict].label" />
              <span v-if="call.result?.matches[0]?.replacement" class="t1 ink-soft">use {{ call.result.matches[0].replacement }}</span>
              <span v-if="call.error" class="t1 ink-soft">{{ call.error }}</span>
            </li>
          </ul>
          <p v-else-if="c.tool" class="t1 ink-faint">It did not need to check anything.</p>
          <!-- Text interpolation only: a model's output is never rendered as HTML here. -->
          <div class="answer">
            <template v-for="(b, i) in blocks(c.side.answer.text)" :key="i">
              <pre v-if="b.code"><code>{{ b.text }}</code></pre>
              <p v-else>{{ b.text }}</p>
            </template>
          </div>
        </template>
      </div>
    </div>

    <p class="t1 ink-faint privacy">
      Your key goes from this browser straight to {{ assistant.name.split(" ")[0] }}. It never reaches
      DocsWatcher, and the check runs here in the page. Each question is billed to your key like any
      other request; comparing asks twice.
    </p>
  </section>
</template>

<style scoped>
.try { border: 1px solid var(--hair); border-radius: var(--radius-sm); padding: var(--s4); margin-top: var(--s3); }
.try .tabs { margin-bottom: var(--s3); flex-wrap: wrap; }
.form { display: grid; gap: var(--s3); }
.form label { display: grid; gap: 6px; min-width: 0; }
.form label > span { font-size: var(--t2); font-weight: 600; color: var(--ink-max); }
.form .input, .form .select { width: 100%; min-width: 0; }
.form textarea { resize: vertical; font: inherit; }
.pair { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 240px), 1fr)); gap: var(--s3); }
.keyline { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; margin-top: calc(-1 * var(--s2)); }
.check { display: inline-flex !important; grid-auto-flow: column; align-items: center; gap: 6px; }
.preset-chips { margin-bottom: 0; }
.actions { display: flex; flex-wrap: wrap; gap: var(--s3); align-items: center; }
.results { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--s3); margin-top: var(--s4); }
.results.single { grid-template-columns: minmax(0, 1fr); }
.side { border: 1px solid var(--hair); border-radius: var(--radius-sm); padding: var(--s3); min-width: 0; }
.side h3 { margin: 0 0 var(--s2); font-size: var(--t2); text-transform: uppercase; letter-spacing: 0.04em; color: var(--ink-soft); }
.calls { list-style: none; padding: 0; margin: 0 0 var(--s3); display: grid; gap: 6px; }
.calls li { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.calls code { overflow-wrap: anywhere; }
.answer { font-size: var(--t2); line-height: 1.55; }
.answer p { white-space: pre-wrap; overflow-wrap: anywhere; margin: 0; }
.answer > * + * { margin-top: var(--s2); }
.answer pre { margin: 0; padding: var(--s2) var(--s3); overflow-x: auto; border: 1px solid var(--hair); border-radius: var(--radius-sm); font-size: var(--t1); }
.privacy { margin-top: var(--s3); }
@media (max-width: 640px) {
  .results { grid-template-columns: minmax(0, 1fr); }
}
</style>
