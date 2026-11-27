<script setup lang="ts">
const { knowledge } = useKnowledge();
const totalChanges = computed(() =>
  knowledge.providers.reduce((n, p) => n + p.changes.length, 0),
);

const quickstart = `name: API contracts
on: [push, pull_request]

jobs:
  docswatcher:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: jameskomo/docswatcher@v0`;

const reportOnly = `      - uses: jameskomo/docswatcher@v0
        with:
          fail-on: never`;

const monorepo = `      - uses: jameskomo/docswatcher@v0
        with:
          path: services/checkout`;

const gitlab = `include:
  - remote: https://raw.githubusercontent.com/jameskomo/docswatcher/v0.4.0/ci/gitlab/docswatcher.gitlab-ci.yml`;

const gitlabSettings = `docswatcher:
  variables:
    DOCSWATCHER_PATH: services/checkout
    DOCSWATCHER_FAIL_ON: never`;

const shell = `curl -sSL -o docswatcher \\
  https://github.com/jameskomo/docswatcher/releases/latest/download/docswatcher-linux-x64
chmod +x docswatcher
./docswatcher match . --format text`;

/** The router owns the URL hash, so in-page buttons scroll by hand instead of navigating. */
function go(id: string) {
  document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" });
}

useHead({
  title: "Add DocsWatcher to your CI - catch API shutdowns at the commit",
  meta: [
    {
      name: "description",
      content:
        "Five lines of YAML to fail your build when your code calls an API that already has a shutdown date. Works with GitHub Actions, GitLab CI, Jenkins, or any shell.",
    },
  ],
});
</script>

<template>
  <article>
    <section class="doc-opening">
      <p class="eyebrow">GitHub Actions · GitLab CI · any shell</p>
      <h1>Catch it at the commit, not at the outage.</h1>
      <p class="lede">
        DocsWatcher fails the build when your code calls an API that already has a shutdown date. It runs
        <strong>offline</strong>: the knowledge base is built into the binary, so nothing leaves your runner and
        no service of ours has to be up for your build to pass.
      </p>
      <div class="doc-actions">
        <button type="button" class="btn solid" @click="go('github')">GitHub Actions</button>
        <button type="button" class="btn" @click="go('gitlab')">GitLab CI</button>
        <button type="button" class="btn" @click="go('shell')">Any shell</button>
      </div>
    </section>

    <div class="prose doc-body">
      <section class="doc-chapter">
        <h2>Add it to your pipeline</h2>
        <ol class="doc-steps">
          <li id="github" class="anchor">
            <span class="step" aria-hidden="true">1</span>
            <div class="step-body">
              <h3>GitHub Actions</h3>
              <p>This is the whole integration.</p>
              <Snippet :code="quickstart" lang="yaml" />
              <ul class="facts">
                <li><strong>Fails the build</strong> on a breaking change that has a date.</li>
                <li><strong>Writes a summary</strong> to the job page.</li>
                <li><strong>Says nothing</strong> when your code calls nothing that is going away.</li>
              </ul>
              <p class="doc-hint" data-testid="upgrade-notice">
                Pinned <code>version: v0.2.1</code> or earlier? Remove the pin: those releases never failed a build.
                <code>@v0</code> already has the fix. <a href="https://github.com/jameskomo/docswatcher/blob/main/CHANGELOG.md" target="_blank" rel="noopener">Changelog</a>
              </p>
            </div>
          </li>
          <li id="gitlab" class="anchor">
            <span class="step" aria-hidden="true">2</span>
            <div class="step-body">
              <h3>GitLab CI</h3>
              <p>Include the template from a release tag, <code>v0.4.0</code> or later.</p>
              <Snippet :code="gitlab" lang="yaml" testid="gitlab-include" />
              <p>
                That adds a <code>docswatcher</code> job to the <code>test</code> stage. It <strong>verifies the binary</strong>
                against the release's <code>checksums.txt</code>, fails the pipeline on a breaking finding, and
                <strong>shows each finding in the merge request</strong> as a Code Quality report.
              </p>
              <p class="doc-label">Settings are variables</p>
              <Snippet :code="gitlabSettings" lang="yaml" />
              <p class="doc-hint">
                Needs a Linux x64 runner, GitLab.com's default. Every setting is in the
                <a href="#options" @click.prevent="go('options')">options</a> below.
              </p>
            </div>
          </li>
          <li id="shell" class="anchor">
            <span class="step" aria-hidden="true">3</span>
            <div class="step-body">
              <h3>Anywhere else</h3>
              <p>Jenkins, CircleCI, a git hook, your laptop: one self-contained binary and one command.</p>
              <Snippet :code="shell" shell />
              <table class="doc-table exits">
                <tbody>
                  <tr><td><code>exit 0</code></td><td>Nothing breaking is open.</td></tr>
                  <tr><td><code>exit 1</code></td><td>Something is. That exit code is the whole contract.</td></tr>
                </tbody>
              </table>
              <p class="doc-hint">
                Linux x64 for glibc (not Alpine). macOS arm64: <code>docswatcher-macos-arm64</code>. Windows x64:
                <code>docswatcher-windows-x64.exe</code>.
              </p>
            </div>
          </li>
        </ol>
      </section>

      <section class="doc-chapter">
        <h2>Turning it on where findings already exist</h2>
        <p>A mature service usually has something. Failing the build on day one blocks your team on unplanned work, so:</p>
        <ol class="doc-steps">
          <li>
            <span class="step" aria-hidden="true">1</span>
            <div class="step-body">
              <h3>Report first</h3>
              <Snippet :code="reportOnly" lang="yaml" />
              <p class="doc-hint">Read the summary for a week. Fix or dismiss what it found.</p>
            </div>
          </li>
          <li>
            <span class="step" aria-hidden="true">2</span>
            <div class="step-body">
              <h3>Then enforce</h3>
              <p>Drop the <code>fail-on</code> line. From then on, <strong>no new breaking dependency reaches your default branch</strong>.</p>
            </div>
          </li>
        </ol>
        <p class="doc-label">One service in a monorepo</p>
        <Snippet :code="monorepo" lang="yaml" />
      </section>

      <section class="doc-chapter" id="options">
        <h2>Options</h2>
        <div class="doc-table-scroll">
          <table class="doc-table options">
            <thead>
              <tr><th>Action input</th><th>GitLab variable</th><th>Default</th><th>What it does</th></tr>
            </thead>
            <tbody>
              <tr>
                <td><code>path</code></td><td><code>DOCSWATCHER_PATH</code></td><td><code>.</code></td>
                <td>Directory to scan; a subdirectory in a monorepo.</td>
              </tr>
              <tr>
                <td><code>fail-on</code></td><td><code>DOCSWATCHER_FAIL_ON</code></td><td><code>breaking</code></td>
                <td><code>breaking</code> fails on a dated shutdown. <code>never</code> reports only.</td>
              </tr>
              <tr>
                <td><code>include-low</code></td><td><code>DOCSWATCHER_INCLUDE_LOW</code></td><td><code>false</code></td>
                <td>Also report contracts found only in docs or tests. Usually noise.</td>
              </tr>
              <tr>
                <td><code>exclude</code></td><td><code>DOCSWATCHER_EXCLUDE</code></td><td>unset</td>
                <td>Paths to skip.</td>
              </tr>
              <tr>
                <td><code>version</code></td><td><code>DOCSWATCHER_VERSION</code></td><td>latest</td>
                <td>Pin a release.</td>
              </tr>
              <tr>
                <td><code>report</code></td><td>always <code>docswatcher.json</code></td><td>unset</td>
                <td>Write the full JSON findings for a later step. GitLab keeps it as an artifact.</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="doc-hint">
          <a href="https://github.com/jameskomo/docswatcher/blob/main/docs/11-ci-integration.md" target="_blank" rel="noopener">Every option, in the docs</a>
        </p>
      </section>

      <section class="doc-chapter">
        <p class="doc-statement display">
          A green build means nothing <mark>known</mark> is expiring.
        </p>
        <p>
          The knowledge base tracks {{ totalChanges }} published changes across {{ knowledge.providers.length }}
          providers, and it is open: if a provider you depend on is missing, the rules are YAML and take pull requests.
        </p>
      </section>

      <section class="doc-chapter">
        <h2>Or let it watch instead</h2>
        <p>
          CI blocks the new mistake. The <strong>GitHub App</strong> and a <strong>connected GitLab group</strong> track the
          ones already there: a scan on every push, an issue per finding, every repository on one
          <NuxtLink to="/app">dashboard</NuxtLink>, and alerts before the date. The two work well together.
        </p>
        <div class="doc-actions"><NuxtLink class="btn" to="/teams">How to start</NuxtLink></div>
      </section>
    </div>
  </article>
</template>

<style scoped>
/* Layout comes from the shared doc-* classes in main.css; what is here is this page's own. */
.anchor { scroll-margin-top: 88px; }
.facts { list-style: none; padding: 0; display: grid; gap: 6px; }
.facts li { position: relative; padding-left: 20px; margin: 0; }
.facts li::before { content: "→"; position: absolute; left: 0; color: var(--ink-faint); }
.exits td:first-child { width: 8rem; }
.options td:nth-child(2) code { font-weight: 400; }
</style>
