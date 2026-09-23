<script setup lang="ts">
// A block of text to copy: a workflow, a command, a badge. Selectable even where the clipboard is not.
// `wrap` is for one long token, like a badge, that is easier to read wrapped than scrolled.
const props = defineProps<{ code: string; testid?: string; wrap?: boolean }>();

const copied = ref(false);
async function copy() {
  try {
    await navigator.clipboard.writeText(props.code);
    copied.value = true;
    setTimeout(() => { copied.value = false; }, 2000);
  } catch { /* clipboard unavailable; the snippet is selectable either way */ }
}
</script>

<template>
  <div class="snippet" :class="{ wrap }" :data-testid="testid">
    <button type="button" class="snippet-copy" @click="copy">{{ copied ? "Copied" : "Copy" }}</button>
    <pre>{{ code }}</pre>
  </div>
</template>

<style scoped>
.snippet { position: relative; }
.snippet pre { margin: 0; overflow-x: auto; padding-right: 72px; }
.snippet.wrap pre { white-space: pre-wrap; overflow-wrap: anywhere; }
.snippet-copy {
  position: absolute;
  top: var(--s2);
  right: var(--s2);
  background: var(--paper);
  border: 1px solid var(--hair);
  border-radius: var(--radius-sm);
  padding: 4px 10px;
  font: inherit;
  font-size: var(--t1);
  color: var(--ink-soft);
  cursor: pointer;
}
.snippet-copy:hover { color: var(--ink); }
.snippet-copy:focus-visible { outline: 2px solid var(--ink-accent); outline-offset: 2px; }
</style>
