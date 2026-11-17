// Repairing dead sources: docs/16-knowledge-watch.md, "Repair dead sources".
import { describe, it, expect } from "vitest";
// @ts-expect-error plain ESM build script, no type declarations
import { availabilityQuery, cites, observedFor, replaceUrl, slashVariant, snapshotUrl } from "../../scripts/lib/sources.mjs";

const DEAD = "https://example.test/docs/lifecycle/";
const record = (status = "active") => [
  "id: example-pubsub-shutdown-2025",
  "sources:",
  "  - kind: provider_page",
  "    url: https://example.test/forum/announcement",
  "    observed: 2026-09-24",
  "  - kind: provider_page",
  `    url: ${DEAD}`,
  "    observed: 2026-09-20",
  `    note: "Lifecycle table, as it stood at ${DEAD}"`,
  "migration:",
  "  guide: https://example.test/docs/lifecycle/",
  `status: ${status}`,
  "",
].join("\n");

const answer = (closest: object) => ({ url: DEAD, archived_snapshots: { closest } });

describe("repairing a dead source", () => {
  it("finds the observed date of the source that cites the URL", () => {
    expect(observedFor(record(), DEAD)).toBe("2026-09-20");
    expect(observedFor(record(), "https://example.test/other")).toBeNull();
  });

  it("asks the archive for the snapshot closest to that date", () => {
    const q = new URL(availabilityQuery(DEAD, "2026-09-20"));
    expect(q.origin + q.pathname).toBe("https://archive.org/wayback/available");
    expect(q.searchParams.get("url")).toBe(DEAD);
    expect(q.searchParams.get("timestamp")).toBe("20260920");
  });

  it("takes only a snapshot the archive recorded as a 200, over https", () => {
    const ok = { available: true, status: "200", timestamp: "20260826091823", url: "http://web.archive.org/web/20260826091823/https://example.test/docs/lifecycle" };
    expect(snapshotUrl(answer(ok))).toBe("https://web.archive.org/web/20260826091823/https://example.test/docs/lifecycle");
    expect(snapshotUrl(answer({ ...ok, status: "404" }))).toBeNull();
    expect(snapshotUrl(answer({ ...ok, available: false }))).toBeNull();
    expect(snapshotUrl(answer({ ...ok, url: "https://elsewhere.test/page" }))).toBeNull();
    expect(snapshotUrl({ url: DEAD, archived_snapshots: {} })).toBeNull();
    expect(snapshotUrl(undefined)).toBeNull();
  });

  it("changes only url lines equal to the dead URL", () => {
    const snap = "https://web.archive.org/web/20260920000000/https://example.test/docs/lifecycle/";
    const { text, count } = replaceUrl(record(), DEAD, snap);
    expect(count).toBe(1);
    const before = record().split("\n");
    const changed = text.split("\n").filter((line, i) => line !== before[i]);
    expect(changed).toEqual([`    url: ${snap}`]);
    // The note and the migration guide mention the same address and stay as they were.
    expect(text).toContain(`as it stood at ${DEAD}`);
    expect(text).toContain("  guide: https://example.test/docs/lifecycle/");
  });

  it("leaves a record alone when it does not cite the URL", () => {
    expect(replaceUrl(record(), "https://example.test/gone", "https://web.archive.org/web/1/x")).toEqual({ text: record(), count: 0 });
  });

  it("does not touch withdrawn records", () => {
    expect(cites(record(), DEAD)).toBe(true);
    expect(cites(record("withdrawn"), DEAD)).toBe(false);
    expect(cites(record(), "https://example.test/docs")).toBe(false);
  });

  it("tries the address with its trailing slash toggled, to find a moved page", () => {
    expect(slashVariant("https://example.test/docs/lifecycle/")).toBe("https://example.test/docs/lifecycle");
    expect(slashVariant("https://example.test/docs/lifecycle")).toBe("https://example.test/docs/lifecycle/");
    expect(slashVariant("https://example.test/")).toBeNull();
    expect(slashVariant("https://example.test/page?id=1")).toBeNull();
  });
});
