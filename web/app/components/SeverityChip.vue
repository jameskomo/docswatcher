<script setup lang="ts">
import type { Severity } from "~~/engine/types";
import { SEVERITY_GLYPH, SEVERITY_LABEL } from "~/utils/format";
const props = defineProps<{ severity: Severity | "healthy"; label?: string }>();
const glyph = computed(() => (props.severity === "healthy" ? "✓" : SEVERITY_GLYPH[props.severity]));
const text = computed(() => props.label ?? (props.severity === "healthy" ? "Healthy" : SEVERITY_LABEL[props.severity]));
</script>

<template>
  <span class="chip" :class="severity"><span aria-hidden="true">{{ glyph }}</span>{{ text }}</span>
</template>
