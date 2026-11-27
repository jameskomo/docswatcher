<script setup lang="ts">
import type { Severity } from "~~/engine/types";
import { SEVERITY_LABEL } from "~/utils/format";
const props = defineProps<{ severity: Severity | "healthy"; label?: string }>();
const text = computed(() => props.label ?? (props.severity === "healthy" ? "Healthy" : SEVERITY_LABEL[props.severity]));
</script>

<template>
  <!-- The word carries the severity, so it is never colour alone. -->
  <span class="sev" :class="severity">{{ text }}</span>
</template>
