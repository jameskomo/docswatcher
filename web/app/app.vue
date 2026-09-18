<script setup lang="ts">
import knowledge from "~~/generated/knowledge.json";
const providers = knowledge.providers.length;
const changes = knowledge.providers.reduce((n, p) => n + p.changes.length, 0);
</script>

<template>
  <div>
    <header class="site-header">
      <div class="wrap">
        <NuxtLink to="/" class="wordmark"><span class="dot" aria-hidden="true"></span>DocWatcher</NuxtLink>
        <nav class="nav" aria-label="Main">
          <NuxtLink to="/">Scan</NuxtLink>
          <NuxtLink to="/calendar">Calendar</NuxtLink>
          <NuxtLink to="/app">Dashboard</NuxtLink>
        </nav>
      </div>
    </header>
    <main class="wrap">
      <NuxtPage />
    </main>
    <footer class="site-footer">
      <div class="wrap row between">
        <span>Knowledge base {{ knowledge.version }} · {{ providers }} providers · {{ changes }} tracked changes</span>
        <span>Scans run in your browser. No code leaves it.</span>
      </div>
    </footer>
  </div>
</template>
