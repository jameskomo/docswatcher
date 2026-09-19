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
          <!-- A single overdue mark. The product is one red dot on a calendar. -->
          <span class="tick" aria-hidden="true"></span>
          <span>DocsWatcher</span>
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
      <div class="wrap">
        <span>
          Knowledge base <span class="mono num">{{ knowledge.version }}</span>, tracking
          {{ changes }} published changes across {{ providers }} providers.
          <NuxtLink to="/about">How this works</NuxtLink>
        </span>
        <span>Scans run in your browser. No code leaves it.</span>
      </div>
    </footer>
  </div>
</template>
