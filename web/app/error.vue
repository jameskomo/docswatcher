<script setup lang="ts">
import type { NuxtError } from "#app";

const props = defineProps<{ error: NuxtError }>();
const is404 = computed(() => props.error?.statusCode === 404);

useHead({ title: () => (is404.value ? "Not found, DocsWatcher" : "Error, DocsWatcher") });
</script>

<template>
  <div>
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
      </div>
    </header>
    <main class="wrap">
      <section class="page">
        <p class="t2 ink-faint num">Status {{ error?.statusCode ?? "unknown" }}</p>
        <h1>{{ is404 ? "No such page" : "Something went wrong" }}</h1>
        <p class="lede">
          {{ is404
            ? "That address does not match any page here. Nothing was scanned and nothing was lost."
            : (error?.message || "An unexpected error occurred while rendering this page.") }}
        </p>
        <div class="row">
          <NuxtLink class="btn solid" to="/" @click="clearError({ redirect: '/' })">Scan a repository</NuxtLink>
          <NuxtLink class="btn" to="/calendar" @click="clearError({ redirect: '/calendar' })">Deprecation calendar</NuxtLink>
          <NuxtLink class="btn quiet" to="/about" @click="clearError({ redirect: '/about' })">What this is</NuxtLink>
        </div>
      </section>
    </main>
  </div>
</template>
