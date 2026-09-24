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
      <div class="notice upgrade" role="note" data-testid="upgrade-notice">
        <strong>Used the Action or the native binary before 24 September 2026? Upgrade to v0.3.0.</strong>
        Releases up to v0.2.1 reported every finding as empty, so the Action never failed a build.
        <code>@v0</code> now points at the fix, so most pipelines need no change: just run the
        pipeline again once. If you pinned <code>version: v0.2.1</code> or earlier, remove the pin.
        <a href="https://github.com/jameskomo/docswatcher/blob/main/CHANGELOG.md" target="_blank" rel="noopener">What changed</a>
      </div>
    </section>

    <div class="prose" style="max-width: 900px">
      <h2 style="margin-top: 0">GitHub Actions</h2>
      <p>This is the whole integration.</p>
      <Snippet :code="quickstart" />
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
      <Snippet :code="reportOnly" />
      <p>
        Read the summary for a week, fix or dismiss what it found, then drop the
        <code>fail-on</code> line. From then on a new breaking dependency cannot reach your default
        branch.
      </p>

      <h2>One service in a monorepo</h2>
      <Snippet :code="monorepo" />

      <h2>Anywhere else</h2>
      <p>
        The Action wraps one command, and that command is a single static binary. GitLab CI,
        Jenkins, CircleCI, a git hook, your laptop — anywhere with a shell.
      </p>
      <Snippet :code="shell" shell />
      <p>
        That is Linux x64. On macOS arm64 the file is <code>docswatcher-macos-arm64</code>; on
        Windows, use <code>docswatcher.jar</code> with Java 25. The Action picks the right one for
        its runner and checks it against the release's <code>checksums.txt</code> before it runs.
      </p>
      <p>
        The command exits <code>0</code> when nothing breaking is open and <code>1</code> when something is.
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
.upgrade { margin-top: var(--s4); max-width: 900px; border-left-color: var(--soon); }
.upgrade strong { display: block; color: var(--ink-max); margin-bottom: 4px; }
</style>
