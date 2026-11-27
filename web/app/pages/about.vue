<script setup lang="ts">
const { knowledge, fixtureSamples } = useKnowledge();

const providers = computed(() =>
  knowledge.providers
    .map((p) => ({ id: p.info.id, name: p.info.name, changes: p.changes.length, policy: p.info.deprecation_policy }))
    .sort((a, b) => b.changes - a.changes),
);
const totalChanges = computed(() => providers.value.reduce((n, p) => n + p.changes, 0));
const openaiChanges = computed(() => providers.value.find((p) => p.id === "openai")?.changes ?? 0);

/** The router owns the URL hash, so the contents links scroll by hand instead of navigating. */
function go(id: string) {
  document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" });
}

useHead({
  title: "About DocsWatcher - Early Warning System for External APIs",
  meta: [
    {
      name: "description",
      content: "DocsWatcher finds external API calls and AI model versions in your code and flags approaching deprecation deadlines.",
    },
  ],
});
</script>

<template>
  <div>
    <section class="opening">
      <p class="eyebrow">About</p>
      <h1>You can pin a package. You can't pin someone else's API.</h1>
      <div class="opening-row">
        <p class="lede">
          Your lockfile can't help when a provider retires something: the code still compiles, the tests still
          pass, and one morning a payment fails. DocsWatcher finds every external API your repository calls and
          checks each one against {{ totalChanges }} published shutdown notices, then shows you the file, the line
          and the date.
        </p>
        <ul class="examples">
          <li><span>Stripe</span><span>retires an API version</span></li>
          <li><span>OpenAI</span><span>shuts down a model</span></li>
          <li><span>Shopify</span><span>drops a release after a year</span></li>
        </ul>
      </div>
    </section>

    <div class="body">
      <nav class="toc" aria-label="On this page">
        <ol>
          <li><a href="#gap" @click.prevent="go('gap')"><span>01</span>The gap</a></li>
          <li><a href="#now" @click.prevent="go('now')"><span>02</span>Why now</a></li>
          <li><a href="#how" @click.prevent="go('how')"><span>03</span>How the scan works</a></li>
          <li><a href="#privacy" @click.prevent="go('privacy')"><span>04</span>What leaves your machine</a></li>
          <li><a href="#providers" @click.prevent="go('providers')"><span>05</span>Providers</a></li>
          <li><a href="#missing" @click.prevent="go('missing')"><span>06</span>Not built yet</a></li>
        </ol>
      </nav>

      <article class="prose about-body">
        <section id="gap" class="chapter">
          <p class="num">01</p>
          <h2>Dependabot watches your libraries. Nothing watches the APIs they call.</h2>
          <p>
            Dependabot and Renovate tell you when a library ships a new version. Nothing watches the HTTP endpoints,
            model names and API versions your code actually sends. Model names in config, a version pinned in a
            header, an SDK method marked deprecated: all of it looks like ordinary code. Tests pass against mocks,
            the build stays green, the lockfile doesn't change. Then, on a date announced months earlier, requests
            start returning 404 and 410 in production.
          </p>
          <figure class="quote">
            <blockquote>
              Shopify app templates pin an Admin API version in their import paths. When that version reaches end of
              life, no linter notices, and storefront requests start failing.
            </blockquote>
            <figcaption>Shopify/shopify-app-template-node, one of the bundled samples</figcaption>
          </figure>
        </section>

        <section id="now" class="chapter">
          <p class="num">02</p>
          <h2>Deadlines arrive faster than they used to</h2>
          <div class="pair">
            <div>
              <h3>AI models retire in months</h3>
              <p>
                A model string hard-coded in a prompt or a config file is out of date within the year. OpenAI alone
                has {{ openaiChanges }} dated changes in the knowledge base.
              </p>
            </div>
            <div>
              <h3>Platforms version by calendar</h3>
              <p>
                Shopify and Stripe ship versions on a schedule with fixed support windows. Miss one and payloads
                change shape, or webhooks stop arriving.
              </p>
            </div>
          </div>
        </section>

        <section id="how" class="chapter">
          <p class="num">03</p>
          <h2>Three passes over your repository</h2>
          <ol class="passes">
            <li>
              <span class="step">1</span>
              <h3>Manifests</h3>
              <p>Which provider SDKs the code loads, from npm, PyPI, Maven, Go modules, RubyGems, Composer and NuGet.</p>
              <code>requirements.txt → openai</code>
            </li>
            <li>
              <span class="step">2</span>
              <h3>Config and literals</h3>
              <p>Pinned API versions, model strings and base URLs in config files, environment definitions and source.</p>
              <code>model="gpt-3.5-turbo-0125"</code>
            </li>
            <li>
              <span class="step">3</span>
              <h3>Call sites</h3>
              <p>Tree-sitter queries match SDK calls to retiring endpoints in Java, Python, TypeScript, JavaScript, Go, Ruby, PHP and C#.</p>
              <code>client.beta.assistants.create(</code>
            </li>
          </ol>
          <h3 class="sub">What DocsWatcher is not</h3>
          <p>
            It is not a feed of every provider's changelog. Unfiltered changelogs bury you in changes to services you
            never call. A deprecation becomes a finding only when one of these passes finds a call to it, with the
            file and line to check.
          </p>
        </section>

        <section id="privacy" class="chapter">
          <p class="num">04</p>
          <h2>What leaves your machine</h2>
          <div class="table-scroll">
            <table class="where">
              <thead>
                <tr><th>Where you scan</th><th>What happens</th><th>Leaves your machine</th></tr>
              </thead>
              <tbody>
                <tr>
                  <td>Local folder</td>
                  <td>Parsed in this browser tab with WebAssembly.</td>
                  <td>Nothing</td>
                </tr>
                <tr>
                  <td>Repository URL</td>
                  <td>The files are fetched into this tab and analysed there. A GitLab token you give for a private project goes only to that GitLab.</td>
                  <td>Nothing</td>
                </tr>
                <tr>
                  <td>CI</td>
                  <td>The GitHub Action, the GitLab CI template and the binary run in your own runner, offline, with the knowledge base built in.</td>
                  <td>Nothing</td>
                </tr>
                <tr class="exception">
                  <td>GitHub App, GitLab integration</td>
                  <td>
                    The exception, by design: to watch private code on every push, the DocsWatcher server clones the
                    commit, scans it in a process of its own, and keeps the findings with their file and line for the
                    issues and the <NuxtLink to="/teams">dashboard</NuxtLink>.
                  </td>
                  <td>The commit, to scan it</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p>
            Browser results are kept in this browser's storage so a refresh doesn't lose them. A sample scan stays
            until you replace it; a scan of your own folder or repository is discarded after an hour, and the
            dashboard's Clear this scan control removes it at once.
          </p>
        </section>

        <section id="providers" class="chapter">
          <p class="num">05</p>
          <h2>{{ providers.length }} providers, {{ totalChanges }} dated changes</h2>
          <p>Each provider's full deprecation policy, with its sources, is in the <a href="https://github.com/jameskomo/docswatcher/tree/main/knowledge" target="_blank" rel="noopener">open knowledge base</a>.</p>
          <div class="table-scroll">
            <table class="providers">
              <thead>
                <tr><th>Provider</th><th class="n">Changes</th><th>Deprecation policy</th></tr>
              </thead>
              <tbody>
                <tr v-for="p in providers" :key="p.id">
                  <td>{{ p.name }}</td>
                  <td class="n">{{ p.changes }}</td>
                  <td><span class="policy">{{ p.policy }}</span></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section id="missing" class="chapter">
          <p class="num">06</p>
          <h2>What does not exist yet</h2>
          <ul class="missing" data-testid="not-built">
            <li>A native download for Intel Macs. They run the portable <code>docswatcher.jar</code> with Java 25 today.</li>
            <li>More providers beyond the {{ providers.length }} tracked today. Each is a detector table and change records in the open knowledge base.</li>
            <li>Alerts beyond email and Slack: Microsoft Teams, webhooks to anything else, and alerts for what only runtime observation saw.</li>
            <li>Fix pull requests on GitLab. A GitLab project gets issues, a commit status and the dashboard; the fix loop is GitHub only.</li>
            <li>Your own APIs shown in full on the dashboard. Issues and check runs carry them; the dashboard names such a finding by its change id.</li>
            <li>A status badge with a count, deliberately: the scan runs in the reader's browser, so a badge could only go stale.</li>
          </ul>
        </section>
      </article>
    </div>

    <section class="band">
      <div class="band-inner closing">
        <h2>See what your code calls.</h2>
        <div class="closing-actions">
          <NuxtLink class="btn signal" to="/">Scan your code</NuxtLink>
          <NuxtLink class="btn ghost" to="/calendar">Deprecation calendar</NuxtLink>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.opening { display: flex; flex-direction: column; gap: 28px; padding: 80px 0 64px; }
.opening h1 { font-size: clamp(2.25rem, 5.2vw, 4.25rem); line-height: 1.02; max-width: 15em; }
.opening-row { display: flex; flex-wrap: wrap; gap: 32px 56px; align-items: flex-start; }
.lede { flex: 1 1 420px; min-width: 0; margin: 0; max-width: 36em; font-size: 1.0625rem; line-height: 1.55; color: var(--ink-soft); }
.examples { flex: 1 1 320px; min-width: 0; margin: 0; padding: 0; list-style: none; border-top: 1.5px solid var(--ink-black); font-size: var(--t2); }
.examples li { display: flex; justify-content: space-between; gap: 16px; padding: 14px 0; border-bottom: 1px solid var(--hair); }
.examples li span:last-child { color: var(--ink-faint); text-align: right; }

.body { display: flex; flex-wrap: wrap; align-items: flex-start; gap: 48px 72px; padding-bottom: 96px; }
.toc { flex: 1 1 200px; max-width: 260px; border-top: 1.5px solid var(--ink-black); padding-top: 12px; }
.toc ol { margin: 0; padding: 0; list-style: none; display: grid; gap: 2px; }
.toc a { display: flex; gap: 12px; padding: 8px 0; text-decoration: none; font-size: var(--t2); }
.toc a span { color: var(--ink-faint); }

/* .prose stays on the article for the shared reading styles; the chapter layout here outranks its spacing. */
.about-body { flex: 999 1 560px; min-width: 0; max-width: none; display: flex; flex-direction: column; gap: 88px; }
.about-body > * + * { margin-top: 0; }
.chapter { display: flex; flex-direction: column; gap: 20px; scroll-margin-top: 88px; }
.chapter > p, .chapter > .quote, .chapter > h2, .chapter > .pair, .chapter > .sub { max-width: 44rem; margin: 0; }
.chapter > p { font-size: var(--t3); line-height: 1.7; color: var(--ink); }
.chapter h2 { font-size: clamp(1.625rem, 2.6vw, 2rem); line-height: 1.15; margin: 0; }
.chapter h3 { font-size: 1.25rem; margin: 0; }
.num { color: var(--ink-faint); font-size: var(--t1); }

.quote { margin: 8px 0 0; padding: 24px 0 0; border: 0; border-top: 6px solid var(--signal); background: none; }
.quote blockquote { margin: 0; padding: 0; border: 0; font-size: 1.1875rem; line-height: 1.45; font-weight: 700; color: var(--ink-max); }
.quote figcaption { margin-top: 12px; font-size: var(--t1); color: var(--ink-faint); }

.pair { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 240px), 1fr)); gap: 32px; border-top: 1px solid var(--hair); padding-top: 24px; }
.pair > div { display: grid; gap: 10px; align-content: start; }
.pair p { margin: 0; color: var(--ink-soft); line-height: 1.65; }

.passes { margin: 0; padding: 0; list-style: none; display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 220px), 1fr)); border-top: 1.5px solid var(--ink-black); }
.passes li { display: flex; flex-direction: column; gap: 12px; padding: 24px 24px 24px 0; margin: 0; }
.passes li + li { padding-left: 24px; border-left: 1px solid var(--hair); }
.passes .step { font-size: 2.5rem; font-weight: 700; line-height: 1; color: var(--signal-ink); }
.passes p { margin: 0; font-size: var(--t2); line-height: 1.6; color: var(--ink-soft); }
.passes code { margin-top: auto; padding: 8px 10px; background: var(--bg-surface-hover); font-size: var(--t1); overflow-wrap: anywhere; }
.sub { margin-top: 8px !important; }

.table-scroll { overflow-x: auto; }
.chapter table { width: 100%; border-collapse: collapse; margin: 0; font-size: var(--t2); }
.chapter th, .chapter td { text-align: left; vertical-align: top; padding: 14px 16px 14px 0; border-bottom: 1px solid var(--hair); }
.chapter td:first-child { font-weight: 700; color: var(--ink-max); }
.chapter td { color: var(--ink-soft); }
.chapter .n { text-align: right; padding-right: 24px; font-variant-numeric: tabular-nums; }
/* Policies run to paragraphs; two lines each keeps the table scannable. The full text stays in the page. */
.policy { display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; line-clamp: 2; overflow: hidden; }
.providers td:first-child { white-space: nowrap; }
.where td:last-child { white-space: nowrap; color: var(--ink-max); }
.where .exception td { background: var(--signal-tint); }
.where .exception td:first-child { padding-left: 12px; }
.where .exception td:last-child { color: var(--signal-ink); font-weight: 700; padding-right: 12px; }

.missing { margin: 0; padding: 0; list-style: none; border-top: 1.5px solid var(--ink-black); max-width: 48rem; }
.missing li { margin: 0; padding: 16px 0; border-bottom: 1px solid var(--hair); line-height: 1.6; }

.closing { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 24px; padding-block: 72px; }
.closing h2 { font-size: clamp(1.75rem, 3.4vw, 2.75rem); line-height: 1.05; }
.closing-actions { display: flex; flex-wrap: wrap; gap: 12px; }
.band .btn.ghost { background: none; border-color: var(--night-ink); color: var(--night-ink); }
.band .btn.ghost:hover { background: #1f1f1d; }
.band .btn.signal { color: var(--ink-black); }

@media (max-width: 640px) {
  .opening { padding: 48px 0 40px; }
  .toc { display: none; }
  .passes li, .passes li + li { padding: 20px 0; border-left: 0; border-bottom: 1px solid var(--hair); }
  /* The privacy table stacks: place and verdict on one line, what happens beneath. */
  .where thead { display: none; }
  .where tr { display: grid; grid-template-columns: minmax(0, 1fr) auto; column-gap: 16px; padding: 14px 0; border-bottom: 1px solid var(--hair); }
  .where td { padding: 0; border: 0; }
  .where td:nth-child(2) { grid-column: 1 / -1; grid-row: 2; margin-top: 6px; }
  .where .exception { background: var(--signal-tint); padding-inline: 10px; }
  .where .exception td { background: none; padding: 0; }
  .where .exception td:first-child { padding-left: 0; }
}
</style>
