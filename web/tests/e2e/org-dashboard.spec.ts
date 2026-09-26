import { test, expect, type Page, type Route } from "@playwright/test";

/*
 * The organisation dashboard, with the app's API mocked at the network layer. The static export
 * has no app behind it, so every answer here comes from page.route, in the shapes the Spring app
 * sends (absent fields omitted, as its NON_NULL serialisation does).
 */

const day = (offset: number) => new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);
const SOON = day(40);
const LATER = day(150);

const ME_IN = {
  enabled: true,
  signedIn: true,
  user: { login: "octo", name: "Octo Cat", avatarUrl: null },
  orgs: [{ login: "acme", installationId: 5001, repos: 3 }, { login: "globex", installationId: 6001, repos: 1 }],
  expiresAt: day(1),
};
const ME_OUT = { enabled: true, signedIn: false };

const evidence = [{ path: "app/models.py", line: 12, column: 9, snippet: "model='gpt-4-turbo'", detector: "d", layer: "literal" }];
const f = (repoId: number, repo: string, contract: string, change: string, title: string, severity: string, effective: string | null, status = "open") => ({
  repoId,
  repoFullName: repo,
  changeTitle: title,
  finding: { id: `${repo}:${change}:${contract}`, contract, change, severity, ...(effective ? { effective, daysRemaining: 40 } : {}), evidence, status },
});

// The second repository's name carries markup. It must arrive on screen as text.
const HOSTILE = "acme/<img src=x onerror=alert(1)>";
const GPT = f(9001, "acme/checkout", "openai:model:gpt-4-turbo", "openai-gpt-4-turbo-shutdown", "gpt-4-turbo shut down", "breaking", SOON);
const GPT2 = f(9002, HOSTILE, "openai:model:gpt-4-turbo", "openai-gpt-4-turbo-shutdown", "gpt-4-turbo shut down", "breaking", SOON);
const STRIPE = f(9001, "acme/checkout", "stripe:endpoint:POST /v1/sources", "stripe-sources-deprecated", "Sources API deprecated", "warning", LATER);

const ALERTS = {
  login: "acme", enabled: true, emails: ["ops@acme.test"], slack: { configured: true, hint: "…wxyz" },
  thresholds: [30, 7], canEdit: true, emailAvailable: true, updatedBy: "octo",
};

interface Mock { me: object; posts: { url: string; body: any }[]; seen: string[] }

async function mockApi(page: Page, me: object): Promise<Mock> {
  const mock: Mock = { me, posts: [], seen: [] };
  const json = (route: Route, body: unknown, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });

  await page.route("**/auth/me", (route) => json(route, mock.me));
  await page.route("**/auth/logout", (route) => {
    mock.posts.push({ url: "auth/logout", body: null });
    mock.me = ME_OUT;
    return route.fulfill({ status: 204 });
  });
  await page.route("**/api/**", (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname.slice(url.pathname.indexOf("/api/") + 1);
    mock.seen.push(path);
    if (route.request().method() === "POST") {
      mock.posts.push({ url: path, body: route.request().postDataJSON() });
      if (path === "api/repos/9002/findings/fix") return route.fulfill({ status: 403 });
      if (path === "api/orgs/acme/alerts") {
        const body = route.request().postDataJSON();
        if (body.emails.includes("bad")) return json(route, { error: "Not an email address: bad" }, 400);
        return json(route, { ...ALERTS, enabled: body.emails.length > 0, emails: body.emails, thresholds: body.thresholds,
          slack: body.slackWebhook === "" ? { configured: false } : ALERTS.slack });
      }
      if (path === "api/orgs/acme/alerts/test") return json(route, { emails: 1, slackMessages: 1, failures: 0 });
      if (path === "api/gitlab/connections") {
        return json(route, {
          id: -1, login: "acme", kind: "group", namespace: "acme", projects: 2, tokenExpiresAt: "2027-03-01", connectedBy: "gitlab:tanuki",
          createdAt: "2026-09-26T10:00:00Z", webhookUrl: "https://docswatcher.test/webhooks/gitlab", webhookToken: "hook-secret-shown-once",
        }, 201);
      }
      return json(route, { status: path.endsWith("snooze") ? "snoozed" : "ok", detail: null });
    }
    switch (path) {
      case "api/orgs/acme/overview":
        return json(route, { login: "acme", repos: 3, contracts: 7, findingsBySeverity: { breaking: 2, warning: 1 }, nearestEffective: SOON, knowledgeVersion: "2026.09.20" });
      case "api/orgs/acme/repos":
        return json(route, [
          { id: 9001, fullName: "acme/checkout", defaultBranch: "main", lastScannedSha: "abc1234def", production: true, openFindings: 2 },
          { id: 9002, fullName: HOSTILE, defaultBranch: "main", lastScannedSha: "def5678abc", production: true, openFindings: 1 },
          { id: 9003, fullName: "acme/docs", defaultBranch: "main", lastScannedSha: "0001111", production: false, openFindings: 0 },
        ]);
      case "api/orgs/acme/map":
        return json(route, [
          { provider: "openai", contracts: 4, evidence: 6, worstSeverity: "breaking", openFindings: 2 },
          { provider: "stripe", contracts: 3, evidence: 3, worstSeverity: "warning", openFindings: 1 },
        ]);
      case "api/orgs/acme/horizon":
        return json(route, [{ month: SOON.slice(0, 7), findings: [GPT, GPT2] }, { month: LATER.slice(0, 7), findings: [STRIPE] }]);
      case "api/orgs/acme/blast-radius/openai-gpt-4-turbo-shutdown":
        return json(route, { changeId: "openai-gpt-4-turbo-shutdown", title: "gpt-4-turbo shut down", effective: SOON, repos: 2, findings: [GPT, GPT2] });
      case "api/repos/9001/findings":
        return json(route, [GPT, STRIPE]);
      case "api/repos/9002/findings":
        return json(route, [GPT2]);
      case "api/orgs/acme/alerts":
        return json(route, ALERTS);
      case "api/orgs/globex/alerts":
        return json(route, { ...ALERTS, login: "globex", emails: [], slack: { configured: false }, canEdit: false, updatedBy: null });
      case "api/orgs/globex/overview":
        return json(route, { login: "globex", repos: 1, contracts: 1, findingsBySeverity: {}, knowledgeVersion: "2026.09.20" });
      case "api/orgs/globex/repos":
      case "api/orgs/globex/map":
      case "api/orgs/globex/horizon":
        return json(route, []);
      default:
        return route.fulfill({ status: 404 });
    }
  });
  return mock;
}

test("signed in, the dashboard shows the organisation: numbers, map, horizon, repositories and blast radius", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  page.on("dialog", (d) => { errors.push(`dialog: ${d.message()}`); return d.dismiss(); });
  await mockApi(page, ME_IN);
  await page.goto("./#/app");

  await expect(page.getByTestId("org-dashboard")).toBeVisible();
  await expect(page.getByTestId("signed-in-as")).toHaveText("octo");
  await expect(page.getByTestId("stat-repos")).toHaveText("3");
  await expect(page.getByTestId("stat-breaking")).toHaveText("2");
  await expect(page.getByTestId("stat-warning")).toHaveText("1");

  await expect(page.locator("#org-map")).toContainText("OpenAI");
  await expect(page.locator("#org-map")).toContainText("Stripe");
  await expect(page.locator("#org-horizon svg circle").first()).toBeVisible();

  await expect(page.getByTestId("org-repo")).toHaveCount(2);
  await expect(page.locator("#org-repos")).toContainText("1 more repository has nothing open");
  // Markup in server data is shown, not run.
  await expect(page.locator("#org-repos")).toContainText(HOSTILE);
  await expect(page.locator("#org-repos img")).toHaveCount(0);

  await expect(page.getByTestId("change-select")).toBeVisible();
  await expect(page.getByTestId("blast-count")).toHaveText("2 repositories");
  await expect(page.getByTestId("blast")).toContainText("acme/checkout");
  await expect(page.getByTestId("blast")).toContainText("app/models.py:12");

  // The browser-scan dashboard is not what a signed-in person sees.
  await expect(page.getByTestId("signin-cta")).toHaveCount(0);
  expect(errors).toEqual([]);
});

test("finding actions post the finding in the body and report the outcome on the row", async ({ page }) => {
  const mock = await mockApi(page, ME_IN);
  await page.goto("./#/app");
  await page.getByTestId("org-repo").first().getByRole("button", { name: "Show findings" }).click();
  const rows = page.getByTestId("org-finding");
  await expect(rows).toHaveCount(2);

  const stripe = rows.filter({ hasText: "Sources API deprecated" });
  await stripe.getByRole("button", { name: "Snooze 30 days" }).click();
  await expect(stripe.getByTestId("org-finding-outcome")).toHaveText("Snoozed for 30 days.");
  expect(mock.posts).toContainEqual({
    url: "api/repos/9001/findings/snooze",
    body: { contract: "stripe:endpoint:POST /v1/sources", change: "stripe-sources-deprecated", days: 30 },
  });

  await rows.first().getByRole("button", { name: "Not in production" }).click();
  await expect.poll(() => mock.posts.map((p) => p.url)).toContain("api/repos/9001/findings/not-in-prod");

  // Without write access the app answers 403, and the row says why.
  await page.getByTestId("org-repo").nth(1).getByRole("button", { name: "Show findings" }).click();
  await page.getByTestId("org-finding").first().getByRole("button", { name: "Request a fix" }).click();
  await expect(page.getByTestId("org-finding-outcome")).toContainText("You need write access to");
});

test("switching organisation loads the other one", async ({ page }) => {
  const mock = await mockApi(page, ME_IN);
  await page.goto("./#/app");
  await expect(page.getByTestId("stat-repos")).toHaveText("3");
  await page.getByTestId("org-select").selectOption("globex");
  await expect(page.getByTestId("stat-repos")).toHaveText("1");
  expect(mock.seen).toContain("api/orgs/globex/overview");
  await expect(page.locator("#org-repos")).toContainText("Nothing open");
});

test("signing out returns to the browser-scan dashboard with the invitation", async ({ page }) => {
  const mock = await mockApi(page, ME_IN);
  await page.goto("./#/app");
  await expect(page.getByTestId("org-dashboard")).toBeVisible();
  await page.getByTestId("sign-out").click();
  await expect(page.getByTestId("signin-cta")).toBeVisible();
  await expect(page.getByTestId("org-dashboard")).toHaveCount(0);
  expect(mock.posts.map((p) => p.url)).toContain("auth/logout");
});

test("signed out, the dashboard is the browser scan plus a sign-in invitation", async ({ page }) => {
  const mock = await mockApi(page, ME_OUT);
  await page.goto("./#/app");
  const cta = page.getByTestId("signin-cta");
  await expect(cta).toBeVisible();
  const link = cta.getByRole("link", { name: "Sign in with GitHub to see your organisation" });
  await expect(link).toHaveAttribute("href", /\/some\/deep\/prefix\/auth\/github\/login$/);
  await expect(page.locator("#results")).toBeVisible({ timeout: 10_000 });
  await expect(page.locator("#map")).toContainText("OpenAI");
  await expect(page.getByTestId("org-dashboard")).toHaveCount(0);
  expect(mock.seen).toEqual([]);
});

test("a failed sign-in says so", async ({ page }) => {
  await mockApi(page, ME_OUT);
  await page.goto("./#/app?signin=failed");
  await expect(page.getByTestId("signin-problem")).toContainText("did not complete");
  await expect(page.getByTestId("signin-cta")).toBeVisible();
});

test("a site with no app behind it offers no sign-in", async ({ page }) => {
  await page.route("**/auth/me", (route) => route.fulfill({ status: 404, body: "not found" }));
  await page.goto("./#/app");
  await expect(page.locator("#results")).toBeVisible({ timeout: 10_000 });
  await expect(page.getByTestId("signin-cta")).toHaveCount(0);
});

test("signed in with no organisation installed, the dashboard explains", async ({ page }) => {
  await mockApi(page, { ...ME_IN, orgs: [] });
  await page.goto("./#/app");
  await expect(page.getByTestId("no-orgs")).toBeVisible();
});

test("no horizontal scroll on the organisation dashboard at phone width", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 800 });
  await mockApi(page, ME_IN);
  await page.goto("./#/app");
  await expect(page.getByTestId("blast-count")).toBeVisible();
  await page.getByTestId("org-repo").first().getByRole("button", { name: "Show findings" }).click();
  await expect(page.getByTestId("org-finding").first()).toBeVisible();
  const overflow = await page.evaluate(() => document.scrollingElement!.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(0);
});

test("alerts: a writer sees and saves the organisation's settings; the Slack webhook is never shown", async ({ page }) => {
  const mock = await mockApi(page, ME_IN);
  await page.goto("./#/app");
  const alerts = page.getByTestId("org-alerts");
  await expect(alerts).toContainText("Warned before the date");
  await expect(page.getByTestId("alerts-emails")).toHaveValue("ops@acme.test");
  await expect(page.getByTestId("alerts-thresholds")).toHaveValue("30, 7");
  await expect(page.getByTestId("alerts-slack-set")).toContainText("…wxyz");
  await expect(page.getByTestId("alerts-slack")).toHaveValue("");

  // Saved channels: the test is on offer, with nothing to explain until something is typed.
  await expect(page.getByTestId("alerts-test")).toBeEnabled();
  await expect(page.getByTestId("alerts-test-hint")).toHaveCount(0);

  // A refusal shows the server's reason, as text.
  await page.getByTestId("alerts-emails").fill("bad");
  await page.getByTestId("alerts-save").click();
  await expect(page.getByTestId("alerts-outcome")).toHaveText("Not an email address: bad");

  await page.getByTestId("alerts-emails").fill("ops@acme.test\nlead@acme.test, ops@acme.test");
  await expect(page.getByTestId("alerts-test-hint")).toContainText("not the changes above until you save");
  await page.getByTestId("alerts-thresholds").fill("14, 3");
  await page.getByTestId("alerts-save").click();
  await expect(page.getByTestId("alerts-outcome")).toHaveText("Saved.");
  // A blank webhook field is left out, so the saved one is kept.
  expect(mock.posts.at(-1)).toEqual({ url: "api/orgs/acme/alerts", body: { enabled: true, emails: ["ops@acme.test", "lead@acme.test"], thresholds: [14, 3] } });

  await page.getByTestId("alerts-slack-remove").check();
  await page.getByTestId("alerts-save").click();
  await expect(page.getByTestId("alerts-outcome")).toHaveText("Saved.");
  expect(mock.posts.at(-1)!.body.slackWebhook).toBe("");
  await expect(page.getByTestId("alerts-slack-set")).toHaveCount(0);

  await page.getByTestId("alerts-test").click();
  await expect(page.getByTestId("alerts-outcome")).toHaveText("Sent 1 email and a Slack message.");
});

test("alerts: with nothing saved, the grey test button says why", async ({ page }) => {
  await mockApi(page, ME_IN);
  await page.route("**/api/orgs/acme/alerts", (route) => route.request().method() === "GET"
    ? route.fulfill({ json: { login: "acme", enabled: true, emails: [], slack: { configured: false }, thresholds: [30, 7], canEdit: true, emailAvailable: true, updatedBy: null } })
    : route.fallback());
  await page.goto("./#/app");
  await expect(page.getByTestId("alerts-test")).toBeDisabled();
  await expect(page.getByTestId("alerts-test-hint")).toHaveText("Add an email address or a Slack webhook and save, then send a test.");
  await page.getByTestId("alerts-emails").fill("ops@acme.test");
  await expect(page.getByTestId("alerts-test-hint")).toHaveText("Save alerts first: the test goes to the saved addresses and webhook.");
});

test("alerts: someone without write access sees the settings read-only", async ({ page }) => {
  await mockApi(page, ME_IN);
  await page.goto("./#/app");
  await page.getByTestId("org-select").selectOption("globex");
  await expect(page.getByTestId("alerts-readonly")).toBeVisible();
  await expect(page.getByTestId("alerts-emails")).toBeDisabled();
  await expect(page.getByTestId("alerts-save")).toHaveCount(0);
});

test("signed out on a deployment with both sign-ins, both are offered", async ({ page }) => {
  await mockApi(page, { ...ME_OUT, providers: { github: true, gitlab: true } });
  await page.goto("./#/app");
  const cta = page.getByTestId("signin-cta");
  await expect(cta.getByTestId("signin-github")).toHaveAttribute("href", /\/auth\/github\/login$/);
  await expect(cta.getByTestId("signin-gitlab")).toHaveAttribute("href", /\/auth\/gitlab\/login$/);
  await expect(cta).toContainText("Sign in with GitHub or GitLab");
});

test("signed in with GitLab and nothing connected, a maintainer connects a group and sees the webhook once", async ({ page }) => {
  const mock = await mockApi(page, { ...ME_IN, provider: "gitlab", user: { login: "tanuki", name: null, avatarUrl: null }, orgs: [] });
  await page.goto("./#/app");
  await expect(page.getByTestId("no-orgs")).toContainText("GitLab groups you belong to");
  const panel = page.getByTestId("gitlab-connect");
  await expect(panel).toBeVisible();
  await panel.getByLabel("Group or project path").fill("acme");
  await panel.getByLabel("Access token").fill("glpat-secret");
  await panel.getByRole("button", { name: "Connect" }).click();
  await expect(page.getByTestId("gitlab-connected")).toContainText("acme is connected, with 2 projects");
  await expect(page.getByTestId("gitlab-webhook-url")).toHaveText("https://docswatcher.test/webhooks/gitlab");
  await expect(page.getByTestId("gitlab-webhook-token")).toHaveText("hook-secret-shown-once");
  // The page keeps no copy of the access token once it is sent.
  await expect(panel.getByLabel("Access token")).toHaveValue("");
  expect(mock.posts).toContainEqual({ url: "api/gitlab/connections", body: { namespace: "acme", token: "glpat-secret" } });

  // The webhook details fit a phone without scrolling sideways.
  await page.setViewportSize({ width: 375, height: 800 });
  const overflow = await page.evaluate(() => document.scrollingElement!.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(0);
});

test("signed in with GitHub, there is no GitLab connect panel", async ({ page }) => {
  await mockApi(page, ME_IN);
  await page.goto("./#/app");
  await expect(page.getByTestId("org-dashboard")).toBeVisible();
  await expect(page.getByTestId("gitlab-connect")).toHaveCount(0);
});
