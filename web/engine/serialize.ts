/** Canonical JSON: 2-space indent, insertion key order, trailing newline. Identical to the Java engine's output. */
export function toJson(value: unknown): string {
  return JSON.stringify(value, null, 2) + "\n";
}
