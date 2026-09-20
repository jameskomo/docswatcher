<script setup lang="ts">
const { knowledge } = useKnowledge();
const totalChanges = computed(() =>
  knowledge.providers.reduce((n, p) => n + p.changes.length, 0),
);

const copied = ref("");
async function copy(id: string, text: string) {
  try {
    await navigator.clipboard.writeText(text);
    copied.value = id;
    setTimeout(() => { if (copied.value === id) copied.value = ""; }, 2000);
  } catch { /* clipboard unavailable; the snippet is selectable either way */ }
}

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

const shell = `curl -sSL -o docswatcher \\
  https://github.com/jameskomo/docswatcher/releases/latest/download/docswatcher-linux-x64
chmod +x docswatcher
./docswatcher match . --format text`;

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
  <div class="stack" style="gap: var(--s5)">
    <section class="page" style="padding-top: var(--s2)">
      <div class="section-head">
        <h1>Catch it at the commit, not at the outage.</h1>
        <p class="lede">
          DocsWatcher runs in your pipeline and fails the build when your code calls an API that
          already has a shutdown date. The scan is offline: the knowledge base is compiled into the
          binary, so nothing about your code leaves your runner and no service of ours has to be up
          for your build to pass.
        </p>
      </div>
    </section>

    <div class="prose" style="max-width: 900px">
      <h2 style="margin-top: 0">GitHub Actions</h2>
      <p>This is the whole integration.</p>
      <div class="snippet">
        <button type="button" class="snippet-copy" @click="copy('qs', quickstart)">
          {{ copied === 'qs' ? 'Copied' : 'Copy' }}
        </button>
        <pre>{{ quickstart }}</pre>
      </div>
      <p>
        The step fails the build if a breaking deprecation is open, writes a summary to the job
        page, and says nothing at all when your code calls nothing that is going away.
      </p>

      <h2>Turning it on where findings already exist</h2>
      <p>
        Switching this on for the first time on a mature service usually surfaces something.
        Failing the build on day one blocks your team on work nobody planned, so start by
        reporting.
      </p>
      <div class="snippet">
        <button type="button" class="snippet-copy" @click="copy('ro', reportOnly)">
          {{ copied === 'ro' ? 'Copied' : 'Copy' }}
        </button>
        <pre>{{ reportOnly }}</pre>
      </div>
      <p>
        Read the summary for a week, fix or dismiss what it found, then drop the
        <code>fail-on</code> line. From then on a new breaking dependency cannot reach your default
        branch.
      </p>

      <h2>One service in a monorepo</h2>
      <div class="snippet">
        <button type="button" class="snippet-copy" @click="copy('mr', monorepo)">
          {{ copied === 'mr' ? 'Copied' : 'Copy' }}
        </button>
        <pre>{{ monorepo }}</pre>
      </div>

      <h2>Anywhere else</h2>
      <p>
        The Action wraps one command, and that command is a single static binary. GitLab CI,
        Jenkins, CircleCI, a git hook, your laptop — anywhere with a shell.
      </p>
      <div class="snippet">
        <button type="button" class="snippet-copy" @click="copy('sh', shell)">
          {{ copied === 'sh' ? 'Copied' : 'Copy' }}
        </button>
        <pre>{{ shell }}</pre>
      </div>
      <p>
        It exits <code>0</code> when nothing breaking is open and <code>1</code> when something is.
        That exit code is the whole contract; everything above is a wrapper around it.
      </p>

      <div class="callout" style="margin-block: var(--s4)">
        <strong>What a green build means.</strong> It means nothing <em>known</em> is expiring. The
        knowledge base tracks {{ totalChanges }} published shutdowns across
        {{ knowledge.providers.length }} providers, and it is open — if a provider you depend on is
        missing, the rules are YAML and take pull requests.
      </div>

      <h2>Options</h2>
      <table>
        <thead>
          <tr><th>Input</th><th>Default</th><th>What it does</th></tr>
        </thead>
        <tbody>
          <tr>
            <td><code>path</code></td><td><code>.</code></td>
            <td>Directory to scan. Point it at a subdirectory in a monorepo.</td>
          </tr>
          <tr>
            <td><code>fail-on</code></td><td><code>breaking</code></td>
            <td><code>breaking</code> fails on a shutdown that has a date. <code>never</code> reports without failing.</td>
          </tr>
          <tr>
            <td><code>include-low</code></td><td><code>false</code></td>
            <td>Also report contracts found only in documentation or test files. Usually noise.</td>
          </tr>
          <tr>
            <td><code>report</code></td><td>unset</td>
            <td>Write the full JSON findings to this path, for a later step to upload or post.</td>
          </tr>
        </tbody>
      </table>

      <h2>Or let it watch instead</h2>
      <p>
        CI blocks the new mistake. The GitHub App tracks the ones already there: it scans on every
        push, opens an issue per finding with the file and line, and can open a fix pull request
        when you add a label. The two work well together.
        <NuxtLink to="/about">How the scan works</NuxtLink>.
      </p>
    </div>
  </div>
</template>

<style scoped>
.snippet { position: relative; }
.snippet pre { margin: 0; overflow-x: auto; }
.snippet-copy {
  position: absolute;
  top: var(--s2);
  right: var(--s2);
  background: var(--paper);
  border: 1px solid var(--hair);
  border-radius: var(--radius-sm);
  padding: 4px 10px;
  font: inherit;
  font-size: var(--t1);
  color: var(--ink-soft);
  cursor: pointer;
}
.snippet-copy:hover { color: var(--ink); }
.snippet-copy:focus-visible { outline: 2px solid var(--ink-accent); outline-offset: 2px; }
</style>
