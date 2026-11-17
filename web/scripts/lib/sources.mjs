// Repairing a source URL that has gone dead: docs/16-knowledge-watch.md, "Repair dead sources".
// Pure functions; the network calls are in scripts/repair-dead-sources.mjs.

const URL_LINE = /^(\s*(?:-\s+)?url:\s+)(\S+)\s*$/;

/** The `observed` date (YYYY-MM-DD) of the source in this record that cites `url`, or null. */
export function observedFor(text, url) {
  const lines = text.split("\n");
  for (let i = 0; i < lines.length; i++) {
    const m = URL_LINE.exec(lines[i]);
    if (!m || m[2] !== url) continue;
    const o = /^\s*observed:\s+(\d{4}-\d{2}-\d{2})\s*$/.exec(lines[i + 1] ?? "");
    if (o) return o[1];
  }
  return null;
}

/** Whether this record cites `url` as a source and is not withdrawn. */
export function cites(text, url) {
  if (/^status:\s+withdrawn\s*$/m.test(text)) return false;
  return text.split("\n").some((line) => URL_LINE.exec(line)?.[2] === url);
}

/**
 * The https snapshot address from an Internet Archive availability answer
 * (https://archive.org/wayback/available), or null unless the archive recorded the page as a 200.
 * A capture of the error page would replace a dead link with a dead page.
 */
export function snapshotUrl(answer) {
  const c = answer?.archived_snapshots?.closest;
  if (!c || c.available !== true || String(c.status) !== "200" || typeof c.url !== "string") return null;
  const url = c.url.replace(/^http:\/\//, "https://");
  return /^https:\/\/web\.archive\.org\/web\/\d{14}\//.test(url) ? url : null;
}

/** The availability query for `url`, closest to the day it was observed. */
export function availabilityQuery(url, observed) {
  const q = new URLSearchParams({ url });
  if (observed) q.set("timestamp", observed.replaceAll("-", ""));
  return `https://archive.org/wayback/available?${q}`;
}

/** Replaces every `url:` line equal to `dead` with `replacement`. Nothing else in the text changes. */
export function replaceUrl(text, dead, replacement) {
  let count = 0;
  const out = text.split("\n").map((line) => {
    const m = URL_LINE.exec(line);
    if (!m || m[2] !== dead) return line;
    count++;
    return m[1] + replacement;
  });
  return { text: out.join("\n"), count };
}

/** Where a moved page may now live: the same address with its trailing slash toggled. */
export function slashVariant(url) {
  const u = new URL(url);
  if (u.search || u.hash || u.pathname === "/") return null;
  u.pathname = u.pathname.endsWith("/") ? u.pathname.slice(0, -1) : u.pathname + "/";
  return u.toString();
}
