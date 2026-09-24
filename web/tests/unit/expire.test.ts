// The knowledge watch's one automatic edit: docs/16-knowledge-watch.md, "Expiring records".
import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import YAML from "yaml";
// @ts-expect-error plain ESM build script, no type declarations
import { dueForExpiry, setExpired } from "../../scripts/lib/expire.mjs";

const record = readFileSync(join(__dirname, "..", "..", "..", "knowledge", "providers", "openai", "changes", "openai-gpt-4-turbo-shutdown-2026.yaml"), "utf8");

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
