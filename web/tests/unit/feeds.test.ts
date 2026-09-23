// The test plan in docs/15-feeds-and-sharing.md.
import { describe, it, expect } from "vitest";
import { join } from "node:path";
// @ts-expect-error plain ESM build script, no type declarations
import { loadKnowledge, isLive } from "../../scripts/lib/knowledge.mjs";
// @ts-expect-error plain ESM build script, no type declarations
import { buildIcs, buildAtom, buildJson, icsEscape, icsFold, flatten, updatedOf } from "../../scripts/lib/feeds.mjs";

const knowledge = loadKnowledge(join(__dirname, "..", "..", "..", "knowledge"));
const live = knowledge.providers.flatMap((p: any) => p.changes).filter(isLive);
const dated = live.filter((c: any) => c.effective);

const events = (ics: string) => ics.split("BEGIN:VEVENT").slice(1);
const uids = (ics: string) => [...ics.matchAll(/^UID:(.+)$/gm)].map((m) => m[1].trim());
/** Undo folding so assertions can read logical lines. */
const unfold = (ics: string) => ics.replace(/\r\n /g, "");

describe("calendar", () => {
  const ics = buildIcs(knowledge);

  it("has every dated live change exactly once, keyed by its ID", () => {
    const got = uids(unfold(ics));
    expect(got).toHaveLength(dated.length);
    expect(new Set(got).size).toBe(got.length);
    expect(got.sort()).toEqual(dated.map((c: any) => `${c.id}@docswatcher.vukisha.co.ke`).sort());
  });

  it("leaves undated records out of calendars", () => {
    const undated = live.filter((c: any) => !c.effective);
    expect(undated.length).toBeGreaterThan(0);
    for (const c of undated) expect(ics).not.toContain(`UID:${c.id}@`);
  });

  it("splits into per-provider calendars that together equal the combined one", () => {
    const all = knowledge.providers.flatMap((p: any) => uids(unfold(buildIcs(knowledge, { provider: p.info.id }))));
    expect(all.sort()).toEqual(uids(unfold(ics)).sort());
  });

  it("uses CRLF, folds at 75 octets and never shows the time as busy", () => {
    expect(ics.endsWith("END:VCALENDAR\r\n")).toBe(true);
    expect(ics.replace(/\r\n/g, "")).not.toContain("\n");
    for (const line of ics.split("\r\n")) expect(new TextEncoder().encode(line).length).toBeLessThanOrEqual(75);
    for (const e of events(ics)) expect(e).toContain("TRANSP:TRANSPARENT");
  });

  it("puts gpt-4-turbo on 2026-10-23 as a one-day event with two reminders", () => {
    const e = events(unfold(ics)).find((x) => x.includes("UID:openai-gpt-4-turbo-shutdown-2026@"))!;
    expect(e).toContain("DTSTART;VALUE=DATE:20261023");
    expect(e).toContain("DTEND;VALUE=DATE:20261024");
    expect(e).toContain("SUMMARY:OpenAI: gpt-4-turbo shut down");
    expect(e.match(/BEGIN:VALARM/g)).toHaveLength(2);
    expect(e).toContain("TRIGGER:-P30D");
  });

  it("escapes text and folds without splitting a character", () => {
    expect(icsEscape("a,b;c\\d\ne")).toBe("a\\,b\\;c\\\\d\\ne");
    const folded = icsFold("DESCRIPTION:" + "é".repeat(60));
    for (const line of folded.split("\r\n")) expect(new TextEncoder().encode(line).length).toBeLessThanOrEqual(75);
    expect(folded.replace(/\r\n /g, "")).toBe("DESCRIPTION:" + "é".repeat(60));
  });

  it("is byte-identical across builds of the same knowledge base", () => {
    expect(buildIcs(knowledge)).toBe(ics);
    expect(buildAtom(knowledge)).toBe(buildAtom(knowledge));
    expect(buildJson(knowledge)).toBe(buildJson(knowledge));
  });
});

describe("atom", () => {
  const atom = buildAtom(knowledge);

  it("has one entry per live record, newest announcement first, each with id, updated and a link", () => {
    const entries = atom.split("<entry>").slice(1);
    expect(entries).toHaveLength(live.length);
    const published = entries.map((e) => /<published>(.+?)<\/published>/.exec(e)![1]);
    expect([...published].sort().reverse()).toEqual(published);
    for (const e of entries) {
      expect(e).toMatch(/<id>tag:docswatcher\.vukisha\.co\.ke,2026:change\/[a-z0-9-]+<\/id>/);
      expect(e).toMatch(/<updated>\d{4}-\d{2}-\d{2}T00:00:00Z<\/updated>/);
      expect(e).toMatch(/<link rel="alternate" href="https?:\/\//);
    }
  });

  it("is well-formed: every opened element closes and no raw ampersand survives", () => {
    expect(atom.startsWith('<?xml version="1.0" encoding="utf-8"?>')).toBe(true);
    expect(atom.match(/<entry>/g)!.length).toBe(atom.match(/<\/entry>/g)!.length);
    expect(atom).not.toMatch(/&(?!amp;|lt;|gt;|quot;|apos;)/);
  });

  it("dates a record by its newest confirmation, not only its announcement", () => {
    expect(updatedOf({ announced: "2026-01-01", sources: [{ observed: "2026-09-19" }] })).toBe("2026-09-19");
  });
});

describe("open data", () => {
  it("holds every live record with its provider's name", () => {
    const data = JSON.parse(buildJson(knowledge));
    expect(data.count).toBe(live.length);
    expect(data.changes).toHaveLength(live.length);
    expect(data.changes.every((c: any) => typeof c.providerName === "string" && c.providerName.length > 0)).toBe(true);
    expect(data.changes.map((c: any) => c.id)).toEqual(flatten(knowledge).map((c: any) => c.id));
  });
});
