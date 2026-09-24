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
const footerLinks = computed(() => [
  { title: "Use it", links: [
    { label: "Scan a repository", to: "/" },
    { label: "Deprecation calendar", to: "/calendar" },
    { label: "Dashboard", to: "/app" },
    { label: "In CI (GitHub Action)", to: "/ci" },
    { label: "In your AI assistant", to: "/agents" },
    { label: "Try it with your own key", to: "/agents#try" },
    { label: "For teams: early access", to: "/teams" },
  ] },
  { title: "Subscribe and build on it", links: [
    { label: "Calendar feed (.ics)", href: `${base.value}feeds/deprecations.ics` },
    { label: "Atom feed", href: `${base.value}feeds/deprecations.atom` },
    { label: "Open JSON", href: `${base.value}feeds/deprecations.json` },
  ] },
  { title: "Open source", links: [
    { label: "Source code", href: GH },
    { label: "Downloads (latest release)", href: `${GH}/releases/latest` },
    { label: "Documentation", href: `${GH}#documentation` },
    { label: "Knowledge base", href: `${GH}/tree/main/knowledge` },
    { label: "How this works", to: "/about" },
  ] },
]);

const theme = ref<"dark" | "light">("dark");

function applyTheme(t: "dark" | "light") {
  theme.value = t;
  if (typeof document !== "undefined") {
    document.documentElement.setAttribute("data-theme", t);
  }
}

function toggleTheme() {
  const next = theme.value === "dark" ? "light" : "dark";
  applyTheme(next);
  try {
    localStorage.setItem("docswatcher.theme", next);
  } catch {}
}

onMounted(() => {
  try {
    const saved = localStorage.getItem("docswatcher.theme");
    if (saved === "light" || saved === "dark") {
      applyTheme(saved);
      return;
    }
  } catch {}
  if (window.matchMedia && window.matchMedia("(prefers-color-scheme: light)").matches) {
    applyTheme("light");
  } else {
    applyTheme("dark");
  }
});
</script>

<template>
  <div>
    <a href="#content" class="skip">Skip to content</a>

    <header class="site-header">
      <div class="wrap">
        <div class="brand-group">
          <NuxtLink to="/" class="wordmark" aria-label="DocsWatcher home">
            <span class="wordmark-logo">
              <span class="tick" aria-hidden="true"></span>
            </span>
            <span>DocsWatcher</span>
          </NuxtLink>

          <span class="registry-badge" title="Deprecation registry active and verified">
            <span class="live-dot" aria-hidden="true"></span>
            <span>KB {{ knowledge.version }} · {{ changes }} tracked</span>
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
          <button
            type="button"
            class="theme-toggle-btn"
            :aria-label="`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`"
            :title="`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`"
            @click="toggleTheme"
          >
            <Icon v-if="theme === 'dark'" name="sun" />
            <Icon v-else name="moon" />
          </button>
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
