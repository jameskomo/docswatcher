<script setup lang="ts">
const { knowledge, fixtureSamples } = useKnowledge();

const providers = computed(() =>
  knowledge.providers
    .map((p) => ({ id: p.info.id, name: p.info.name, changes: p.changes.length, policy: p.info.deprecation_policy }))
    .sort((a, b) => b.changes - a.changes),
);
const totalChanges = computed(() => providers.value.reduce((n, p) => n + p.changes, 0));

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
  <div class="stack" style="gap: var(--s5)">
    <section class="page" style="padding-top: var(--s2)">
      <div class="section-head">
        <h1>You can pin a package. You can't pin someone else's API.</h1>
        <p class="lede">
          Stripe retires an API version. OpenAI shuts down a model. Shopify drops a release a year
          after publishing it. Your lockfile cannot help with any of them: the code still compiles,
          the tests still pass, and one morning a payment fails. DocsWatcher reads your repository,
          finds every external API it calls, and checks each one against {{ totalChanges }} published
          shutdown notices &mdash; then shows you the file, the line and the date.
        </p>
      </div>
    </section>

    <div class="prose">
      <h2 style="margin-top: 0">The Problem</h2>
      <p>
        Traditional package managers keep your installed dependencies up to date. Dependabot and Renovate alert you
        when a library releases a new version, but nothing monitors the external HTTP endpoints and cloud APIs your
        code actually calls.
      </p>
      <p>
        That visibility gap frequently leads to unexpected production failures. AI model identifiers in configuration
        files, pinned API versions in request headers, and deprecated SDK methods all look like normal code. Your tests
        pass against mocks, your build stays green, and your package locks remain unchanged. Then, on a deadline announced
        months earlier, third-party requests suddenly return 404 or 410 errors in production.
      </p>
      <div class="callout" style="margin-block: var(--s4)">
        <strong>Real-world example:</strong> Shopify application templates frequently pin specific Admin API versions
        within resource import paths. When that API version reaches end of life, traditional package linters notice nothing,
        yet customer storefront requests immediately begin failing.
      </div>

      <h2>Why This Matters Now</h2>
      <p>
        Two major software trends make external API deprecations happen faster than ever:
      </p>
      <ul>
        <li>
          <strong>Rapid AI model lifecycles:</strong> Leading AI providers routinely retire model versions every few months.
          Hardcoded model strings in configuration and prompts quickly become deprecated, requiring proactive version rotation.
        </li>
        <li>
          <strong>Calendar-versioned platforms:</strong> Modern SaaS platforms like Shopify and Stripe issue regular version
          releases with strict 12-month support windows. Missing a deprecation window can break payload shapes or fail live webhooks.
        </li>
      </ul>

      <h2>Monitored Providers</h2>
      <p>
        DocsWatcher continuously tracks <strong>{{ totalChanges }}</strong> verified breaking changes across
        <strong>{{ providers.length }}</strong> major cloud, AI, payment and platform providers.
      </p>
      <div class="table-wrap" style="margin-block: var(--s4)">
        <table>
          <thead>
            <tr>
              <th style="width: 140px">Provider</th>
              <th class="n" style="width: 150px">Tracked Changes</th>
              <th>Deprecation Policy</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in providers" :key="p.id">
              <td style="font-weight: 700; color: var(--ink-max)">{{ p.name }}</td>
              <td class="n num">{{ p.changes }}</td>
              <td class="ink-soft">{{ p.policy }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <h2>How the Scanner Works</h2>
      <p>
        Scanning evaluates your project in three targeted steps:
      </p>
      <ul>
        <li>
          <strong>Package Manifests:</strong> Inspects declared dependencies (npm, PyPI, Maven, Go modules, RubyGems, Composer, NuGet)
          to identify which third-party provider SDKs your application actually loads.
        </li>
        <li>
          <strong>Configuration & Literals:</strong> Scans configuration files, environment definitions, and source files
          for pinned API versions, model strings, and base URLs.
        </li>
        <li>
          <strong>Method Calls:</strong> Analyzes source code call sites with tree-sitter AST queries to match SDK invocations against specific retiring endpoints, in Java, Python, TypeScript, JavaScript, Go, Ruby, PHP and C#.
        </li>
      </ul>

      <h2>Zero Code Uploaded</h2>
      <p>
        Your code security and privacy come first:
      </p>
      <ul>
        <li>
          <strong>Local Folders:</strong> When you point DocsWatcher at a local folder on your computer, all code parsing
          and analysis occur directly inside your browser. No files, code snippets, or environment secrets are transmitted over the network.
        </li>
        <li>
          <strong>Public Repositories:</strong> When scanning a GitHub or GitLab URL, the repository's files are fetched directly
          into your browser and analysed client-side. A GitLab token you give for a private project goes only to that GitLab.
        </li>
        <li>
          <strong>In CI:</strong> The GitHub Action, the GitLab CI template and the binary run inside your own runner, offline,
          with the knowledge base built in.
        </li>
      </ul>
      <p>
        The <NuxtLink to="/teams">GitHub App and GitLab integration</NuxtLink> are the exception, by design: to watch
        private code on every push, the DocsWatcher server clones the commit, scans it in a process of its own, and
        keeps the findings with their file and line for the issues and the dashboard.
      </p>
      <p>
        Browser scans run entirely on client side. No source code or secrets are sent to external servers. To let you refresh the page without losing your scan, results are saved locally in your browser storage. A sample scan stays until you replace it; a scan of your own folder or repository is discarded after an hour, and the dashboard has a Clear this scan control that removes it immediately.
      </p>

      <h2>What DocsWatcher is not</h2>
      <p>
        It is not a generic feed of third-party changelogs. Watching unfiltered changelogs creates alert fatigue
        about services and features your system never uses.
      </p>
      <p>
        A deprecation only becomes a finding when DocsWatcher finds active calls in your codebase,
        with the exact file path and line number to verify it.
      </p>

      <h2>What does not exist yet</h2>
      <ul data-testid="not-built">
        <li>A native download for Intel Macs. They run the portable <code>docswatcher.jar</code> with Java 25 today.</li>
        <li>More providers beyond the {{ providers.length }} tracked today. Each is a detector table and change records in the open knowledge base.</li>
        <li>Alerts beyond email and Slack: Microsoft Teams, webhooks to anything else, and alerts for what only runtime observation saw.</li>
        <li>Fix pull requests on GitLab. A GitLab project gets issues, a commit status and the dashboard; the fix loop is GitHub only.</li>
        <li>Your own APIs shown in full on the dashboard. Issues and check runs carry them; the dashboard names such a finding by its change id.</li>
        <li>A status badge with a count, deliberately: the scan runs in the reader's browser, so a badge could only go stale.</li>
      </ul>

      <div class="row" style="margin-top: var(--s6); gap: var(--s3)">
        <NuxtLink class="btn solid" to="/">Scan your code</NuxtLink>
        <NuxtLink class="btn" to="/calendar">View deprecation calendar</NuxtLink>
      </div>
    </div>
  </div>
</template>
