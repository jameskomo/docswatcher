<script setup lang="ts">
const { knowledge } = useKnowledge();
const totalChanges = knowledge.providers.reduce((n, p) => n + p.changes.length, 0);
const providerNames = knowledge.providers.map((p) => p.info.name).join(", ");

const claudeCode = `claude mcp add docswatcher -- docswatcher mcp`;

const mcpJson = `{
  "mcpServers": {
    "docswatcher": { "command": "docswatcher", "args": ["mcp"] }
  }
}`;

const install = `curl -sSL -o docswatcher \\
  https://github.com/jameskomo/docswatcher/releases/latest/download/docswatcher-linux-x64
chmod +x docswatcher && sudo mv docswatcher /usr/local/bin/`;

// Verbatim from a Claude Code session on 2026-09-23. The prompt never mentioned DocsWatcher.
const exchange = [
  { who: "You", text: "Write a minimal Python script that summarises a paragraph with the OpenAI chat completions API using the gpt-4-turbo model." },
  { who: "Agent calls", text: 'check_api {"value": "gpt-4-turbo", "provider": "openai", "kind": "model"}', mono: true },
  { who: "DocsWatcher", text: "RETIRING in 30 days (2026-10-23): gpt-4-turbo shut down [OpenAI]. Replacement: gpt-5.6-sol.", mono: true },
  { who: "Agent", text: "Note: OpenAI shuts down gpt-4-turbo on 2026-10-23, 30 days from now, after which this call will fail, so change the model string to the recommended replacement, gpt-5.6-sol, before then." },
];

useHead({
  title: "DocsWatcher for coding agents",
  meta: [{ name: "description", content: "An MCP server that lets Claude Code, Cursor and other coding agents check whether a model ID or API is being shut down before they write it." }],
});
</script>

<template>
  <div class="stack" style="gap: var(--s5)">
    <section class="page" style="padding-top: var(--s2)">
      <div class="section-head">
        <h1>Your coding agent's training data is older than the deprecation list.</h1>
        <p class="lede">
          Ask an agent for "a quick OpenAI call" and it writes the model ID it remembers. Some of
          those are already shut down, and more are on a date. DocsWatcher runs as an MCP server
          next to your agent, so it can check a model, endpoint or API version before it writes
          one.
        </p>
      </div>
    </section>

    <div class="prose" style="max-width: 900px">
      <h2 style="margin-top: 0">What it looks like</h2>
      <div class="exchange" data-testid="agent-exchange">
        <div v-for="(m, i) in exchange" :key="i" class="turn">
          <span class="who">{{ m.who }}</span>
          <span :class="{ mono: m.mono }">{{ m.text }}</span>
        </div>
      </div>
      <p class="t1 ink-faint" style="margin-top: var(--s1)">Recorded in Claude Code on 23 September 2026, unedited.</p>
      <p>
        The prompt never mentioned DocsWatcher. The server tells the agent at startup to check
        identifiers before writing them, and the agent did. It still used the model you asked for,
        and told you when it stops working and what replaces it.
      </p>

      <h2>Install</h2>
      <p>One binary, the same one the CI Action uses. Linux x64:</p>
      <Snippet :code="install" />
      <p>Then add it to your agent. Claude Code:</p>
      <Snippet :code="claudeCode" testid="mcp-claude-code" />
      <p>Cursor, Windsurf, Claude Desktop and anything else that reads an <code>mcpServers</code> block:</p>
      <Snippet :code="mcpJson" />

      <h2>What the agent can ask</h2>
      <table>
        <thead>
          <tr><th>Tool</th><th>What it answers</th></tr>
        </thead>
        <tbody>
          <tr>
            <td><code>check_api</code></td>
            <td>
              Is this model ID, endpoint, API version or SDK still safe?
              <div class="eg"><code>gpt-4-turbo</code> <code>openai/gpt-4-turbo</code> <code>POST /v1/assistants</code> <code>2024-04</code></div>
            </td>
          </tr>
          <tr>
            <td><code>upcoming_deprecations</code></td>
            <td>
              What shuts down soon, for one provider or all?
              <div class="eg"><code>{"provider": "openai", "within_days": 60}</code></div>
            </td>
          </tr>
          <tr>
            <td><code>scan_repository</code></td>
            <td>
              Which calls in this directory have a shutdown date?
              <div class="eg"><code>{"path": "."}</code></div>
            </td>
          </tr>
        </tbody>
      </table>

      <div class="callout" style="margin-block: var(--s4)">
        <strong>Nothing leaves your machine.</strong> The knowledge base is compiled into the binary.
        The server makes no network calls, writes no files and runs no processes. It knows
        {{ totalChanges }} published changes across {{ providerNames }}. When it says "no known
        deprecation", it means nothing <em>known</em>.
      </div>

      <h2>Why not just tell the agent?</h2>
      <p>
        A line in your instructions file saying "never use deprecated models" asks the agent to
        follow a rule about facts it does not have. The server gives it the facts, with dates, and
        they update every time you update the binary.
      </p>
    </div>
  </div>
</template>

<style scoped>
.exchange {
  display: grid;
  gap: var(--s2);
  border: 1px solid var(--hair);
  border-radius: var(--radius-sm);
  padding: var(--s3);
}
.turn { display: grid; grid-template-columns: 7.5rem 1fr; gap: var(--s3); align-items: baseline; }
.who { font-size: var(--t1); font-weight: 600; color: var(--ink-soft); text-transform: uppercase; letter-spacing: 0.04em; }
.turn .mono { font-family: var(--face-mono); font-size: var(--t2); overflow-wrap: anywhere; }
.eg { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }
.eg code, td code { overflow-wrap: anywhere; }
@media (max-width: 560px) {
  .turn { grid-template-columns: 1fr; gap: 2px; }
}
</style>
