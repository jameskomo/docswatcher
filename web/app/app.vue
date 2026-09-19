<script setup lang="ts">
import knowledge from "~~/generated/knowledge.json";
const providers = knowledge.providers.length;
const changes = knowledge.providers.reduce((n, p) => n + p.changes.length, 0);
</script>

<template>
  <div>
    <a href="#content" class="skip">Skip to content</a>
    <header class="site-header">
      <div class="wrap">
        <NuxtLink to="/" class="wordmark" aria-label="DocsWatcher home">
          <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="11" cy="13" r="7.5" fill="none" stroke="currentColor" stroke-width="2" />
            <path d="M11 13 L11 8" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
            <circle cx="18.5" cy="5.5" r="3.5" fill="var(--critical)" />
          </svg>
          <span class="name">DocsWatcher</span>
        </NuxtLink>
        <nav class="nav" aria-label="Main">
          <NuxtLink to="/">Scan</NuxtLink>
          <NuxtLink to="/calendar">Calendar</NuxtLink>
          <NuxtLink to="/app">Dashboard</NuxtLink>
          <NuxtLink to="/about">About</NuxtLink>
        </nav>
      </div>
    </header>
    <main id="content" class="wrap">
      <NuxtPage />
    </main>
    <footer class="site-footer">
      <div class="wrap row between">
        <span>
          Knowledge base <span class="mono">{{ knowledge.version }}</span> ·
          {{ providers }} providers · {{ changes }} tracked changes ·
          <NuxtLink to="/about">how this works</NuxtLink>
        </span>
        <span>Scans run in your browser. No code leaves it.</span>
      </div>
    </footer>
  </div>
</template>

<style>
.skip {
  position: absolute; left: -9999px; top: 0; z-index: 10;
  background: var(--surface); color: var(--ink); padding: var(--s2) var(--s3);
  border: 1px solid var(--line-strong); border-radius: var(--r);
}
.skip:focus { left: var(--s3); top: var(--s3); }
</style>
