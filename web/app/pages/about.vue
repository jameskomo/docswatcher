<script setup lang="ts">
const { knowledge, samples, fixtureSamples } = useKnowledge();

const providers = computed(() =>
  knowledge.providers
    .map((p) => ({ id: p.info.id, name: p.info.name, changes: p.changes.length, policy: p.info.deprecation_policy }))
    .sort((a, b) => b.changes - a.changes),
);
const totalChanges = computed(() => providers.value.reduce((n, p) => n + p.changes, 0));
const negatives = computed(() => fixtureSamples.filter((s) => s.expectedFindings && s.expectedFindings.length === 0).length);

useHead({
  title: "About DocsWatcher · what it is and why it exists",
  meta: [{ name: "description", content: "DocsWatcher finds the external APIs your code calls and tells you which ones have an expiry date. What it is, how it works, and what it does not do yet." }],
});
</script>

<template>
  <div class="prose">
    <section class="hero" style="max-width: none">
      <span class="label">About</span>
      <h1>Dependabot for the APIs you call, not the packages you install</h1>
      <p class="lede">
        DocsWatcher reads a repository and builds an inventory of every external API it depends on.
        It matches that inventory against a public record of provider deprecations, and tells you
        which of your calls stops working, on what date, at which file and line.
      </p>
    </section>

    <h2>The problem</h2>
    <p>
      Your toolchain watches the packages you install. Dependabot opens a pull request when a
      dependency has a new version. Nothing watches the APIs you call.
    </p>
    <p>
      That gap is where outages come from. A model ID in a config file is a string. A pinned API
      version in a URL is a string. An SDK method that maps to an endpoint being removed is an
      ordinary method call. None of these change when the provider retires them. Your lockfile is
      unchanged, your build is green, your tests pass against mocks, and on a date somebody
      published months ago the calls start failing in production.
    </p>
    <p class="callout">
      A concrete example from a real scan. Shopify's own application template pins the Admin API
      version inside an import path. That version left support in October 2025. Nothing in the
      repository's dependency graph records this, and no package update would surface it.
    </p>

    <h2>Why this matters now</h2>
    <p>
      Two things changed the rate at which external contracts expire.
    </p>
    <ul>
      <li>
        <strong>AI model retirements.</strong> Providers retire model IDs every few months, and
        almost every codebase now has model identifiers sitting in configuration. This is the
        highest frequency deprecation event in a modern repository.
      </li>
      <li>
        <strong>Calendar-versioned platforms.</strong> Shopify ships a new Admin API version every
        quarter and supports each one for about twelve months. Missing a window changes response
        shapes without returning an error.
      </li>
    </ul>
    <p>
      The knowledge base currently tracks <strong>{{ totalChanges }}</strong> published changes
      across <strong>{{ providers.length }}</strong> providers. Every record carries the provider's
      own source page and the date it was read.
    </p>
    <div class="table-wrap">
      <table>
        <thead><tr><th>Provider</th><th>Tracked changes</th><th>How they version</th></tr></thead>
        <tbody>
          <tr v-for="p in providers" :key="p.id">
            <td>{{ p.name }}</td>
            <td class="n num">{{ p.changes }}</td>
            <td class="muted">{{ p.policy }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <h2>How the scan works</h2>
    <p>
      Detection runs in three layers, cheapest first. Every rule is data in the open knowledge base,
      not code, so a false positive is fixed by editing one line of configuration.
    </p>
    <ul>
      <li>
        <strong>Manifests.</strong> Dependency files are read to learn which provider SDKs the
        project actually declares. This covers npm, PyPI, Maven, Go modules and RubyGems.
      </li>
      <li>
        <strong>Literals.</strong> Regular expressions find model IDs, base URLs and pinned API
        versions wherever they appear, including configuration and environment files.
      </li>
      <li>
        <strong>Call sites.</strong> Source is parsed into a syntax tree and queried, so an SDK
        method is resolved to the endpoint it calls. Six languages are supported today: Java,
        Python, TypeScript, TSX, JavaScript and Go.
      </li>
    </ul>
    <p>
      The layers are gated. Call site rules for a provider never run unless that provider's package
      appeared in a manifest. A file that happens to define a method called <span class="mono">create</span>
      on something called <span class="mono">Source</span> is not a Stripe call unless the project
      depends on Stripe. That gate is the main reason the scanner stays quiet on code it does not
      understand.
    </p>

    <h2>Two engines, held to the same output</h2>
    <p>
      The scan you run in this tab and the scan that runs in continuous integration are the same
      scan. There are two interpreters of the same rules: one in Java for the command line and the
      server, one in TypeScript for the browser.
    </p>
    <p>
      They are not trusted to agree. A parity check runs both engines over all
      <strong>{{ fixtureSamples.length }}</strong> fixtures on every change and compares the output
      byte for byte. If they ever diverge, the build fails. That is what makes a result produced in
      your browser worth the same as one produced in a pipeline.
    </p>

    <h2>Nothing is uploaded</h2>
    <p>
      When you scan a local folder, files are read in this tab and never leave it. When you scan a
      public repository by URL, the files are fetched from a public content delivery network
      straight into your browser. There is no server-side scan, no account, and no copy of your
      code anywhere.
    </p>

    <h2>What DocsWatcher is not</h2>
    <p>
      It is not a feed of provider changelogs. Watching changelogs is a solved and crowded problem,
      and a feed produces alerts about changes that have nothing to do with you.
    </p>
    <p>
      A change becomes a finding here only when it matches something the scanner actually found in
      your code, with a file and a line to prove it. Everything else is filtered out before you see
      it. That join between a published change and your own source is the entire product.
    </p>

    <h2>Precision over recall</h2>
    <p>
      A false positive costs a developer an afternoon and costs us their trust. A missed contract is
      invisible. Those are not symmetric, so the scanner is tuned to stay quiet when unsure.
    </p>
    <p>
      Contracts found only in documentation or test paths are marked low confidence and raise no
      finding by default. Of the {{ fixtureSamples.length }} fixtures in the knowledge base,
      <strong>{{ negatives }}</strong> exist purely to pin down things that must never be reported:
      a provider named in a README, a mocked client in a test file. A scanner that finds something
      in every repository is not one you should trust.
    </p>

    <h2>The knowledge base is open</h2>
    <p>
      Detection rules, deprecation records and fixtures all live in one public directory. Adding a
      provider means writing configuration, not code.
    </p>
    <p>
      One rule governs contributions: no change record is merged without a fixture proving the
      scanner detects it. A deprecation we cannot demonstrate finding is not published.
    </p>

    <h2>What exists today</h2>
    <ul>
      <li>The scanner, both engines, the command line tool and the deprecation calendar.</li>
      <li>{{ totalChanges }} deprecation records across {{ providers.length }} providers, each with its source and the date it was read.</li>
      <li>A server with a scan worker, a dashboard API and GitHub webhook handling.</li>
      <li>Verified results against real public repositories, including ones that correctly report nothing.</li>
    </ul>

    <h2>What does not exist yet</h2>
    <p>Being straight about the gaps is more useful than hiding them.</p>
    <ul>
      <li><strong>Provider coverage is {{ providers.length }}, not ten.</strong> Google AI, Twilio, SendGrid, Slack, GitHub and the AWS SDK are not covered.</li>
      <li><strong>Records are written by hand.</strong> Nothing yet watches provider pages and drafts new records, so the knowledge base needs manual upkeep.</li>
      <li><strong>The automated fix has not run end to end on a real repository.</strong> The dispatch path is built and tested, but no real pull request has been opened by it yet.</li>
      <li><strong>Precision is argued, not measured.</strong> A labelled benchmark corpus with published precision and recall numbers does not exist yet.</li>
      <li><strong>No runtime observation.</strong> Deprecation and sunset headers on live traffic are not yet collected.</li>
    </ul>

    <h2>Try it</h2>
    <p>
      Scan one of the bundled real repositories, paste a public repository URL, or point it at a
      folder on your machine. It takes a few seconds and asks for nothing.
    </p>
    <div class="row">
      <NuxtLink class="btn primary" to="/">Scan a repository</NuxtLink>
      <NuxtLink class="btn" to="/calendar">See what breaks when</NuxtLink>
    </div>
  </div>
</template>
