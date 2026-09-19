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
          <NuxtLink to="/about">About</NuxtLink>
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
