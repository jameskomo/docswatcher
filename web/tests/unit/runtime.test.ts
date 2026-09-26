import { describe, expect, it } from "vitest";
import { ApiError, createOrgApi } from "../../app/utils/orgApi";
import { ago, endpointLabel, fmtCount, ingestEndpoint, noticeLabel, normaliseRuntimeSummary, HOSTED_INGEST } from "../../app/utils/runtime";

const BASE = "https://docswatcher.test/";

function fakeFetch(answers: Record<string, { status: number; body?: unknown }>) {
  const calls: { url: string; init: RequestInit }[] = [];
  const impl = (async (url: string, init: RequestInit) => {
    calls.push({ url, init });
    const a = answers[url.replace(BASE, "")] ?? { status: 404 };
    return new Response(a.body === undefined ? null : JSON.stringify(a.body), { status: a.status });
  }) as unknown as typeof fetch;
  return { impl, calls };
}

describe("runtime helpers", () => {
  it("fills in everything the server leaves out", () => {
    const s = normaliseRuntimeSummary({
      repoId: 7,
      repoFullName: "acme/checkout",
      activeTokens: 1,
      deprecated: [{ host: "api.openai.com", method: "POST", path: "/v1/assistants", totalCalls: 3, callsPerDay: 3, daysObserved: 1, findings: [{ contract: "openai:endpoint:ANY /v1/assistants", change: "c", severity: "breaking" }] }],
      notObserved: [],
    });
    expect(s.lastReportAt).toBeNull();
    expect(s.alsoObserved).toEqual([]);
    expect(s.notObservable).toBe(0);
    expect(s.deprecated[0]!.provider).toBeNull();
    expect(s.deprecated[0]!.sunsetHeader).toBeNull();
    expect(s.deprecated[0]!.findings[0]).toEqual({ contract: "openai:endpoint:ANY /v1/assistants", change: "c", changeTitle: "c", severity: "breaking", effective: null, status: "open" });
  });

  it("writes counts with separators and times in the largest unit", () => {
    expect(fmtCount(0)).toBe("0");
    expect(fmtCount(1204)).toBe("1,204");
    expect(fmtCount(1234567)).toBe("1,234,567");
    const now = Date.parse("2026-09-26T12:00:00Z");
    expect(ago("2026-09-26T11:59:30Z", now)).toBe("just now");
    expect(ago("2026-09-26T11:59:00Z", now)).toBe("1 minute ago");
    expect(ago("2026-09-26T10:00:00Z", now)).toBe("2 hours ago");
    expect(ago("2026-09-19T12:00:00Z", now)).toBe("7 days ago");
    expect(ago(null, now)).toBe("never");
    expect(ago("not a date", now)).toBe("never");
  });

  it("names an endpoint and the provider's notice the way the screen shows them", () => {
    expect(endpointLabel({ method: "POST", path: "/v1/assistants" })).toBe("POST /v1/assistants");
    expect(endpointLabel({ method: "ANY", path: "/v1/assistants" })).toBe("/v1/assistants");
    expect(noticeLabel({ deprecationHeader: "true", sunsetHeader: "Wed, 26 Aug 2026 00:00:00 GMT" })).toBe("Sunset: Wed, 26 Aug 2026 00:00:00 GMT · Deprecation: true");
    expect(noticeLabel({ deprecationHeader: null, sunsetHeader: null })).toBeNull();
  });

  it("takes telemetry next to wherever the dashboard is served", () => {
    expect(ingestEndpoint("https://host.test/deep/prefix/")).toBe("https://host.test/deep/prefix/api/runtime/otlp/v1/traces");
    expect(HOSTED_INGEST).toBe("https://docswatcher.vukisha.co.ke/api/runtime/otlp/v1/traces");
  });
});

describe("runtime API", () => {
  it("reads a summary and normalises it", async () => {
    const api = createOrgApi(BASE, fakeFetch({ "api/repos/7/runtime/summary": { status: 200, body: { repoId: 7, repoFullName: "a/b", activeTokens: 0 } } }).impl);
    const s = await api.runtimeSummary(7);
    expect(s.deprecated).toEqual([]);
    expect(s.notObserved).toEqual([]);
  });

  it("creates a token with its label in the body and revokes it with a POST", async () => {
    const created = { token: { id: 3, repoId: 7, prefix: "dwi_abcdef", label: "prod", createdBy: "octo", createdAt: "2026-09-26T00:00:00Z", lastUsedAt: null }, secret: "dwi_secret" };
    const { impl, calls } = fakeFetch({
      "api/repos/7/runtime/tokens": { status: 201, body: created },
      "api/repos/7/runtime/tokens/3/revoke": { status: 204 },
    });
    const api = createOrgApi(BASE, impl);
    expect((await api.createIngestToken(7, "prod")).secret).toBe("dwi_secret");
    expect(calls[0]!.init.method).toBe("POST");
    expect(JSON.parse(calls[0]!.init.body as string)).toEqual({ label: "prod" });
    await api.revokeIngestToken(7, 3);
    expect(calls[1]!.url).toBe(`${BASE}api/repos/7/runtime/tokens/3/revoke`);
    expect(calls[1]!.init.method).toBe("POST");
  });

  it("raises a refusal with its status, so the screen can say it needs write access", async () => {
    const api = createOrgApi(BASE, fakeFetch({ "api/repos/7/runtime/tokens": { status: 403 } }).impl);
    const err = await api.createIngestToken(7, "").catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(403);
  });
});
