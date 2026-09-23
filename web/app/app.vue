<script setup lang="ts">
import knowledge from "~~/generated/knowledge.json";

// Guard against web-tree-sitter reading process?.versions.node in browser
if (typeof globalThis !== "undefined" && (globalThis as any).process && !(globalThis as any).process.versions) {
  (globalThis as any).process.versions = {};
}

const providers = knowledge.providers.length;
const changes = knowledge.providers.reduce((n, p) => n + p.changes.length, 0);

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
          <NuxtLink to="/about">About</NuxtLink>
          <button
            type="button"
            class="theme-toggle-btn"
            :aria-label="`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`"
            :title="`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`"
            @click="toggleTheme"
          >
            <span v-if="theme === 'dark'" aria-hidden="true">☀️</span>
            <span v-else aria-hidden="true">🌙</span>
          </button>
        </nav>
      </div>
    </header>

    <main id="content" class="wrap">
      <NuxtPage />
    </main>

    <footer class="site-footer">
      <div class="wrap">
        <span>
          Knowledge base <span class="mono num">{{ knowledge.version }}</span>, tracking
          {{ changes }} published changes across {{ providers }} providers.
          <NuxtLink to="/about">How this works</NuxtLink>
        </span>
        <span>
          <span class="mono">🔒</span> Scans run in your browser. No code leaves it.
        </span>
      </div>
    </footer>
  </div>
</template>
