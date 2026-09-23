// Knowledge watch: notices when a page the knowledge base cites changes, and when a record has not
// been re-verified for a while. See docs/16-knowledge-watch.md. Everything here is a pure function
// except `fetchPage`; the network and the file system stay in scripts/watch-sources.mjs.
import { createHash } from "node:crypto";

/** A page that reduces to less text than this is client-rendered or blocked; there is nothing to compare. */
export const MIN_READABLE = 400;
/** Consecutive failed fetches before a URL is reported. One bad day at a CDN is not news. */
export const FAILURE_THRESHOLD = 3;
/** Days since a record's newest `observed` date before it is listed as ageing. */
export const STALE_DAYS = 30;

const SIGNAL = /\b(deprecat\w*|retir\w*|shut\s?down|shutting down|sunset\w*|end[- ]of[- ](life|support)|eol|remov(e|ed|al|ing)|legacy|discontinu\w*|no longer (supported|available)|decommission\w*)\b/i;
const MONTH = "(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?";
const DATE = new RegExp(`\\b(20\\d\\d-\\d\\d(-\\d\\d)?|${MONTH}\\s+\\d{1,2},?\\s+20\\d\\d|\\d{1,2}\\s+${MONTH}\\s+20\\d\\d)\\b`, "i");

/**
 * Every URL the knowledge base cites, once, with the records that cite it. A provider's changelog
 * is cited by the provider itself, written as `provider:<id>`.
 */
export function collectUrls(knowledge) {
  const byUrl = new Map();
  const cite = (url, who) => {
    if (!url || !/^https?:\/\//.test(url)) return;
    if (!byUrl.has(url)) byUrl.set(url, new Set());
    byUrl.get(url).add(who);
  };
  for (const p of knowledge.providers) {
    cite(p.info.changelog, `provider:${p.info.id}`);
    for (const c of p.changes) for (const s of c.sources ?? []) cite(s.url, c.id);
  }
  return [...byUrl.entries()]
    .map(([url, who]) => ({ url, citedBy: [...who].sort() }))
    .sort((a, b) => (a.url < b.url ? -1 : 1));
}

const ENTITIES = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " ", mdash: "-", ndash: "-", hellip: "...", rsquo: "'", lsquo: "'", rdquo: '"', ldquo: '"' };

function decode(s) {
  return s.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (m, e) => {
    if (e[0] === "#") {
      const code = e[1] === "x" || e[1] === "X" ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);
      return Number.isFinite(code) ? String.fromCodePoint(code) : m;
    }
    return ENTITIES[e.toLowerCase()] ?? m;
  });
}

const BLOCK = "p|div|li|ul|ol|tr|td|th|table|thead|tbody|h[1-6]|br|hr|section|article|header|footer|nav|main|aside|pre|blockquote|dt|dd|dl|figure|figcaption|details|summary";

/**
 * Reduces HTML to the text a person reads, one block per line. The same page fetched twice must
 * reduce to the same text, or every run reports a change: so scripts, styles and markup go, and
 * whitespace is normalised.
 */
export function reduceHtml(html) {
  let s = String(html);
  if (!/<[a-z!]/i.test(s)) return normaliseLines(s); // plain text or markdown
  s = s.replace(/<!--[\s\S]*?-->/g, " ");
  s = s.replace(/<(script|style|noscript|svg|head|template|iframe)\b[\s\S]*?<\/\1\s*>/gi, " ");
  s = s.replace(new RegExp(`<\\/?(${BLOCK})\\b[^>]*>`, "gi"), "\n");
  s = s.replace(/<[^>]+>/g, " ");
  return normaliseLines(decode(s));
}

function normaliseLines(s) {
  const out = [];
  for (const raw of s.split(/\r?\n/)) {
    const line = raw.replace(/[\s ]+/g, " ").trim();
    if (!line) continue;
    if (out[out.length - 1] === line) continue;
    out.push(line);
  }
  return out.join("\n");
}

export const hash = (text) => createHash("sha256").update(text).digest("hex");

/** Lines in `next` that `previous` did not have, counting repeats, in page order. */
export function addedLines(previous, next) {
  const seen = new Map();
  for (const l of previous.split("\n")) seen.set(l, (seen.get(l) ?? 0) + 1);
  const added = [];
  for (const l of next.split("\n")) {
    const n = seen.get(l) ?? 0;
    if (n > 0) seen.set(l, n - 1);
    else if (l) added.push(l);
  }
  return added;
}

/** Whether a line reads like deprecation news: a deprecation word, or a date. */
export const isSignal = (line) => SIGNAL.test(line) || DATE.test(line);

/** Records whose newest source confirmation is more than STALE_DAYS old on `today`. */
export function ageing(knowledge, today, days = STALE_DAYS) {
  const cutoff = new Date(Date.parse(today + "T00:00:00Z") - days * 86400000).toISOString().slice(0, 10);
  const out = [];
  for (const p of knowledge.providers) {
    for (const c of p.changes) {
      if (c.status !== "active" && c.status !== "expired") continue;
      const newest = (c.sources ?? []).map((s) => s.observed).filter(Boolean).sort().pop() ?? "1970-01-01";
      if (newest < cutoff) out.push({ id: c.id, provider: p.info.id, observed: newest });
    }
  }
  return out.sort((a, b) => (a.observed < b.observed ? -1 : a.observed > b.observed ? 1 : a.id < b.id ? -1 : 1));
}

/**
 * One watch run. `pages` maps a URL to its fetched result ({ ok, status, text } or { ok: false,
 * error }). `state` is the previous run's state; `previousText` maps a URL to its last snapshot.
 * Returns the next state, the snapshots to write, and what is worth telling a person.
 */
export function evaluate({ knowledge, urls, pages, state, previousText, today }) {
  const first = !state || !state.urls;
  const prev = first ? { urls: {}, agedReported: {} } : state;
  const next = { version: 1, lastRun: today, urls: {}, agedReported: { ...(prev.agedReported ?? {}) } };
  const snapshots = {};
  const report = { changed: [], quietlyChanged: [], unreadable: [], failing: [], ageing: [] };

  for (const { url, citedBy } of urls) {
    const was = prev.urls[url] ?? {};
    const got = pages[url];
    if (!got || !got.ok) {
      const failures = (was.failures ?? 0) + 1;
      next.urls[url] = { ...was, failures, lastError: got?.error ?? `HTTP ${got?.status ?? "?"}`, lastTried: today };
      if (failures === FAILURE_THRESHOLD) report.failing.push({ url, citedBy, error: next.urls[url].lastError });
      continue;
    }
    const text = got.text;
    const readable = text.length >= MIN_READABLE;
    next.urls[url] = { hash: hash(text), fetched: today, status: got.status, failures: 0, readable, unreadableReported: was.unreadableReported ?? false };
    snapshots[url] = text;
    if (!readable) {
      if (!was.unreadableReported && !first) report.unreadable.push({ url, citedBy, chars: text.length });
      next.urls[url].unreadableReported = true;
      continue;
    }
    if (first || !was.hash || was.hash === next.urls[url].hash) continue;
    const added = addedLines(previousText[url] ?? "", text);
    const signal = added.filter(isSignal);
    const entry = { url, citedBy, signal: signal.slice(0, 25), moreSignal: Math.max(0, signal.length - 25), otherAdded: added.length - signal.length };
    (signal.length ? report.changed : report.quietlyChanged).push(entry);
  }

  if (!first) {
    for (const a of ageing(knowledge, today)) {
      if (next.agedReported[a.id] === a.observed) continue;
      next.agedReported[a.id] = a.observed;
      report.ageing.push(a);
    }
  } else {
    // A baseline, not news: remember what is already old so it is not all reported tomorrow.
    for (const a of ageing(knowledge, today)) next.agedReported[a.id] = a.observed;
  }

  const worthAnIssue = report.changed.length + report.unreadable.length + report.failing.length + report.ageing.length > 0;
  return { state: next, snapshots, report, worthAnIssue, first };
}

/** File name for a URL's snapshot: readable, and unique through a short hash. */
export function slug(url) {
  const readable = url.replace(/^https?:\/\//, "").replace(/[^a-z0-9]+/gi, "-").replace(/^-|-$/g, "").slice(0, 80);
  return `${readable}-${hash(url).slice(0, 8)}.txt`;
}

export function renderReport(report, { today, repoUrl = "https://github.com/jameskomo/docswatcher" } = {}) {
  const out = [`Knowledge watch for ${today}.`, ""];
  const cited = (list) => list.map((c) => (c.startsWith("provider:") ? `\`${c}\`` : `[\`${c}\`](${repoUrl}/search?q=${encodeURIComponent(c)}&type=code)`)).join(", ");

  if (report.changed.length) {
    out.push(`## Sources that changed with deprecation news (${report.changed.length})`, "",
      "Lines added since the last run that mention a deprecation, a removal or a date. Read the page, then add or update a change record.", "");
    for (const c of report.changed) {
      out.push(`### ${c.url}`, "", `Cited by ${cited(c.citedBy)}.`, "");
      for (const l of c.signal) out.push(`> ${l.replace(/[<>]/g, "")}`);
      if (c.moreSignal) out.push(`> ...and ${c.moreSignal} more`);
      if (c.otherAdded) out.push("", `Plus ${c.otherAdded} other added line${c.otherAdded === 1 ? "" : "s"}.`);
      out.push("");
    }
  }
  if (report.failing.length) {
    out.push(`## Sources failing ${FAILURE_THRESHOLD} runs in a row (${report.failing.length})`, "");
    for (const f of report.failing) out.push(`- ${f.url}: ${f.error}. Cited by ${cited(f.citedBy)}.`);
    out.push("");
  }
  if (report.unreadable.length) {
    out.push(`## Sources that cannot be watched (${report.unreadable.length})`, "",
      `These reduce to under ${MIN_READABLE} characters of text, so they are probably rendered by JavaScript or block automated readers. Check them by hand, or cite a page that serves its text.`, "");
    for (const u of report.unreadable) out.push(`- ${u.url} (${u.chars} characters). Cited by ${cited(u.citedBy)}.`);
    out.push("");
  }
  if (report.ageing.length) {
    out.push(`## Records not re-verified in ${STALE_DAYS} days (${report.ageing.length})`, "",
      "Open each source, confirm the record still says what the page says, and set `observed` to today.", "");
    for (const a of report.ageing) out.push(`- \`${a.id}\`, last observed ${a.observed}`);
    out.push("");
  }
  if (report.quietlyChanged.length) {
    out.push(`<details><summary>Other sources that changed, with nothing that looks like deprecation news (${report.quietlyChanged.length})</summary>`, "");
    for (const q of report.quietlyChanged) out.push(`- ${q.url} (${q.otherAdded} added lines)`);
    out.push("", "</details>", "");
  }
  out.push("---", "Generated by `.github/workflows/knowledge-watch.yml`. Snapshots and history are on the `knowledge-watch` branch.");
  return out.join("\n") + "\n";
}

/** Fetches a page and reduces it. Never throws: a failure is a result, like any other. */
export async function fetchPage(url, { timeoutMs = 30000, fetchImpl = fetch } = {}) {
  try {
    const res = await fetchImpl(url, {
      redirect: "follow",
      signal: AbortSignal.timeout(timeoutMs),
      headers: {
        "user-agent": "DocsWatcher-knowledge-watch/1.0 (+https://github.com/jameskomo/docswatcher)",
        accept: "text/html,text/plain,text/markdown;q=0.9,*/*;q=0.5",
      },
    });
    if (!res.ok) return { ok: false, status: res.status, error: `HTTP ${res.status}` };
    return { ok: true, status: res.status, text: reduceHtml(await res.text()) };
  } catch (e) {
    return { ok: false, error: e?.name === "TimeoutError" ? "timed out" : String(e?.message ?? e) };
  }
}
