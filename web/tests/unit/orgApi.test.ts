import { describe, expect, it } from "vitest";
import {
  ApiError, changesIn, createOrgApi, normaliseFinding, openFindings, orgFinding, parseThresholds, providerRows, siteBase, splitEmails,
  type HorizonMonth, type RepoFinding,
} from "../../app/utils/orgApi";

const BASE = "https://docswatcher.test/";

function finding(repoId: number, contract: string, change: string, severity: "breaking" | "warning" | "info", status = "open", effective: string | null = "2026-10-23"): RepoFinding {
  return {
    repoId,
    repoFullName: `acme/r${repoId}`,
    changeTitle: `${change} title`,
    finding: normaliseFinding({ id: `${contract}|${change}`, contract, change, severity, effective, status: status as "open" }),
  };
}

/** A fetch that records what it was asked and answers from a table. */
function fakeFetch(answers: Record<string, { status: number; body?: unknown }>) {
  const calls: { url: string; init: RequestInit }[] = [];
  const impl = (async (url: string, init: RequestInit) => {
    calls.push({ url, init });
    const a = answers[url.replace(BASE, "")] ?? { status: 404 };
    return new Response(a.body === undefined ? null : JSON.stringify(a.body), { status: a.status });
  }) as unknown as typeof fetch;
  return { impl, calls };
}

describe("orgApi helpers", () => {
  it("fills in what the server omits", () => {
    const f = normaliseFinding({ id: "x", contract: "openai:model:gpt-4", change: "c", severity: "breaking" });
    expect(f).toEqual({ id: "x", contract: "openai:model:gpt-4", change: "c", severity: "breaking", effective: null, daysRemaining: null, evidence: [], status: "open", snoozedUntil: null, fixPr: null });
  });

  it("gives the same finding in two repositories two ids", () => {
    const a = orgFinding(finding(1, "openai:model:gpt-4", "c", "breaking"));
    const b = orgFinding(finding(2, "openai:model:gpt-4", "c", "breaking"));
    expect(a.id).not.toEqual(b.id);
  });

  it("splits each provider's contracts by the worst open severity, the rest healthy", () => {
    const open = [
      finding(1, "openai:model:gpt-4", "c1", "warning"),
      finding(1, "openai:model:gpt-4", "c2", "breaking"),
      finding(2, "openai:model:gpt-4", "c1", "warning"),
      finding(2, "stripe:endpoint:POST /v1/sources", "s", "info"),
    ];
    const rows = providerRows(
      [
        { provider: "openai", contracts: 5, evidence: 9, worstSeverity: "breaking", openFindings: 3 },
        { provider: "stripe", contracts: 2, evidence: 2, worstSeverity: "info", openFindings: 1 },
        { provider: "anthropic", contracts: 1, evidence: 1, worstSeverity: "healthy", openFindings: 0 },
      ],
      open,
    );
    expect(rows.map((r) => r.id)).toEqual(["openai", "stripe", "anthropic"]);
    expect(rows[0]!.split).toEqual({ breaking: 1, warning: 1, info: 0, healthy: 3 });
    expect(rows[0]!.callSites).toBe(9);
    expect(rows[1]!.split).toEqual({ breaking: 0, warning: 0, info: 1, healthy: 1 });
    expect(rows[2]!.split.healthy).toBe(1);
  });

  it("lists the changes in an organisation nearest first, counting repositories once", () => {
    const months: HorizonMonth[] = [
      { month: "2026-10", findings: [finding(1, "openai:model:a", "late", "breaking", "open", "2026-12-01"), finding(2, "openai:model:a", "late", "breaking")] },
      { month: "2026-11", findings: [finding(1, "openai:model:b", "soon", "warning", "snoozed", "2026-11-01"), finding(1, "openai:model:c", "soon", "warning", "open", "2026-11-01")] },
      { month: "undated", findings: [finding(3, "openai:model:d", "someday", "info", "open", null)] },
    ];
    expect(changesIn(months).map((c) => [c.id, c.repos])).toEqual([["soon", 1], ["late", 2], ["someday", 1]]);
    expect(openFindings(months)).toHaveLength(4);
  });

  it("resolves the API against the directory the page was served from", () => {
    expect(siteBase("https://docswatcher.test/#/app")).toBe("https://docswatcher.test/");
    expect(siteBase("https://host.test/deep/prefix/index.html#/app?signin=failed")).toBe("https://host.test/deep/prefix/");
  });
});

describe("createOrgApi", () => {
  it("reads a signed-out answer, a missing app and a broken answer all as signed out", async () => {
    const out = createOrgApi(BASE, fakeFetch({ "auth/me": { status: 200, body: { enabled: true, signedIn: false } } }).impl);
    expect(await out.me()).toEqual({ enabled: true, signedIn: false });
    const none = createOrgApi(BASE, fakeFetch({}).impl);
    expect(await none.me()).toEqual({ enabled: false, signedIn: false });
    const odd = createOrgApi(BASE, fakeFetch({ "auth/me": { status: 200, body: "<html>" } }).impl);
    expect(await odd.me()).toEqual({ enabled: false, signedIn: false });
  });

  it("encodes the organisation and change, sends cookies same-origin, and normalises findings", async () => {
    const { impl, calls } = fakeFetch({
      "api/orgs/acme%20co/blast-radius/openai-gpt-4%2Fturbo": {
        status: 200,
        body: { changeId: "x", title: "t", repos: 1, findings: [{ repoId: 1, repoFullName: "a/b", changeTitle: "t", finding: { id: "i", contract: "openai:model:gpt-4", change: "x", severity: "breaking" } }] },
      },
    });
    const api = createOrgApi(BASE, impl);
    const b = await api.blastRadius("acme co", "openai-gpt-4/turbo");
    expect(calls[0]!.init.credentials).toBe("same-origin");
    expect(b.effective).toBeNull();
    expect(b.findings[0]!.finding.evidence).toEqual([]);
  });

  it("names a finding in the body, so a contract with a slash survives", async () => {
    const { impl, calls } = fakeFetch({ "api/repos/9/findings/snooze": { status: 200, body: { status: "snoozed", detail: "2026-10-24" } } });
    const api = createOrgApi(BASE, impl);
    await api.act(9, "snooze", { contract: "stripe:endpoint:POST /v1/sources", change: "c" }, 30);
    expect(calls[0]!.init.method).toBe("POST");
    expect(JSON.parse(calls[0]!.init.body as string)).toEqual({ contract: "stripe:endpoint:POST /v1/sources", change: "c", days: 30 });
    expect((calls[0]!.init.headers as Record<string, string>)["Content-Type"]).toBe("application/json");
  });

  it("raises the status of a refused call", async () => {
    const api = createOrgApi(BASE, fakeFetch({ "api/repos/9/findings/fix": { status: 403 } }).impl);
    const err = await api.act(9, "fix", { contract: "a:b:c", change: "d" }).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(403);
  });

  it("reads and saves an organisation's alerts by POST, and carries the server's explanation of a refusal", async () => {
    const settings = { login: "acme", enabled: true, emails: ["ops@acme.test"], slack: { configured: false }, thresholds: [30, 7], canEdit: true, emailAvailable: true };
    const { impl, calls } = fakeFetch({
      "api/orgs/acme%2Fx/alerts": { status: 200, body: settings },
      "api/orgs/globex/alerts": { status: 400, body: { error: "Not an email address: x" } },
      "api/orgs/acme%2Fx/alerts/test": { status: 200, body: { emails: 1, slackMessages: 0, failures: 0 } },
    });
    const api = createOrgApi(BASE, impl);
    expect(await api.alerts("acme/x")).toEqual(settings);
    await api.saveAlerts("acme/x", { enabled: true, emails: ["a@b.test"], thresholds: [30, 7], slackWebhook: "" });
    expect(calls[1]!.init.method).toBe("POST");
    expect(JSON.parse(String(calls[1]!.init.body))).toEqual({ enabled: true, emails: ["a@b.test"], thresholds: [30, 7], slackWebhook: "" });
    expect((await api.testAlerts("acme/x")).emails).toBe(1);
    const err = await api.saveAlerts("globex", { enabled: true, emails: ["x"], thresholds: [7] }).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(400);
    expect(err.detail).toBe("Not an email address: x");
  });

  it("splits typed addresses and days", () => {
    expect(splitEmails(" a@b.test,\nc@d.test; a@b.test  ")).toEqual(["a@b.test", "c@d.test"]);
    expect(splitEmails("")).toEqual([]);
    expect(parseThresholds("30, 7")).toEqual([30, 7]);
    expect(parseThresholds("30 days, 1.5, 7")).toEqual([30, 7]);
  });

  it("points sign-in at the app beside the site", () => {
    expect(createOrgApi("https://host.test/deep/", fakeFetch({}).impl).loginUrl()).toBe("https://host.test/deep/auth/github/login");
  });
});
