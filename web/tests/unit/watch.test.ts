// The test plan in docs/16-knowledge-watch.md.
import { describe, it, expect } from "vitest";
import { join } from "node:path";
// @ts-expect-error plain ESM build script, no type declarations
import { loadKnowledge } from "../../scripts/lib/knowledge.mjs";
import {
  collectUrls, reduceHtml, addedLines, isSignal, ageing, expiring, evaluate, renderReport, fetchPage, slug,
  MIN_READABLE, FAILURE_THRESHOLD, EXPIRY_NOTICE_DAYS,
  // @ts-expect-error plain ESM build script, no type declarations
} from "../../scripts/lib/watch.mjs";

const knowledge = loadKnowledge(join(__dirname, "..", "..", "..", "knowledge"));
const urls = collectUrls(knowledge);

const page = (body: string) => `<html><head><title>x</title><script>var t=${Math.random()}</script></head><body>${body}</body></html>`;
const filler = "<p>" + "This paragraph is ordinary documentation text about the API. ".repeat(10) + "</p>";

describe("collecting sources", () => {
  it("watches every provider changelog and every record source, once each", () => {
    const list = urls.map((u: any) => u.url);
    expect(new Set(list).size).toBe(list.length);
    for (const p of knowledge.providers) {
      expect(list).toContain(p.info.changelog);
      for (const c of p.changes) for (const s of c.sources ?? []) if (s.url) expect(list).toContain(s.url);
    }
  });

  it("knows which records cite each page", () => {
    const openai = urls.find((u: any) => u.url === "https://developers.openai.com/api/docs/deprecations");
    expect(openai.citedBy).toContain("provider:openai");
    expect(openai.citedBy).toContain("openai-gpt-4-turbo-shutdown-2026");
  });
});

describe("reducing a page to text", () => {
  it("is stable across fetches and keeps no script, style or markup", () => {
    const html = page(`<nav>Home</nav><style>.a{}</style><h1>Deprecations</h1><p>Model <b>x</b> &amp; y</p><!-- note -->`);
    const a = reduceHtml(html);
    expect(reduceHtml(page(`<nav>Home</nav><style>.a{}</style><h1>Deprecations</h1><p>Model <b>x</b> &amp; y</p><!-- note -->`))).toBe(a);
    expect(a).toBe("Home\nDeprecations\nModel x & y");
    expect(a).not.toMatch(/[<>]|Math|\.a\{/);
  });

  it("passes plain text and markdown through, normalising whitespace", () => {
    expect(reduceHtml("# Title\n\n  two   spaces  \n")).toBe("# Title\ntwo spaces");
  });
});

describe("what counts as news", () => {
  it("reports added lines only, counting repeats", () => {
    expect(addedLines("a\nb\nb", "a\nb\nb\nb\nc")).toEqual(["b", "c"]);
    expect(addedLines("a\nb", "a\nb")).toEqual([]);
  });

  it("recognises deprecation words and dates, and ignores the rest", () => {
    expect(isSignal("gpt-4.1-nano will be shut down on 2026-10-23")).toBe(true);
    expect(isSignal("Legacy endpoints are retired")).toBe(true);
    expect(isSignal("Announced March 3, 2027")).toBe(true);
    expect(isSignal("Accept all cookies")).toBe(false);
    expect(isSignal("Sign in to your account")).toBe(false);
  });
});

describe("ageing", () => {
  it("lists records whose newest confirmation is over 30 days old, and not fresh ones", () => {
    const old = ageing(knowledge, "2026-12-01").map((a: any) => a.id);
    expect(old).toContain("openai-gpt-4-turbo-shutdown-2026");
    expect(ageing(knowledge, "2026-09-23")).toEqual([]);
  });
});

describe("a watch run", () => {
  const url = "https://example.test/deprecations";
  const one = [{ url, citedBy: ["provider:openai", "openai-gpt-4-turbo-shutdown-2026"] }];
  const before = reduceHtml(page(`<h1>Deprecations</h1>${filler}`));
  const run = (pages: any, state: any, previousText: any = {}, today = "2026-09-24") =>
    evaluate({ knowledge, urls: one, pages, state, previousText, today });

  it("records a baseline on the first run and reports nothing", () => {
    const r = run({ [url]: { ok: true, status: 200, text: before } }, null, {}, "2026-12-01");
    expect(r.first).toBe(true);
    expect(r.worthAnIssue).toBe(false);
    expect(r.snapshots[url]).toBe(before);
    // Records already old on day one are remembered, not reported the next day.
    expect(Object.keys(r.state.agedReported).length).toBeGreaterThan(0);
  });

  it("stays quiet when nothing changed", () => {
    const base = run({ [url]: { ok: true, status: 200, text: before } }, null);
    const again = run({ [url]: { ok: true, status: 200, text: before } }, base.state, { [url]: before });
    expect(again.worthAnIssue).toBe(false);
    expect(again.report.changed).toEqual([]);
  });

  it("reports only the added lines that look like deprecation news, naming the records that cite the page", () => {
    const base = run({ [url]: { ok: true, status: 200, text: before } }, null);
    const after = reduceHtml(page(`<h1>Deprecations</h1><p>Accept all cookies</p><p>o1-mini will be shut down on 2027-01-15.</p>${filler}`));
    const r = run({ [url]: { ok: true, status: 200, text: after } }, base.state, { [url]: before });
    expect(r.worthAnIssue).toBe(true);
    expect(r.report.changed[0].signal).toEqual(["o1-mini will be shut down on 2027-01-15."]);
    expect(r.report.changed[0].otherAdded).toBe(1);
    const md = renderReport(r.report, { today: "2026-09-24" });
    expect(md).toContain("> o1-mini will be shut down on 2027-01-15.");
    expect(md).toContain("openai-gpt-4-turbo-shutdown-2026");
  });

  it("keeps a change with no news out of the issue", () => {
    const base = run({ [url]: { ok: true, status: 200, text: before } }, null);
    const after = reduceHtml(page(`<h1>Deprecations</h1><p>Accept all cookies</p>${filler}`));
    const r = run({ [url]: { ok: true, status: 200, text: after } }, base.state, { [url]: before });
    expect(r.worthAnIssue).toBe(false);
    expect(r.report.quietlyChanged).toHaveLength(1);
  });

  it("marks a page too short to read as unreadable, once", () => {
    const base = run({ [url]: { ok: true, status: 200, text: before } }, null);
    const tiny = { [url]: { ok: true, status: 200, text: "Loading..." } };
    const r1 = run(tiny, base.state, { [url]: before });
    expect("Loading...".length).toBeLessThan(MIN_READABLE);
    expect(r1.report.unreadable).toHaveLength(1);
    expect(run(tiny, r1.state, { [url]: "Loading..." }).report.unreadable).toHaveLength(0);
  });

  it("keeps the old snapshot on failure and reports only at the third failure in a row", () => {
    let r = run({ [url]: { ok: true, status: 200, text: before } }, null);
    const reported: number[] = [];
    for (let i = 1; i <= FAILURE_THRESHOLD + 1; i++) {
      r = run({ [url]: { ok: false, status: 503, error: "HTTP 503" } }, r.state, { [url]: before });
      expect(r.snapshots[url]).toBeUndefined();
      if (r.report.failing.length) reported.push(i);
    }
    expect(reported).toEqual([FAILURE_THRESHOLD]);
    expect(r.state.urls[url].hash).toBeDefined();
  });

  it("reports an ageing record once per confirmation date", () => {
    const base = run({ [url]: { ok: true, status: 200, text: before } }, null, {}, "2026-09-24");
    const later = run({ [url]: { ok: true, status: 200, text: before } }, base.state, { [url]: before }, "2026-12-01");
    expect(later.report.ageing.length).toBeGreaterThan(0);
    const again = run({ [url]: { ok: true, status: 200, text: before } }, later.state, { [url]: before }, "2026-12-02");
    expect(again.report.ageing).toEqual([]);
  });
});

describe("records about to pass their date", () => {
  const rec = (id: string, status: string, effective: string | null) => ({ id, status, effective, sources: [] });
  const kb = {
    version: "t",
    providers: [{
      info: { id: "shopify" },
      changes: [
        rec("shopify-script-tag-create-update-rejected-2026", "active", "2026-10-01"),
        rec("later", "active", "2026-12-01"),
        rec("undated", "active", null),
        rec("already-expired", "expired", "2026-09-01"),
        rec("overdue", "active", "2026-09-20"),
      ],
    }],
  };

  it("lists active records due within the notice window, and overdue ones, with the day to expire them", () => {
    expect(EXPIRY_NOTICE_DAYS).toBe(7);
    expect(expiring(kb, "2026-09-24")).toEqual([
      { id: "overdue", provider: "shopify", effective: "2026-09-20", expireOn: "2026-09-21", overdue: true },
      { id: "shopify-script-tag-create-update-rejected-2026", provider: "shopify", effective: "2026-10-01", expireOn: "2026-10-02", overdue: false },
    ]);
    expect(expiring(kb, "2026-09-23").map((e: any) => e.id)).toEqual(["overdue"]);
  });

  it("reports each record once per date, and names it in the issue", () => {
    const url = "https://example.test/p";
    const text = reduceHtml(page(filler));
    const go = (state: any, today: string) =>
      evaluate({ knowledge: kb, urls: [{ url, citedBy: [] }], pages: { [url]: { ok: true, status: 200, text } }, state, previousText: { [url]: text }, today });
    const base = go(null, "2026-09-10");
    expect(base.report.expiring).toEqual([]);
    const week = go(base.state, "2026-09-24");
    expect(week.worthAnIssue).toBe(true);
    expect(week.report.expiring.map((e: any) => e.id)).toEqual(["overdue", "shopify-script-tag-create-update-rejected-2026"]);
    const md = renderReport(week.report, { today: "2026-09-24" });
    expect(md).toContain("Records that must be set expired (2)");
    expect(md).toContain("`shopify-script-tag-create-update-rejected-2026` takes effect 2026-10-01: set `status: expired` on 2026-10-02");
    expect(md).toContain("`overdue` takes effect 2026-09-20: overdue");
    expect(go(week.state, "2026-09-25").report.expiring).toEqual([]);
  });
});

describe("fetching", () => {
  it("turns failures into results, never exceptions", async () => {
    const down = await fetchPage("https://x.test", { fetchImpl: async () => { throw new TypeError("fetch failed"); } });
    expect(down).toEqual({ ok: false, error: "fetch failed" });
    const missing = await fetchPage("https://x.test", { fetchImpl: async () => new Response("no", { status: 404 }) });
    expect(missing).toMatchObject({ ok: false, status: 404 });
    const fine = await fetchPage("https://x.test", { fetchImpl: async () => new Response("<p>Hello</p>", { status: 200 }) });
    expect(fine).toEqual({ ok: true, status: 200, text: "Hello" });
  });

  it("gives each URL a distinct, readable snapshot name", () => {
    expect(slug("https://a.test/x")).not.toBe(slug("https://a.test/x?y"));
    expect(slug("https://docs.stripe.com/changelog")).toMatch(/^docs-stripe-com-changelog-[0-9a-f]{8}\.txt$/);
  });
});
