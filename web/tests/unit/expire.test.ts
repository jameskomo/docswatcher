// The knowledge watch's one automatic edit: docs/16-knowledge-watch.md, "Expiring records".
import { describe, it, expect } from "vitest";
import YAML from "yaml";
// @ts-expect-error plain ESM build script, no type declarations
import { dueForExpiry, setExpired } from "../../scripts/lib/expire.mjs";

// A frozen record, not a file from knowledge/: real records are meant to change (the expiry pull
// request flips them to expired), and on 2026-10-26 reading the live gpt-4-turbo record broke CI the
// moment its own expiry was merged. The nested status line proves only the top-level one changes.
const record = [
  "id: example-model-shutdown-2026",
  "provider: openai",
  "kind: retired",
  "severity: breaking",
  'title: "example-model shut down"',
  "summary: >",
  "  Requests to example-model fail after the shutdown date.",
  "affects:",
  "  - kind: model",
  '    match: "example-model"',
  "announced: 2026-04-22",
  "effective: 2026-10-23",
  "sources:",
  "  - kind: provider_page",
  "    url: https://example.test/deprecations",
  "    observed: 2026-09-18",
  "migration:",
  '  replacement: "example-model-2"',
  "  notes: >",
  "    status: active is how the provider labels the old model in its console.",
  "status: active",
  "",
].join("\n");

describe("expiring a record", () => {
  it("is due only for an active record whose date is in the past, the first day validate fails", () => {
    const r = { status: "active", effective: "2026-10-01" };
    expect(dueForExpiry(r, "2026-10-01")).toBe(false);
    expect(dueForExpiry(r, "2026-10-02")).toBe(true);
    expect(dueForExpiry({ ...r, effective: new Date("2026-10-01T00:00:00Z") }, "2026-10-02")).toBe(true);
    expect(dueForExpiry({ ...r, status: "expired" }, "2026-10-02")).toBe(false);
    expect(dueForExpiry({ ...r, status: "draft" }, "2026-10-02")).toBe(false);
    expect(dueForExpiry({ status: "active" }, "2026-10-02")).toBe(false);
  });

  it("changes the status line and nothing else", () => {
    const out = setExpired(record);
    const before = record.split("\n");
    const after = out.split("\n");
    expect(after).toHaveLength(before.length);
    const changed = before.flatMap((l, i) => (l === after[i] ? [] : [[l, after[i]]]));
    expect(changed).toEqual([["status: active", "status: expired"]]);
    expect(YAML.parse(out).status).toBe("expired");
  });

  it("refuses a record without exactly one top-level active status line", () => {
    expect(() => setExpired("id: x\nstatus: expired\n")).toThrow(/found 0/);
    expect(() => setExpired("id: x\nstatus: active\nstatus: active\n")).toThrow(/found 2/);
    expect(() => setExpired("id: x\nnested:\n  status: active\n")).toThrow(/found 0/);
  });
});
