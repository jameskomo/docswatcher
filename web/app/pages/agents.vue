<script setup lang="ts">
const { knowledge } = useKnowledge();
const totalChanges = knowledge.providers.reduce((n, p) => n + p.changes.length, 0);
const providerNames = knowledge.providers.map((p) => p.info.name).join(", ");

// No sudo: a binary in ~/.local/bin is on most Linux PATHs and never needs root. The page tells
// Mac users which part of the URL to change rather than showing a second snippet.
const install = `mkdir -p ~/.local/bin
curl -sSL -o ~/.local/bin/docswatcher \\
  https://github.com/jameskomo/docswatcher/releases/latest/download/docswatcher-linux-x64
chmod +x ~/.local/bin/docswatcher`;

const claudeCode = `claude mcp add docswatcher -- docswatcher mcp`;

const mcpJson = `{
  "mcpServers": {
    "docswatcher": { "command": "docswatcher", "args": ["mcp"] }
  }
}`;

// Verbatim from a Claude Code session on 2026-09-23. The prompt never mentioned DocsWatcher.
const exchange = [
  { who: "You asked", text: "Write a minimal Python script that summarises a paragraph with the OpenAI chat completions API using the gpt-4-turbo model." },
  { who: "Your assistant checked", text: "Is gpt-4-turbo still safe to use?", note: "It asked DocsWatcher on its own." },
  { who: "DocsWatcher answered", text: "RETIRING in 30 days (2026-10-23): gpt-4-turbo shut down [OpenAI]. Replacement: gpt-5.6-sol.", mono: true },
  { who: "Your assistant told you", text: "Note: OpenAI shuts down gpt-4-turbo on 2026-10-23, 30 days from now, after which this call will fail, so change the model string to the recommended replacement, gpt-5.6-sol, before then." },
];

useHead({
  title: "DocsWatcher for AI coding assistants",
  meta: [{ name: "description", content: "Stop Claude Code, Cursor and other AI coding assistants from writing code that uses an AI model or API that is being shut down." }],
});
</script>

<template>
  <article class="agents">
    <section class="page">
      <div class="section-head">
        <h1>Stop your AI assistant writing code that is about to break.</h1>
        <p class="lede">
          AI coding assistants like Claude Code and Cursor learned from code written months ago, so
          they still suggest AI models and APIs that are being shut down. Connect DocsWatcher and
          your assistant checks first, then tells you what to use instead.
        </p>
      </div>
    </section>

    <div class="prose">
      <h2 style="margin-top: 0">What it looks like</h2>
      <div class="exchange" data-testid="agent-exchange">
        <div v-for="(m, i) in exchange" :key="i" class="turn">
          <span class="who">{{ m.who }}</span>
          <span>
            <span :class="{ mono: m.mono }">{{ m.text }}</span>
            <span v-if="m.note" class="t1 ink-faint" style="display: block">{{ m.note }}</span>
          </span>
        </div>
      </div>
      <p class="t1 ink-faint" style="margin-top: var(--s1)">Recorded in Claude Code on 23 September 2026. Your request, DocsWatcher's answer and the assistant's note are word for word; the check itself is summarised.</p>
      <p>
        Nobody asked it to check. It still wrote the model you asked for, but you found out it stops
        working in 30 days before shipping it, not the morning it fails.
      </p>

      <h2>Try it with your own assistant</h2>
      <p>
        Ask Claude, GPT and Codex, or Gemini to write something, and see the answer with DocsWatcher
        next to the answer without it. Bring your own API key; it stays between your browser and the
        provider.
      </p>
      <TryAssistant />

      <h2>Set it up in two steps</h2>
      <div class="steps">
        <div class="step">
          <span class="step-n" aria-hidden="true">1</span>
          <div class="step-body">
            <p><strong>Install DocsWatcher.</strong> One file, no account, no root. This is for Linux x64; on a Mac with Apple silicon, change <code>linux-x64</code> to <code>macos-arm64</code>.</p>
            <Snippet :code="install" shell />
            <p class="t1 ink-faint">
              To download it yourself, get it from the
              <a href="https://github.com/jameskomo/docswatcher/releases/latest" target="_blank" rel="noopener">release page</a>
              and check it against <code>checksums.txt</code> there.
            </p>
          </div>
        </div>
        <div class="step">
          <span class="step-n" aria-hidden="true">2</span>
          <div class="step-body">
            <p><strong>Connect it to your assistant.</strong> In Claude Code, run:</p>
            <Snippet :code="claudeCode" testid="mcp-claude-code" shell />
            <p>
              In Cursor, Windsurf or Claude Desktop, add this to the assistant's MCP settings file.
              For Cursor that is <code>~/.cursor/mcp.json</code>.
            </p>
            <Snippet :code="mcpJson" />
          </div>
        </div>
      </div>
      <p>That is all. There is nothing to configure and nothing to remember to run.</p>

      <h2>What your assistant can ask it</h2>
      <table>
        <thead>
          <tr><th>Question</th><th>Asked through</th></tr>
        </thead>
        <tbody>
          <tr>
            <td>Is this model, API endpoint or API version still safe to use?</td>
            <td><code>check_api</code></td>
          </tr>
          <tr>
            <td>What is being shut down soon, for one provider or all of them?</td>
            <td><code>upcoming_deprecations</code></td>
          </tr>
          <tr>
            <td>Which calls in this project already have a shutdown date?</td>
            <td><code>scan_repository</code></td>
          </tr>
        </tbody>
      </table>

      <h2>Questions</h2>
      <dl class="faq">
        <dt>What is MCP?</dt>
        <dd>
          The Model Context Protocol, the standard way to give an AI assistant extra tools. You do not
          need to know more than that: the two steps above are the whole setup.
        </dd>

        <dt>Does my code get sent anywhere?</dt>
        <dd>
          No. DocsWatcher runs on your machine, and the list of shutdown dates is built into it. It
          makes no network calls, writes no files and runs nothing else.
        </dd>

        <dt>Is my API key safe in "Try it"?</dt>
        <dd>
          It is sent only from your browser to the provider you pick, never to DocsWatcher: there is
          no DocsWatcher server in between. It is kept in memory and forgotten when you leave the
          page, unless you tick "Remember the key on this device".
        </dd>

        <dt>What does it know about?</dt>
        <dd>
          {{ totalChanges }} published shutdowns and changes across {{ providerNames }}. When it says
          "no known deprecation", it means nothing <em>known</em>. Update it by running the install
          step again.
        </dd>

        <dt>Why not just tell the assistant "never use old models"?</dt>
        <dd>
          It cannot follow a rule about facts it does not have. Its knowledge stops months ago;
          DocsWatcher gives it the current dates.
        </dd>

        <dt>macOS or Windows?</dt>
        <dd>
          A Mac with Apple silicon takes <code>docswatcher-macos-arm64</code>, a single file on the
          release page. If the folder you put it in is not on your <code>PATH</code>, give your
          assistant the full path to the file instead of <code>docswatcher</code>. Windows and Intel
          Macs have no single file yet: the portable <code>docswatcher.jar</code> runs anywhere with
          Java 25, as <code>java -jar docswatcher.jar mcp</code>.
        </dd>
      </dl>
    </div>
  </article>
</template>

<style scoped>
/* One column for everything: headline, intro and body share both edges. */
.agents { max-width: 760px; }
.agents .prose, .agents .lede { max-width: none; }
.agents h1 { font-size: clamp(1.75rem, 3.4vw, 2.5rem); line-height: 1.15; text-wrap: balance; }
.agents .lede { margin-top: var(--s3); }
.agents .prose { margin-top: var(--s5); }
.exchange {
  display: grid;
  gap: var(--s2);
  border: 1px solid var(--hair);
  border-radius: var(--radius-sm);
  padding: var(--s3);
}
.turn { display: grid; grid-template-columns: 11rem 1fr; gap: var(--s3); align-items: baseline; }
.who { font-size: var(--t1); font-weight: 600; color: var(--ink-soft); text-transform: uppercase; letter-spacing: 0.04em; }
.turn .mono { font-family: var(--face-mono); font-size: var(--t2); overflow-wrap: anywhere; }
.steps { display: grid; gap: var(--s5); margin-top: var(--s4); }
.step { display: grid; grid-template-columns: 2rem minmax(0, 1fr); gap: var(--s3); }
.step-n {
  width: 2rem; height: 2rem; border-radius: 50%;
  display: grid; place-items: center;
  font-weight: 700; color: var(--ink-max); border: 1px solid var(--hair-strong);
}
/* min-width: 0 lets a long command scroll inside its block instead of widening the page. */
.step-body { min-width: 0; }
.step-body > * + * { margin-top: var(--s3); }
.faq dt { font-weight: 600; color: var(--ink-max); margin-top: var(--s3); }
.faq dd { margin: 4px 0 0; color: var(--ink-soft); }
td code { overflow-wrap: anywhere; }
@media (max-width: 560px) {
  .turn { grid-template-columns: 1fr; gap: 2px; }
}
</style>
