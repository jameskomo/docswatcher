<script setup lang="ts">
// Free for individuals, early access for teams. No form backend: the request is an email the
// visitor sends themselves, pre-filled with the questions worth answering.
const subject = "DocsWatcher for teams: early access";
const bodyText = [
  "Hi,",
  "",
  "We'd like early access to DocsWatcher for teams.",
  "",
  "Company:",
  "How many repositories:",
  "Which providers we depend on most (OpenAI, Stripe, ...):",
  "What we'd use first (dashboard, alerts, private repos, runtime, our own APIs):",
  "",
].join("\n");
const mailto = `mailto:hello@vukisha.co.ke?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(bodyText)}`;

const free = [
  { what: "Scan any repository in your browser", where: "/" },
  { what: "Fail CI on a dated shutdown", where: "/ci" },
  { what: "Your AI coding assistant checks before it writes", where: "/agents" },
  { what: "Every shutdown date in your calendar", where: "/calendar" },
  { what: "The open knowledge base, and the CLI", where: "https://github.com/jameskomo/docswatcher" },
];

const team = [
  { title: "Every repository, one view", text: "A dashboard across your organisation, and the blast radius of a single shutdown: which repositories break, and where." },
  { title: "Warned before the date", text: "Email or Slack when a repository calls something due to shut down, 30 and 7 days ahead, not the morning it fails." },
  { title: "Private repositories", text: "The GitHub App watches private code on every push and opens an issue per finding, with a fix pull request on request." },
  { title: "What actually runs", text: "Runtime observation from your existing OpenTelemetry: which deprecated calls production really makes, and how often." },
  { title: "Your own APIs", text: "Add your internal services' deprecations, so the teams that call them hear about it the same way." },
];

useHead({
  title: "DocsWatcher for teams",
  meta: [{ name: "description", content: "DocsWatcher is free for developers. Teams get an organisation dashboard, alerts before shutdown dates, private repositories and runtime observation." }],
});
</script>

<template>
  <article class="teams">
    <section class="page">
      <div class="section-head">
        <h1>Free for developers. Built for teams too.</h1>
        <p class="lede">
          Everything that finds a dead API call stays free. Teams that need it across many
          repositories, with warnings before the date, can join early access now.
        </p>
      </div>
    </section>

    <div class="prose">
      <h2 style="margin-top: 0">Free, and staying free</h2>
      <ul class="free" data-testid="free-list">
        <li v-for="f in free" :key="f.what">
          <NuxtLink v-if="f.where.startsWith('/')" :to="f.where">{{ f.what }}</NuxtLink>
          <a v-else :href="f.where" target="_blank" rel="noopener">{{ f.what }}</a>
        </li>
      </ul>

      <h2>For teams</h2>
      <div class="grid" data-testid="team-features">
        <div v-for="t in team" :key="t.title" class="card">
          <strong>{{ t.title }}</strong>
          <p class="t2 ink-soft">{{ t.text }}</p>
        </div>
      </div>

      <div class="callout early" data-testid="early-access">
        <strong>Early access.</strong> The first teams get a founding price and a direct line to
        shape what gets built first. Tell us a little about your setup.
        <div style="margin-top: var(--s3)">
          <a class="btn solid" :href="mailto" data-testid="early-access-link">Request early access</a>
        </div>
        <p class="t1 ink-faint" style="margin-top: var(--s2)">Opens an email to hello@vukisha.co.ke with a few questions filled in.</p>
      </div>
    </div>
  </article>
</template>

<style scoped>
.teams { max-width: 760px; }
.teams .prose, .teams .lede { max-width: none; }
.teams h1 { font-size: clamp(1.75rem, 3.4vw, 2.5rem); line-height: 1.15; text-wrap: balance; }
.teams .lede { margin-top: var(--s3); }
.teams .prose { margin-top: var(--s5); }
.free { display: grid; gap: var(--s2); }
.grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 220px), 1fr)); gap: var(--s3); margin-top: var(--s3); }
.card { border: 1px solid var(--hair); border-radius: var(--radius-sm); padding: var(--s3); display: grid; gap: 6px; }
.early { margin-top: var(--s5); }
</style>
