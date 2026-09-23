// Calendar (iCalendar), feed (Atom) and open data (JSON) built from the knowledge base.
// See docs/15-feeds-and-sharing.md. Pure functions of the knowledge base: no clock, no network,
// so an unchanged knowledge base always produces byte-identical files.
import { mkdirSync, writeFileSync, rmSync } from "node:fs";
import { join } from "node:path";
import { isLive, newestObserved } from "./knowledge.mjs";

export const DEFAULT_SITE_URL = "https://docswatcher.vukisha.co.ke";
const HOST = "docswatcher.vukisha.co.ke";

/** Every live change record, flattened, with its provider's display name. Soonest first, undated last. */
export function flatten(knowledge) {
  const out = [];
  for (const p of knowledge.providers) {
    for (const c of p.changes) {
      if (!isLive(c)) continue;
      out.push({ ...c, providerName: p.info.name });
    }
  }
  return out.sort((a, b) => cmp(a.effective ?? "9999-99-99", b.effective ?? "9999-99-99") || cmp(a.id, b.id));
}

const cmp = (a, b) => (a < b ? -1 : a > b ? 1 : 0);

// ---------------------------------------------------------------------------------------------
// iCalendar, RFC 5545

/** TEXT escaping: backslash first, then the three separators, then newlines. */
export function icsEscape(s) {
  return String(s).replace(/\\/g, "\\\\").replace(/;/g, "\\;").replace(/,/g, "\\,").replace(/\r?\n/g, "\\n");
}

/**
 * Lines longer than 75 octets are folded: CRLF, then a space, then the rest. Counted in UTF-8
 * bytes, and never splitting a character, because a split multi-byte character is invalid UTF-8.
 */
export function icsFold(line) {
  const enc = new TextEncoder();
  if (enc.encode(line).length <= 75) return line;
  const parts = [];
  let current = "";
  let bytes = 0;
  let limit = 75;
  for (const ch of line) {
    const n = enc.encode(ch).length;
    if (bytes + n > limit) {
      parts.push(current);
      current = "";
      bytes = 0;
      limit = 74; // continuation lines start with a space, which counts
    }
    current += ch;
    bytes += n;
  }
  parts.push(current);
  return parts.join("\r\n ");
}

const icsDate = (iso) => iso.replaceAll("-", "");

function nextDay(iso) {
  const d = new Date(iso + "T00:00:00Z");
  d.setUTCDate(d.getUTCDate() + 1);
  return d.toISOString().slice(0, 10);
}

function describe(c) {
  const lines = [c.summary];
  if (c.migration?.replacement) lines.push(`Replacement: ${c.migration.replacement}`);
  if (c.migration?.guide) lines.push(`Migration guide: ${c.migration.guide}`);
  lines.push(`Affects: ${c.affects.map((a) => a.match).join(", ")}`);
  return lines.join("\n");
}

/** A calendar of every dated live record, or one provider's when `provider` is given. */
export function buildIcs(knowledge, { provider = null, siteUrl = DEFAULT_SITE_URL } = {}) {
  const stamp = icsDate(newestObserved(knowledge)) + "T000000Z";
  const scope = provider ? knowledge.providers.find((p) => p.info.id === provider)?.info.name ?? provider : null;
  const name = scope ? `${scope} API deprecations (DocsWatcher)` : "API deprecations (DocsWatcher)";
  const lines = [
    "BEGIN:VCALENDAR",
    "VERSION:2.0",
    "PRODID:-//DocsWatcher//Deprecation calendar//EN",
    "CALSCALE:GREGORIAN",
    "METHOD:PUBLISH",
    `X-WR-CALNAME:${icsEscape(name)}`,
    `X-WR-CALDESC:${icsEscape(`Published API shutdown dates${scope ? ` for ${scope}` : ""}, from the DocsWatcher knowledge base. ${siteUrl}/#/calendar`)}`,
    "REFRESH-INTERVAL;VALUE=DURATION:P1D",
    "X-PUBLISHED-TTL:P1D",
  ];
  for (const c of flatten(knowledge)) {
    if (!c.effective) continue;
    if (provider && c.provider !== provider) continue;
    const summary = `${c.providerName}: ${c.title}`;
    lines.push(
      "BEGIN:VEVENT",
      `UID:${c.id}@${HOST}`,
      `DTSTAMP:${stamp}`,
      `DTSTART;VALUE=DATE:${icsDate(c.effective)}`,
      `DTEND;VALUE=DATE:${icsDate(nextDay(c.effective))}`,
      `SUMMARY:${icsEscape(summary)}`,
      `DESCRIPTION:${icsEscape(describe(c))}`,
      `URL:${c.migration?.guide ?? c.sources?.[0]?.url ?? `${siteUrl}/#/calendar`}`,
      `CATEGORIES:${icsEscape(c.providerName)}`,
      // An all-day marker, not a meeting: it must never show someone as busy.
      "TRANSP:TRANSPARENT",
    );
    for (const days of [30, 7]) {
      lines.push(
        "BEGIN:VALARM",
        "ACTION:DISPLAY",
        `TRIGGER:-P${days}D`,
        `DESCRIPTION:${icsEscape(`In ${days} days: ${summary}`)}`,
        "END:VALARM",
      );
    }
    lines.push("END:VEVENT");
  }
  lines.push("END:VCALENDAR");
  return lines.map(icsFold).join("\r\n") + "\r\n";
}

// ---------------------------------------------------------------------------------------------
// Atom, RFC 4287

export function xmlEscape(s) {
  return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&apos;");
}

/** When a record last meant something new: announced, or re-verified against its source. */
export function updatedOf(c) {
  let u = c.announced ?? "1970-01-01";
  for (const s of c.sources ?? []) if (s.observed && s.observed > u) u = s.observed;
  return u;
}

export function buildAtom(knowledge, { siteUrl = DEFAULT_SITE_URL } = {}) {
  const entries = flatten(knowledge).sort((a, b) => cmp(b.announced ?? "", a.announced ?? "") || cmp(a.id, b.id));
  const feedUpdated = entries.reduce((m, c) => (updatedOf(c) > m ? updatedOf(c) : m), "1970-01-01");
  const out = [
    `<?xml version="1.0" encoding="utf-8"?>`,
    `<feed xmlns="http://www.w3.org/2005/Atom">`,
    `  <id>${xmlEscape(`${siteUrl}/feeds/deprecations.atom`)}</id>`,
    `  <title>API deprecations (DocsWatcher)</title>`,
    `  <subtitle>Published shutdown dates for the APIs and AI models your code calls.</subtitle>`,
    `  <updated>${feedUpdated}T00:00:00Z</updated>`,
    `  <link rel="self" type="application/atom+xml" href="${xmlEscape(`${siteUrl}/feeds/deprecations.atom`)}"/>`,
    `  <link rel="alternate" type="text/html" href="${xmlEscape(`${siteUrl}/#/calendar`)}"/>`,
    `  <author><name>DocsWatcher</name><uri>https://github.com/jameskomo/docswatcher</uri></author>`,
  ];
  for (const c of entries) {
    const when = c.effective ? `Effective ${c.effective}.` : "No date announced yet.";
    const replacement = c.migration?.replacement ? ` Replacement: ${c.migration.replacement}.` : "";
    const link = c.migration?.guide ?? c.sources?.[0]?.url ?? `${siteUrl}/#/calendar`;
    out.push(
      `  <entry>`,
      `    <id>tag:${HOST},2026:change/${xmlEscape(c.id)}</id>`,
      `    <title>${xmlEscape(`${c.providerName}: ${c.title}`)}</title>`,
      `    <updated>${updatedOf(c)}T00:00:00Z</updated>`,
      `    <published>${c.announced ?? updatedOf(c)}T00:00:00Z</published>`,
      `    <link rel="alternate" href="${xmlEscape(link)}"/>`,
      `    <category term="${xmlEscape(c.provider)}" label="${xmlEscape(c.providerName)}"/>`,
      `    <category term="${xmlEscape(c.severity)}"/>`,
      `    <summary>${xmlEscape(`${when} ${c.summary}${replacement}`)}</summary>`,
      `  </entry>`,
    );
  }
  out.push(`</feed>`);
  return out.join("\n") + "\n";
}

// ---------------------------------------------------------------------------------------------
// Open data

export function buildJson(knowledge) {
  const changes = flatten(knowledge).map((c) => ({
    id: c.id,
    provider: c.provider,
    providerName: c.providerName,
    kind: c.kind,
    severity: c.severity,
    status: c.status,
    title: c.title,
    summary: c.summary,
    affects: c.affects,
    announced: c.announced ?? null,
    effective: c.effective ?? null,
    replacement: c.migration?.replacement ?? null,
    guide: c.migration?.guide ?? null,
    sources: c.sources ?? [],
  }));
  return JSON.stringify({
    knowledgeVersion: knowledge.version,
    lastVerified: newestObserved(knowledge),
    count: changes.length,
    changes,
  }, null, 2) + "\n";
}

// ---------------------------------------------------------------------------------------------

/** Writes every feed into `dir`, replacing what was there. Returns the file names written. */
export function writeFeeds(knowledge, dir, { siteUrl } = {}) {
  const url = (siteUrl || DEFAULT_SITE_URL).replace(/\/+$/, "");
  rmSync(dir, { recursive: true, force: true });
  mkdirSync(dir, { recursive: true });
  const files = {
    "deprecations.ics": buildIcs(knowledge, { siteUrl: url }),
    "deprecations.atom": buildAtom(knowledge, { siteUrl: url }),
    "deprecations.json": buildJson(knowledge),
  };
  for (const p of knowledge.providers) files[`${p.info.id}.ics`] = buildIcs(knowledge, { provider: p.info.id, siteUrl: url });
  for (const [name, body] of Object.entries(files)) writeFileSync(join(dir, name), body);
  return Object.keys(files);
}
