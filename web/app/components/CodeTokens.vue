<script setup lang="ts">
// Highlighted code as inline text, for inside a <pre> or a <code>. It shows the plain text at once and
// swaps in the coloured tokens when they arrive; the text is identical, so nothing moves.
import { highlight, plainLines, type CodeLang, type CodeToken } from "~/utils/highlight";

const props = defineProps<{ code: string; lang: CodeLang }>();

const lines = ref<CodeToken[][]>(plainLines(props.code));
let request = 0;
watch(
  () => [props.code, props.lang] as const,
  async ([code, lang]) => {
    const mine = ++request;
    lines.value = plainLines(code);
    const tokens = await highlight(code, lang);
    if (mine === request) lines.value = tokens; // a newer request has not overtaken this one
  },
  { immediate: true },
);
</script>

<template><template v-for="(line, i) in lines" :key="i"><span v-for="(t, j) in line" :key="j" :style="t.color ? { color: t.color } : undefined" :class="{ 'tok-italic': t.italic }">{{ t.content }}</span><template v-if="i < lines.length - 1">{{ "\n" }}</template></template></template>
