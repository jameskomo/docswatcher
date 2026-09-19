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
  <div class="prose" style="max-width: 820px; padding-top: var(--s3); margin-inline: auto;">
    <section class="page" style="max-width: none">
      <h1>Dependabot for the APIs you call, not the packages you install</h1>
      <p class="lede" style="margin-top: var(--s3)">
        DocsWatcher inspects your repositories to map every external API and AI model your software relies on.
        It checks that inventory against verified deprecation schedules, highlighting which endpoints are scheduled
        for shutdown, when they expire, and the exact lines of code you need to update.
      </p>
    </section>

    <h2>The Problem</h2>
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
      within resource import paths. When that API version reaches end-of-life, traditional package linters notice nothing,
      yet customer storefront requests immediately begin failing.
    </div>

    <h2>Why This Matters Now</h2>
    <p>
      Two major software trends make external API deprecations happen faster than ever:
    </p>
    <ul>
      <li>
        <strong>Rapid AI model lifecycles.</strong> Leading AI providers routinely retire model versions every few months.
        Hardcoded model strings in configuration and prompts quickly become deprecated, requiring proactive version rotation.
      </li>
      <li>
        <strong>Calendar-versioned platforms.</strong> Modern SaaS platforms like Shopify and Stripe issue regular version
        releases with strict 12-month support windows. Missing a deprecation window can break payload shapes or fail live webhooks.
      </li>
    </ul>

    <h2>Monitored Providers</h2>
    <p>
      DocsWatcher continuously tracks <strong>{{ totalChanges }}</strong> verified breaking changes across
      <strong>{{ providers.length }}</strong> major cloud and AI providers.
    </p>
    <div class="table-wrap" style="margin-block: var(--s4)">
      <table>
        <thead>
          <tr>
            <th>Provider</th>
            <th>Tracked Changes</th>
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
        <strong>Package Manifests:</strong> Inspects declared dependencies (npm, PyPI, Maven, Go modules, RubyGems)
        to identify which third-party provider SDKs your application actually loads.
      </li>
      <li>
        <strong>Configuration & Literals:</strong> Scans configuration files, environment definitions, and source files
        for pinned API versions, model strings, and base URLs.
      </li>
      <li>
        <strong>Method Calls:</strong> Analyzes source code call sites to match SDK invocations against specific retiring endpoints.
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
        <strong>Public Repositories:</strong> When scanning a public repository URL, repository archives are fetched directly
        into your browser and analysed there.
      </li>
      <li>
        <strong>Private Repositories:</strong> For automated CI/CD scans and private repository monitoring, DocsWatcher runs
        inside your own automated workflow runner using your own credentials.
      </li>
    </ul>
    <p>
      One copy does stay on this device. So the dashboard survives a reload, the most recent scan is kept in this
      browser's local storage, and that record includes the matched source lines and their file paths. Nothing is
      transmitted, but it does persist until you clear this site's data.
    </p>

    <h2>What DocsWatcher is not</h2>
    <p>
      It is not a generic feed of third-party changelogs. Watching unfiltered changelogs creates alert fatigue
      about services and features your system never uses.
    </p>
    <p>
      A deprecation only appears as an alert here when DocsWatcher finds active calls in your codebase,
      with the exact file path and line number to verify it.
    </p>

    <h2>What does not exist yet</h2>
    <p>Capabilities actively in development on our roadmap:</p>
    <ul>
      <li>Automated deprecation monitoring for upcoming third-party OpenAPI and GraphQL schemas.</li>
      <li>Passive runtime telemetry observation for Sunset and Deprecation HTTP response headers.</li>
      <li>Cross-repository organizational dependency blast radius mapping.</li>
      <li>Expanded language grammars for additional backend languages.</li>
    </ul>

    <div class="row" style="margin-top: var(--s6); gap: var(--s3)">
      <NuxtLink class="btn solid" to="/">Scan your code</NuxtLink>
      <NuxtLink class="btn" to="/calendar">View deprecation calendar</NuxtLink>
    </div>
  </div>
</template>
