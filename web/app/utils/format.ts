import type { Finding, Severity } from "~~/engine/types";

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

export function fmtDate(iso: string | null | undefined): string {
  if (!iso) return "no date";
  const [y, m, d] = iso.split("-").map(Number);
  return `${d} ${MONTHS[m - 1]} ${y}`;
}
export function fmtMonth(yyyymm: string): string {
  const [y, m] = yyyymm.split("-").map(Number);
  return `${MONTHS[m - 1]} ${y}`;
}
export function daysLabel(n: number | null): string {
  if (n === null) return "no date";
  if (n === 0) return "today";
  if (n > 0) return `in ${n} day${n === 1 ? "" : "s"}`;
  return `${-n} day${n === -1 ? "" : "s"} ago`;
}
export const SEVERITY_LABEL: Record<Severity, string> = { breaking: "Breaking", warning: "Warning", info: "Informational" };
export const SEVERITY_GLYPH: Record<Severity, string> = { breaking: "✕", warning: "!", info: "i" };
export const KIND_LABEL: Record<string, string> = {
  sdk_package: "SDK package", sdk_method: "SDK call", endpoint: "Endpoint", model: "Model", api_version: "API version",
  graphql_operation: "GraphQL operation", webhook: "Webhook",
};
export function contractLabel(id: string): { provider: string; kind: string; key: string } {
  const i = id.indexOf(":"), j = id.indexOf(":", i + 1);
  return { provider: id.slice(0, i), kind: id.slice(i + 1, j), key: id.slice(j + 1) };
}
export function encodeId(id: string): string { return encodeURIComponent(id); }
export function nearest(findings: Finding[]): Finding | null {
  const upcoming = findings.filter((f) => f.daysRemaining !== null && f.daysRemaining >= 0);
  return upcoming.length ? upcoming[0] : null;
}

/**
 * Splits a day count into a numeral and a unit so the numeral can be set as
 * display type. The design anchors each finding on this number, so it has to
 * stand alone rather than sit inside a sentence.
 */
export function daysParts(n: number | null): { n: string; unit: string } {
  if (n === null) return { n: "—", unit: "no date set" };
  if (n === 0) return { n: "0", unit: "today" };
  if (n > 0) return { n: String(n), unit: n === 1 ? "day left" : "days left" };
  return { n: String(-n), unit: -n === 1 ? "day ago" : "days ago" };
}
