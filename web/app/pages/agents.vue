<script setup lang="ts">
const { knowledge } = useKnowledge();
const totalChanges = knowledge.providers.reduce((n, p) => n + p.changes.length, 0);
const providerCount = knowledge.providers.length;

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

/** The router owns the URL hash, so in-page buttons scroll by hand instead of navigating. */
function go(id: string) {
  document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" });
}

useHead({
  title: "DocsWatcher for AI coding assistants",
  meta: [{ name: "description", content: "Stop Claude Code, Cursor and other AI coding assistants from writing code that uses an AI model or API that is being shut down." }],
});
</script>

<template>
  <article class="agents">
    <section class="doc-opening">
      <p class="eyebrow">For Claude Code, Cursor, Windsurf and Claude Desktop</p>
      <h1>Stop your AI assistant writing code that is about to break.</h1>
      <p class="lede">
        Assistants learned from code written months ago, so they still suggest models and APIs that are
        shutting down. Connect DocsWatcher and your assistant <strong>checks first</strong>, then tells you
        what to use instead.
      </p>
      <div class="doc-actions">
        <button type="button" class="btn solid" @click="go('setup')">Set it up in two steps</button>
        <button type="button" class="btn" @click="go('try')">Try it with your own key</button>
      </div>
    </section>

    <div class="prose doc-body">
      <!-- The proof: a real session, verbatim (2026-09-23). The prompt never mentioned DocsWatcher. -->
      <section class="doc-chapter">
        <p class="eyebrow">What it looks like · recorded in Claude Code, 23 September 2026</p>
        <div class="exchange" data-testid="agent-exchange">
          <div class="turn">
            <span class="who">You</span>
            <span class="said">Write a minimal Python script that summarises a paragraph with the OpenAI chat completions API using the <span class="hl">gpt-4-turbo</span> model.</span>
          </div>
          <div class="turn">
            <span class="who">Assistant</span>
            <span class="said"><span class="call">check_api("gpt-4-turbo")</span> <span class="aside">it asked DocsWatcher on its own</span></span>
          </div>
          <div class="turn turn-answer">
            <span class="who">DocsWatcher</span>
            <span class="said"><span class="badge">RETIRING</span> in 30 days (2026-10-23): gpt-4-turbo shut down [OpenAI]. Replacement: <span class="hl">gpt-5.6-sol</span>.</span>
          </div>
          <div class="turn">
            <span class="who">Assistant</span>
            <span class="said">Note: OpenAI shuts down gpt-4-turbo on 2026-10-23, 30 days from now, after which this call will fail, so change the model string to the recommended replacement, gpt-5.6-sol, before then.</span>
          </div>
        </div>
        <p class="doc-statement display">Nobody asked it to check. You find out <mark>30 days before</mark> the call fails, not the morning it does.</p>
        <p class="fineprint">Your request, DocsWatcher's answer and the assistant's note are word for word; the check is summarised.</p>
      </section>

      <section class="doc-chapter" id="setup">
        <h2>Set it up in two steps</h2>
        <ol class="doc-steps">
          <li>
            <span class="step" aria-hidden="true">1</span>
            <div class="step-body">
              <h3>Install</h3>
              <p>One file. No account, no root.</p>
              <Snippet :code="install" shell />
              <p class="doc-hint">
                Mac with Apple silicon: change <code>linux-x64</code> to <code>macos-arm64</code>. Windows, or to
                download it yourself: the <a href="https://github.com/jameskomo/docswatcher/releases/latest" target="_blank" rel="noopener">release page</a>,
                with <code>checksums.txt</code> to verify it.
              </p>
            </div>
          </li>
          <li>
            <span class="step" aria-hidden="true">2</span>
            <div class="step-body">
              <h3>Connect your assistant</h3>
              <p class="doc-label">Claude Code</p>
              <Snippet :code="claudeCode" testid="mcp-claude-code" shell />
              <p class="doc-label">Cursor, Windsurf or Claude Desktop: add to the MCP settings file (Cursor: <code>~/.cursor/mcp.json</code>)</p>
              <Snippet :code="mcpJson" lang="json" />
            </div>
          </li>
        </ol>
        <p class="done"><strong>That's it.</strong> Nothing to configure, nothing to remember to run.</p>
      </section>

      <section class="doc-chapter">
        <h2>What your assistant can ask</h2>
        <table class="doc-table tools">
          <thead><tr><th>Tool</th><th>The question it answers</th></tr></thead>
          <tbody>
            <tr><td><code>check_api</code></td><td>Is this model, endpoint or API version still safe to use?</td></tr>
            <tr><td><code>upcoming_deprecations</code></td><td>What shuts down soon, for one provider or all of them?</td></tr>
            <tr><td><code>scan_repository</code></td><td>Which calls in this project already have a shutdown date?</td></tr>
          </tbody>
        </table>
        <p data-testid="agents-own-apis">
          It knows <strong>your own APIs</strong> too. Started in a project, it reads that project's
          <code>.docswatcher/</code> records, so an internal endpoint your team is retiring is flagged like a
          provider's. <a href="https://github.com/jameskomo/docswatcher/blob/main/docs/19-your-own-apis.md" target="_blank" rel="noopener">How to write them</a>
        </p>
      </section>

      <section class="doc-chapter" id="try">
        <h2>Try it with your own key</h2>
        <p>
          Ask Claude, GPT or Gemini to write something and compare the answer with DocsWatcher and without it.
          <strong>Your key goes only to the provider</strong>, straight from this browser.
        </p>
        <TryAssistant />
      </section>

      <section class="doc-chapter">
        <h2>Questions</h2>
        <dl class="doc-faq">
          <div>
            <dt>What is MCP?</dt>
            <dd>The Model Context Protocol: the standard way to give an assistant extra tools. The two steps above are the whole setup.</dd>
          </div>
          <div>
            <dt>Does my code get sent anywhere?</dt>
            <dd><strong>No.</strong> It runs on your machine with the shutdown list built in. No network calls, no files written, nothing else run.</dd>
          </div>
          <div>
            <dt>Is my key safe in "Try it"?</dt>
            <dd>It goes from your browser to the provider you pick, never to DocsWatcher. It is kept in memory unless you tick "Remember the key on this device".</dd>
          </div>
          <div>
            <dt>What does it know about?</dt>
            <dd>{{ totalChanges }} published changes across {{ providerCount }} providers (<NuxtLink to="/about">the list</NuxtLink>). "No known deprecation" means nothing <em>known</em>. Run the install step again to update.</dd>
          </div>
          <div>
            <dt>Why not tell it "never use old models"?</dt>
            <dd>It cannot follow a rule about facts it does not have. DocsWatcher gives it today's dates.</dd>
          </div>
          <div>
            <dt>macOS or Windows?</dt>
            <dd>
              Both: <code>docswatcher-macos-arm64</code> and <code>docswatcher-windows-x64.exe</code> on the release page.
              Not on your <code>PATH</code>? Give the assistant the file's full path. Intel Mac:
              <code>java -jar docswatcher.jar mcp</code> with Java 25.
            </dd>
          </div>
        </dl>
      </section>
    </div>
  </article>
</template>

<style scoped>
/* Layout comes from the shared doc-* classes in main.css; what is here is this page's own. */
.exchange { background: var(--night); color: var(--night-ink); border: 1px solid var(--night-line); padding: 6px 0; }
.turn { display: grid; grid-template-columns: 9.5rem minmax(0, 1fr); gap: 16px; padding: 14px 20px; align-items: baseline; }
.turn + .turn { border-top: 1px solid var(--night-line); }
.who { font-size: var(--t1); letter-spacing: 0.06em; text-transform: uppercase; color: var(--night-faint); }
.said { line-height: 1.6; overflow-wrap: anywhere; }
.turn-answer { background: #1a0d0b; }
.turn-answer .who { color: var(--signal); }
.badge { display: inline-block; padding: 1px 8px; margin-right: 6px; background: var(--signal); color: var(--on-signal); font-weight: 700; font-size: var(--t2); letter-spacing: 0.04em; }
.hl { color: #e3c98a; font-weight: 700; }
.call { color: #8ed3c6; }
.aside { color: var(--night-faint); font-size: var(--t2); }
.doc-statement { margin-top: 8px; }
.fineprint { font-size: var(--t1); color: var(--ink-faint) !important; }
.done { font-size: 1.0625rem; }
.tools td:first-child { width: 16rem; }
.tools td:first-child code { font-size: var(--t3); }
@media (max-width: 640px) {
  .turn { grid-template-columns: 1fr; gap: 4px; padding: 12px 14px; }
  .tools td:first-child { width: auto; }
  .tools td:first-child code { font-size: var(--t2); }
}
</style>
