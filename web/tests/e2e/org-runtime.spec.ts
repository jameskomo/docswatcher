import { test, expect, type Page, type Route } from "@playwright/test";

/*
 * What actually runs: the dashboard's runtime screen, with the app's API mocked at the network
 * layer in the shapes the Spring app sends (absent fields omitted, as NON_NULL does). acme has two
 * repositories: checkout reports telemetry, docs has never reported.
 */

const day = (offset: number) => new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);
const hoursAgo = (h: number) => new Date(Date.now() - h * 3_600_000).toISOString();

const ME = {
  enabled: true,
  signedIn: true,
  user: { login: "octo", name: "Octo Cat", avatarUrl: null },
  orgs: [{ login: "acme", installationId: 5001, repos: 2 }],
  expiresAt: day(1),
};

// A path carrying markup. It must arrive on screen as text.
const HOSTILE_PATH = "/v1/<img src=x onerror=alert(1)>";

const ASSISTANTS = { contract: "openai:endpoint:ANY /v1/assistants", change: "openai-assistants-api-shutdown-2026", changeTitle: "Assistants API shut down", severity: "breaking", effective: day(40), status: "open" };
const THREADS = { contract: "openai:endpoint:ANY /v1/threads", change: "openai-assistants-api-shutdown-2026", changeTitle: "Assistants API shut down", severity: "breaking", effective: day(40), status: "open" };

const CHECKOUT_SUMMARY = {
  repoId: 9001,
  repoFullName: "acme/checkout",
  lastReportAt: hoursAgo(2),
  activeTokens: 1,
  deprecated: [
    { host: "api.openai.com", method: "POST", path: "/v1/assistants", provider: "openai", contractId: ASSISTANTS.contract, totalCalls: 8428, callsPerDay: 1204, daysObserved: 7, firstSeen: hoursAgo(170), lastSeen: hoursAgo(2), deprecationHeader: "true", sunsetHeader: "Wed, 26 Aug 2026 00:00:00 GMT", findings: [ASSISTANTS] },
    { host: "legacy.example.test", method: "GET", path: HOSTILE_PATH, totalCalls: 12, callsPerDay: 12, daysObserved: 1, lastSeen: hoursAgo(5), sunsetHeader: "Thu, 01 Oct 2026 00:00:00 GMT", findings: [] },
  ],
  alsoObserved: [{ host: "api.openai.com", method: "POST", path: "/v1/chat/completions", provider: "openai", totalCalls: 50, callsPerDay: 50, daysObserved: 1, findings: [] }],
  notObserved: [THREADS],
  notObservable: 1,
};
const DOCS_SUMMARY = { repoId: 9003, repoFullName: "acme/docs", activeTokens: 0, deprecated: [], alsoObserved: [], notObserved: [], notObservable: 0 };

const TOKEN = { id: 1, repoId: 9001, prefix: "dwi_AbCdEf", label: "production collector", createdBy: "octo", createdAt: hoursAgo(48), lastUsedAt: hoursAgo(2) };

interface Mock { posts: { url: string; body: any }[]; tokens: Record<number, object[]> }

async function mockApi(page: Page): Promise<Mock> {
  const mock: Mock = { posts: [], tokens: { 9001: [TOKEN], 9003: [] } };
  const json = (route: Route, body: unknown, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });

  await page.route("**/auth/me", (route) => json(route, ME));
  await page.route("**/api/**", (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname.slice(url.pathname.indexOf("/api/") + 1);
    if (route.request().method() === "POST") {
      mock.posts.push({ url: path, body: route.request().postData() ? route.request().postDataJSON() : null });
      if (path === "api/repos/9001/runtime/tokens") {
        const t = { ...TOKEN, id: 2, prefix: "dwi_NeWtOk", label: route.request().postDataJSON().label || "Ingest token", createdAt: new Date().toISOString(), lastUsedAt: undefined };
        mock.tokens[9001] = [...mock.tokens[9001]!, t];
        return json(route, { token: t, secret: "dwi_NeWtOkEn-secret-value-shown-once" }, 201);
      }
      if (path === "api/repos/9001/runtime/tokens/1/revoke") {
        mock.tokens[9001] = mock.tokens[9001]!.filter((t: any) => t.id !== 1);
        return route.fulfill({ status: 204 });
      }
      if (path === "api/repos/9003/runtime/tokens") return route.fulfill({ status: 403 });
      return route.fulfill({ status: 404 });
    }
    switch (path) {
      case "api/orgs/acme/overview":
        return json(route, { login: "acme", repos: 2, contracts: 5, findingsBySeverity: { breaking: 2 }, nearestEffective: day(40), knowledgeVersion: "2026.09.26" });
      case "api/orgs/acme/repos":
        return json(route, [
          { id: 9001, fullName: "acme/checkout", defaultBranch: "main", lastScannedSha: "abc1234", production: true, openFindings: 2 },
          { id: 9003, fullName: "acme/docs", defaultBranch: "main", lastScannedSha: "0001111", production: true, openFindings: 0 },
        ]);
      case "api/orgs/acme/map":
      case "api/orgs/acme/horizon":
        return json(route, []);
      case "api/repos/9001/findings":
        return json(route, [{ repoId: 9001, repoFullName: "acme/checkout", changeTitle: ASSISTANTS.changeTitle, finding: { id: "f1", contract: ASSISTANTS.contract, change: ASSISTANTS.change, severity: "breaking", effective: ASSISTANTS.effective, daysRemaining: 40, evidence: [], status: "open" } }]);
      case "api/repos/9001/runtime/summary":
        return json(route, CHECKOUT_SUMMARY);
      case "api/repos/9003/runtime/summary":
        return json(route, DOCS_SUMMARY);
      case "api/repos/9001/runtime/tokens":
        return json(route, mock.tokens[9001]);
      case "api/repos/9003/runtime/tokens":
        return json(route, mock.tokens[9003]);
      default:
        return route.fulfill({ status: 404 });
    }
  });
  return mock;
}

test("the runtime screen shows which deprecated calls production made, and what it never makes", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  page.on("dialog", (d) => { errors.push(`dialog: ${d.message()}`); return d.dismiss(); });
  await mockApi(page);
  await page.goto("./#/app");

  const runtime = page.getByTestId("org-runtime");
  await expect(page.getByTestId("runtime-repo")).toHaveValue("9001");
  await expect(page.getByTestId("runtime-last-report")).toContainText("2 hours ago");

  const calls = page.getByTestId("runtime-call");
  await expect(calls).toHaveCount(2);
  const assistants = calls.first();
  await expect(assistants).toContainText("POST /v1/assistants");
  await expect(assistants).toContainText("api.openai.com");
  await expect(assistants).toContainText("OpenAI");
  await expect(assistants).toContainText("8,428");
  await expect(assistants).toContainText("1,204 a day over 7 days");
  await expect(assistants).toContainText("Assistants API shut down");
  await expect(assistants).toContainText("Provider sent Sunset: Wed, 26 Aug 2026 00:00:00 GMT");

  // An announced endpoint with no finding, whose path carries markup: shown, not run.
  await expect(calls.nth(1)).toContainText(HOSTILE_PATH);
  await expect(calls.nth(1)).toContainText("unknown provider");
  await expect(runtime.locator("img")).toHaveCount(0);

  await expect(runtime).toContainText("1 more tracked endpoint was called");
  await expect(page.getByTestId("runtime-unseen-item")).toHaveCount(1);
  await expect(page.getByTestId("runtime-unseen-item")).toContainText("OpenAI Endpoint ANY /v1/threads");
  await expect(runtime).toContainText("1 more finding is on a model or SDK version");

  // Reporting already, so the setup guide starts folded.
  await expect(page.getByTestId("runtime-setup-details")).not.toHaveAttribute("open", "");

  // From a runtime row to the finding it confirms.
  await assistants.getByRole("button", { name: "Show finding" }).click();
  await expect(page.locator("#repo-findings")).toContainText("acme/checkout");
  await expect(page.getByTestId("org-finding")).toHaveCount(1);
  expect(errors).toEqual([]);
});

test("a token is created once, shown once, and revoked", async ({ page }) => {
  const mock = await mockApi(page);
  await page.goto("./#/app");
  await expect(page.getByTestId("runtime-token")).toHaveCount(1);
  await expect(page.getByTestId("runtime-token").first()).toContainText("dwi_AbCdEf…");
  const explainer = page.getByTestId("runtime-token-explainer");
  await expect(explainer).toContainText("the password your OpenTelemetry Collector sends with its data");
  await expect(explainer).toContainText("cannot read this dashboard");

  await page.getByTestId("runtime-token-label").fill("staging collector");
  await page.getByTestId("runtime-create").click();
  await expect(page.getByTestId("runtime-created")).toContainText("It is not shown again");
  await expect(page.getByTestId("runtime-secret")).toContainText("dwi_NeWtOkEn-secret-value-shown-once");
  await expect(page.getByTestId("runtime-token")).toHaveCount(2);
  expect(mock.posts).toContainEqual({ url: "api/repos/9001/runtime/tokens", body: { label: "staging collector" } });

  await page.getByTestId("runtime-token").filter({ hasText: "production collector" }).getByRole("button", { name: "Revoke" }).click();
  await expect(page.getByTestId("runtime-token")).toHaveCount(1);
  expect(mock.posts.map((p) => p.url)).toContain("api/repos/9001/runtime/tokens/1/revoke");

  // The secret belongs to the repository it was made for; switching away forgets it.
  await page.getByTestId("runtime-repo").selectOption("9003");
  await expect(page.getByTestId("runtime-created")).toHaveCount(0);
});

test("a repository that has never reported gets the setup guide, pointed at this deployment", async ({ page }) => {
  await mockApi(page);
  await page.goto("./#/app");
  await page.getByTestId("runtime-repo").selectOption("9003");

  await expect(page.getByTestId("runtime-last-report")).toContainText("No telemetry has arrived for acme/docs yet");
  // Without a report, silence proves nothing, so no finding is called unobserved.
  await expect(page.getByTestId("runtime-unseen")).toHaveCount(0);
  await expect(page.getByTestId("runtime-setup-details")).toHaveAttribute("open", "");
  const config = page.getByTestId("runtime-collector-config");
  await expect(config).toContainText(/traces_endpoint: http:\/\/localhost:\d+\/some\/deep\/prefix\/api\/runtime\/otlp\/v1\/traces/);
  await expect(config).toContainText("Authorization: Bearer ${env:DOCSWATCHER_INGEST_TOKEN}");
  await expect(config).toContainText("encoding: json");
  await expect(page.getByTestId("runtime-sdk-java")).toContainText("OTEL_INSTRUMENTATION_HTTP_CLIENT_CAPTURE_RESPONSE_HEADERS=deprecation,sunset");

  // Read access only: the app answers 403 and the screen says why.
  await page.getByTestId("runtime-create").click();
  await expect(page.getByTestId("runtime-token-problem")).toHaveText("You need write access to acme/docs to create a token.");
});

test("the teams page carries the setup guide, pointed at the hosted service", async ({ page }) => {
  await page.goto("./#/teams");
  const guide = page.getByTestId("runtime-guide");
  await expect(guide).toBeVisible();
  await expect(guide.getByTestId("runtime-collector-config")).toContainText("traces_endpoint: https://docswatcher.vukisha.co.ke/api/runtime/otlp/v1/traces");
  await expect(guide.getByTestId("runtime-sdk-python")).toContainText("OTEL_INSTRUMENTATION_HTTP_CAPTURE_HEADERS_CLIENT_RESPONSE=deprecation,sunset");
  await expect(guide.getByTestId("runtime-sdk-node")).toContainText("OTEL_EXPORTER_OTLP_ENDPOINT");
  await expect(guide.getByTestId("runtime-privacy")).toContainText("request or response bodies");
});

test("no horizontal scroll on the runtime screen at phone width", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 800 });
  await mockApi(page);
  await page.goto("./#/app");
  await expect(page.getByTestId("runtime-call")).toHaveCount(2);
  await page.getByTestId("runtime-repo").selectOption("9003");
  await expect(page.getByTestId("runtime-collector-config")).toBeVisible();
  const overflow = await page.evaluate(() => document.scrollingElement!.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(0);

  await page.goto("./#/teams");
  await expect(page.getByTestId("runtime-guide")).toBeVisible();
  const teamsOverflow = await page.evaluate(() => document.scrollingElement!.scrollWidth - window.innerWidth);
  expect(teamsOverflow).toBeLessThanOrEqual(0);
});
