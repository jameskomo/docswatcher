// The one edit the knowledge watch makes to a change record by itself: `status: active` becomes
// `status: expired` once the record's effective date has passed. See docs/16-knowledge-watch.md.
// Pure functions; the file system stays in scripts/expire-records.mjs.

const iso = (d) => (d instanceof Date ? d.toISOString().slice(0, 10) : d);

/**
 * Whether `docswatcher validate` fails on this record today for being active past its date. The
 * validator accepts `expired` only once the date is in the past, so this is also the first day
 * the change can be made.
 */
export function dueForExpiry(record, today) {
  const effective = iso(record?.effective);
  return record?.status === "active" && typeof effective === "string" && effective < today;
}

const ACTIVE_LINE = /^status:[ \t]*active[ \t]*$/m;

/**
 * The record's YAML with its top-level status line changed, and nothing else. Line-level on
 * purpose: re-serialising the YAML would reflow summaries and drop comments, and the diff a person
 * reviews should be one line. Throws unless there is exactly one such line.
 */
export function setExpired(text) {
  const matches = text.match(new RegExp(ACTIVE_LINE.source, "gm")) ?? [];
  if (matches.length !== 1) throw new Error(`expected one top-level "status: active" line, found ${matches.length}`);
  return text.replace(ACTIVE_LINE, "status: expired");
}
