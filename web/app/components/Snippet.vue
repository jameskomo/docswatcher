<script setup lang="ts">
// A block of text to copy: a workflow, a config, a command, a badge.
//
// `shell` marks a command someone will paste into a terminal. Those never get a Copy button.
// A page that writes a shell command to the clipboard from script is exactly what a ClickFix
// attack does, and uBlock Origin blocks it and warns the visitor, which is right. So a shell
// snippet selects itself whole on one click, and the visitor copies it themselves.
//
// `wrap` is for one long token, like a badge, that is easier to read wrapped than scrolled.
// It renders as `snippet-wrap`, not `wrap`: `.wrap` is the global page-shell class in
// main.css (max-width, centred, gutter padding). Emitting it here gave the badge snippet
// the page's horizontal padding, which pushed the Copy button outside the dark block.
const props = defineProps<{ code: string; testid?: string; wrap?: boolean; shell?: boolean }>();

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
  <div class="snippet" :class="{ 'snippet-wrap': wrap, shell }" :data-testid="testid">
    <button v-if="!shell" type="button" class="snippet-copy" @click="copy">{{ copied ? "Copied" : "Copy" }}</button>
    <span v-else class="snippet-hint" aria-hidden="true">click to select</span>
    <pre>{{ code }}</pre>
  </div>
</template>

<style scoped>
.snippet { position: relative; }
.snippet pre { margin: 0; overflow-x: auto; padding-right: 72px; }
.snippet.snippet-wrap pre { white-space: pre-wrap; overflow-wrap: anywhere; }
/* One click selects the whole command; the copy itself is the visitor's own. */
.snippet.shell pre { user-select: all; -webkit-user-select: all; cursor: text; padding-right: 110px; }
.snippet-copy, .snippet-hint {
  position: absolute;
  top: var(--s2);
  right: var(--s2);
  font-size: var(--t1);
  color: var(--ink-soft);
}
.snippet-copy {
  background: var(--paper);
  border: 1px solid var(--hair);
  border-radius: var(--radius-sm);
  padding: 4px 10px;
  font: inherit;
  font-size: var(--t1);
  cursor: pointer;
}
.snippet-hint { padding: 4px 2px; pointer-events: none; opacity: 0.8; }
.snippet-copy:hover { color: var(--ink); }
.snippet-copy:focus-visible { outline: 2px solid var(--ink-accent); outline-offset: 2px; }
</style>
