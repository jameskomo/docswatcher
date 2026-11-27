<script setup lang="ts">
import knowledge from "~~/generated/knowledge.json";

// Guard against web-tree-sitter reading process?.versions.node in browser
if (typeof globalThis !== "undefined" && (globalThis as any).process && !(globalThis as any).process.versions) {
  (globalThis as any).process.versions = {};
}

const providers = knowledge.providers.length;
const changes = knowledge.providers.reduce((n, p) => n + p.changes.length, 0);

// Every feature, one click from any page. Feeds resolve against wherever the site is served.
const base = ref("");
onMounted(() => { base.value = location.href.split("#")[0].replace(/[^/]*$/, ""); });
const GH = "https://github.com/jameskomo/docswatcher";
const DOCS = `${GH}/blob/main/docs`;
const footerLinks = computed(() => [
  { title: "Use it", links: [
    { label: "Scan a repository", to: "/" },
    { label: "Deprecation calendar", to: "/calendar" },
    { label: "Email alerts", to: "/calendar#email-alerts" },
    { label: "In CI (GitHub Action)", to: "/ci" },
    { label: "In CI (GitLab)", to: "/ci#gitlab" },
    { label: "In your AI assistant", to: "/agents" },
    { label: "Try it with your own key", to: "/agents#try" },
  ] },
  { title: "For teams", links: [
    { label: "Sign in with GitHub or GitLab", to: "/app" },
    { label: "What teams get", to: "/teams" },
    { label: "Runtime observation setup", to: "/teams#runtime" },
    { label: "Early access and pricing", to: "/teams#early-access" },
  ] },
  { title: "Subscribe and build on it", links: [
    { label: "Calendar feed (.ics)", href: `${base.value}feeds/deprecations.ics` },
    { label: "Atom feed", href: `${base.value}feeds/deprecations.atom` },
    { label: "Open JSON", href: `${base.value}feeds/deprecations.json` },
    { label: "Your own APIs (docs)", href: `${DOCS}/19-your-own-apis.md` },
    { label: "Runtime observation (docs)", href: `${DOCS}/13-runtime-observation.md` },
  ] },
  { title: "Open source", links: [
    { label: "Source code", href: GH },
    { label: "Downloads (latest release)", href: `${GH}/releases/latest` },
    { label: "Documentation", href: `${GH}#documentation` },
    { label: "Knowledge base", href: `${GH}/tree/main/knowledge` },
    { label: "How this works", to: "/about" },
  ] },
]);
</script>

<template>
  <div>
    <a href="#content" class="skip">Skip to content</a>

    <header class="site-header">
      <div class="wrap">
        <div class="brand-group">
          <NuxtLink to="/" class="wordmark" aria-label="DocsWatcher home">
            <svg class="wordmark-logo" width="22" height="22" viewBox="0 0 22 22" aria-hidden="true">
              <rect x="1" y="1" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" />
              <line x1="5" y1="11" x2="17" y2="11" class="tick" stroke-width="3" />
            </svg>
            <span>DocsWatcher</span>
          </NuxtLink>

          <span class="registry-badge" title="Knowledge base version and the number of published changes it tracks">
            KB {{ knowledge.version }} · {{ changes }} records
          </span>
        </div>

        <nav class="nav" aria-label="Main">
          <NuxtLink to="/">Scan</NuxtLink>
          <NuxtLink to="/calendar">Calendar</NuxtLink>
          <NuxtLink to="/app">Dashboard</NuxtLink>
          <NuxtLink to="/ci">CI</NuxtLink>
          <NuxtLink to="/agents">Agents</NuxtLink>
          <NuxtLink to="/teams">Teams</NuxtLink>
          <NuxtLink to="/about">About</NuxtLink>
        </nav>
      </div>
    </header>

    <main id="content" class="wrap">
      <NuxtPage />
    </main>

    <footer class="site-footer">
      <nav class="wrap footer-links" aria-label="Everything DocsWatcher does" data-testid="footer-links">
        <div v-for="g in footerLinks" :key="g.title">
          <h2>{{ g.title }}</h2>
          <ul>
            <li v-for="l in g.links" :key="l.label">
              <NuxtLink v-if="l.to" :to="l.to">{{ l.label }}</NuxtLink>
              <a v-else :href="l.href" :target="l.href!.startsWith('http') ? '_blank' : undefined" rel="noopener">{{ l.label }}</a>
            </li>
          </ul>
        </div>
      </nav>
      <div class="wrap">
        <span>
          Knowledge base <span class="mono num">{{ knowledge.version }}</span>, tracking
          {{ changes }} published changes across {{ providers }} providers.
          <NuxtLink to="/about">How this works</NuxtLink>
        </span>
        <span>
          <Icon name="lock" :size="14" /> Scans run in your browser. No code leaves it.
        </span>
      </div>
    </footer>
  </div>
</template>
