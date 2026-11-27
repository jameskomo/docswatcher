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

/** Lines of the snippet; a whole-line `#` or `//` comment is drawn quieter, since in setup snippets it carries the instructions. */
const lines = computed(() => props.code.split("\n").map((text) => ({ text, comment: /^\s*(#|\/\/)/.test(text) })));

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
    <pre><template v-for="(l, i) in lines" :key="i"><span v-if="l.comment" class="cm">{{ l.text }}</span><template v-else>{{ l.text }}</template><template v-if="i < lines.length - 1">{{ "\n" }}</template></template></pre>
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
  color: var(--night-soft);
}
/* Outlined on the dark block, like the feed links on the departures board: present, not the loudest thing. */
.snippet-copy {
  min-height: 32px;
  background: none;
  border: 1px solid #4a4a45;
  border-radius: 0;
  padding: 4px 10px;
  font: inherit;
  font-size: var(--t1);
  cursor: pointer;
}
.snippet-hint { padding: 4px 2px; pointer-events: none; }
.snippet-copy:hover { color: var(--night-ink); border-color: var(--night-ink); }
.snippet-copy:focus-visible { outline: 2px solid var(--night-ink); outline-offset: 2px; }
</style>
