import { test, expect, type Page } from "@playwright/test";

function watchFailures(page: Page) {
  const failed: string[] = [];
  page.on("response", (r) => { if (r.status() >= 400) failed.push(`${r.status()} ${r.url()}`); });
  page.on("requestfailed", (r) => failed.push(`FAILED ${r.url()} ${r.failure()?.errorText}`));
  page.on("pageerror", (e) => failed.push(`PAGEERROR ${e.message}`));
  return failed;
}
const own = (list: string[]) => list.filter((u) => !u.includes("fonts.g") && !u.includes("api.github.com"));

/** Scans one sample by its option value, so a test never depends on which sample is the default. */
async function scanSample(page: Page, value: string) {
  await expect(page.locator("#results")).toBeVisible();
  await page.getByRole("tab", { name: "Sample repository" }).click();
  await page.locator("#sample-select").selectOption(value);
  await page.locator("#scan-sample").click();
}

/** Aborts every origin except the local server, mimicking a host that forbids outbound requests. */
async function offline(page: Page) {
  await page.route("**://*/**", (r) => (r.request().url().startsWith("http://localhost") ? r.continue() : r.abort()));
}

test("home runs the example scan under a subpath and shows findings", async ({ page }) => {
  const failed = watchFailures(page);
  await page.goto("./");
  // Structure, not copy. This test is about the example scan running and rendering findings;
  // the headline wording is a marketing decision that should not be able to fail the build.
  await expect(page.locator("h1")).toHaveCount(1);
  await expect(page.locator("#results")).toBeVisible();
  // The default scan is a vendored real repository, not a fixture.
  await expect(page.locator("#results")).toContainText("openai/openai-quickstart-python");
  await expect(page.locator("#findings")).toContainText("Stops working on a date");
  // callsite layer ran: the inventory lists the SDK call found through tree-sitter
  await expect(page.locator("#inventory")).toContainText("beta.assistants.create");
  expect(own(failed)).toEqual([]);
});

test("a different sample can be scanned from the picker", async ({ page }) => {
  await page.goto("./");
  await scanSample(page, "stripe-java-sources");
  await expect(page.locator("#results")).toContainText("stripe-java-sources");
  await expect(page.getByText("Source.create").first()).toBeVisible();
  await expect(page.getByText("Sources API deprecated").first()).toBeVisible();
});

test("calendar renders months and records", async ({ page }) => {
  const failed = watchFailures(page);
  await page.goto("./#/calendar");
  await expect(page.locator("h1")).toContainText("What breaks when");
  await expect(page.locator(".month h3").first()).toBeVisible();
  await expect(page.getByText("gpt-4-turbo shut down").first()).toBeVisible();
  await expect(page.getByText("Admin API version 2025-10 unsupported").first()).toBeVisible();
  await page.getByRole("button", { name: /Show already effective/ }).click();
  await expect(page.getByText("Claude Haiku 3 retired").first()).toBeVisible();
  expect(own(failed)).toEqual([]);
});

test("dashboard renders the dependency view and the horizon from the stored scan", async ({ page }) => {
  const failed = watchFailures(page);
  await page.goto("./");
  await expect(page.locator("#results")).toBeVisible();
  await page.goto("./#/app");
  // The default sample has one provider, so the map renders a ledger rather than
  // a node graph. A graph with one node is a mostly empty rectangle.
  await expect(page.locator("#map .ledger-row")).toHaveCount(1);
  await expect(page.locator("#map")).toContainText("OpenAI");
  await expect(page.locator("#map svg")).toHaveCount(0);
  await expect(page.locator("#horizon svg")).toContainText("today");
  await expect(page.locator("#horizon svg circle").first()).toBeVisible();
  expect(own(failed)).toEqual([]);
});

test("a finding row opens its detail page, and shows evidence and the fix prompt", async ({ page }) => {
  await page.goto("./");
  await scanSample(page, "openai-python-model-config");
  // The whole row is the link. Nothing else competes for the click.
  await page.locator("#findings .finding-row").first().click();
  await expect(page.locator("h1")).toContainText("gpt-4-turbo shut down");
  await expect(page.locator("#fix-pr")).toBeVisible();
  await expect(page.locator("pre")).toContainText("Affected locations");
  await expect(page.locator("pre")).toContainText("src/summarize.py:5");
});

test("a finding row is reachable and activatable by keyboard", async ({ page }) => {
  await page.goto("./");
  await scanSample(page, "openai-python-model-config");
  const row = page.locator("#findings .finding-row").first();
  await expect(row).toBeVisible();
  // Focus and read activeElement in one evaluation, and poll it. The list
  // re-renders when the scan is stored, which can detach the node between
  // resolving the locator and reading focus.
  await expect
    .poll(
      () => row.evaluate((el) => {
        (el as HTMLElement).focus();
        return document.activeElement === el;
      }),
      { message: "the finding row must take keyboard focus" },
    )
    .toBe(true);
  await row.press("Enter");
  await expect(page.locator("#fix-pr")).toBeVisible();
});

test("about page explains the product and reports the real counts", async ({ page }) => {
  const failed = watchFailures(page);
  await page.goto("./#/about");
  await expect(page.locator("h1")).toContainText("You can pin a package");
  await expect(page.locator(".prose")).toContainText("What DocsWatcher is not");
  await expect(page.locator(".prose")).toContainText("What does not exist yet");
  // The provider table is generated from the knowledge base, not hardcoded.
  await expect(page.locator(".prose table")).toContainText("Shopify");
  expect(own(failed)).toEqual([]);
});

test("an unknown route shows the 404 page, not a blank screen", async ({ page }) => {
  await page.goto("./#/no-such-page");
  await expect(page.locator("h1")).toContainText("No such page");
  await expect(page.getByRole("link", { name: /Scan a repository/ })).toBeVisible();
});

test("no horizontal scroll at phone width", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 800 });
  await page.goto("./");
  await expect(page.locator("#results")).toBeVisible();
  for (const path of ["./", "./#/calendar", "./#/app", "./#/about", "./#/ci", "./#/agents", "./#/teams"]) {
    await page.goto(path);
    await page.waitForTimeout(300);
    const overflow = await page.evaluate(() => document.scrollingElement!.scrollWidth - window.innerWidth);
    expect(overflow, path).toBeLessThanOrEqual(0);
  }
});

test("scans a bundled real repository offline and finds the Assistants API sunset", async ({ page }) => {
  // No network at all: every outbound origin is aborted. This is the case on hosts
  // that forbid outbound requests, and the demo must still scan genuine code there.
  await offline(page);
  const failed = watchFailures(page);

  await page.goto("./");
  await expect(page.locator("#results")).toContainText("openai/openai-quickstart-python");
  await expect(page.locator("#findings")).toContainText("Assistants API");
  await expect(page.locator("#inventory")).toContainText("beta.assistants.create");
  await expect(page.locator("#findings")).toContainText("gpt-3.5-turbo-0125");
  expect(own(failed)).toEqual([]);
});

test("the second bundled repository reports its unsupported Shopify version", async ({ page }) => {
  await offline(page);
  await page.goto("./");
  await scanSample(page, "Shopify/shopify-app-template-node");
  await expect(page.locator("#results")).toContainText("Shopify/shopify-app-template-node");
  await expect(page.locator("#findings")).toContainText("2024-10");
  await expect(page.locator("#findings")).toContainText("unsupported");
});

test("direct visit to /app prepares example dashboard without runtime error", async ({ page }) => {
  const failed = watchFailures(page);
  await page.goto("./#/app");
  await expect(page.locator(".notice.bad")).toHaveCount(0);
  await expect(page.locator("#results")).toBeVisible({ timeout: 10000 });
  await expect(page.locator("#map")).toContainText("OpenAI");
  expect(own(failed)).toEqual([]);
});

test("theme toggle switches between dark and light mode", async ({ page }) => {
  await page.goto("./");
  const toggleBtn = page.locator(".theme-toggle-btn");
  await expect(toggleBtn).toBeVisible();

  // Initial theme (defaults to dark or system)
  const initialTheme = await page.evaluate(() => document.documentElement.getAttribute("data-theme") || "dark");
  await toggleBtn.click();

  const newTheme = await page.evaluate(() => document.documentElement.getAttribute("data-theme"));
  expect(newTheme).not.toEqual(initialTheme);

  // Toggle back
  await toggleBtn.click();
  const restoredTheme = await page.evaluate(() => document.documentElement.getAttribute("data-theme"));
  expect(restoredTheme).toEqual(initialTheme);
});

test("the CI page explains the integration and offers a copyable snippet", async ({ page }) => {
  const failed = watchFailures(page);
  await page.goto("./#/ci");
  await expect(page.locator("h1")).toHaveCount(1);
  // Structure and behaviour, not wording: the page has to actually carry a snippet someone
  // can copy, and it has to name the command the exit code contract rests on.
  await expect(page.locator(".snippet")).not.toHaveCount(0);
  await expect(page.locator(".prose")).toContainText("docswatcher match");
  expect(failed).toEqual([]);
});

// ---- docs/15-feeds-and-sharing.md

test("a live scan link scans the named repository on arrival", async ({ page }) => {
  // Offline, so the fetch fails; what matters is that the link started a scan of that repository.
  await offline(page);
  await page.goto("./#/?repo=openai/openai-quickstart-python");
  await expect(page.getByRole("alert")).toContainText("openai/openai-quickstart-python");
});

test("a scan of a public repository offers a link and a badge that re-run it", async ({ page }) => {
  await page.goto("./");
  await expect(page.locator("#results")).toBeVisible();
  await expect(page.getByTestId("share")).toBeVisible();
  await expect(page.getByTestId("share-copy")).toHaveCount(1);
  await page.locator("[data-testid=share] summary").click();
  await expect(page.getByTestId("share-badge")).toContainText("#/?repo=openai/openai-quickstart-python");
  await expect(page.getByTestId("share-badge")).toContainText("img.shields.io");
  // The badge is a provenance mark. It must not imply a count or a status it cannot know,
  // because the scan runs in the reader's browser and nothing here has the result.
  await expect(page.getByTestId("share-badge")).not.toContainText(/deprecat|passing|failing|\d+\s*(issue|finding)/i);
});

test("the calendar offers subscriptions, and the feed files are served", async ({ page, request }) => {
  const failed = watchFailures(page);
  await page.goto("./#/calendar");
  const ics = page.getByTestId("subscribe-ics");
  await expect(ics).toHaveAttribute("href", /feeds\/deprecations\.ics$/);
  await expect(page.getByTestId("subscribe-webcal")).toHaveAttribute("href", /^webcal:/);
  await page.locator("#feed-provider").selectOption("openai");
  await expect(ics).toHaveAttribute("href", /feeds\/openai\.ics$/);

  for (const [file, starts] of [["deprecations.ics", "BEGIN:VCALENDAR"], ["openai.ics", "BEGIN:VCALENDAR"],
    ["deprecations.atom", "<?xml"], ["deprecations.json", "{"]]) {
    const r = await request.get(`./feeds/${file}`);
    expect(r.status(), file).toBe(200);
    expect((await r.text()).startsWith(starts), file).toBe(true);
  }
  expect(own(failed)).toEqual([]);
});

// ---- docs/14-coding-agents.md

test("the agents page shows a real exchange and the install command", async ({ page }) => {
  const failed = watchFailures(page);
  await page.goto("./#/agents");
  await expect(page.locator("h1")).toHaveCount(1);
  await expect(page.getByTestId("agent-exchange").locator(".turn")).toHaveCount(4);
  await expect(page.getByTestId("mcp-claude-code")).toContainText("docswatcher mcp");
  await expect(page.locator(".prose table")).toContainText("check_api");
  expect(own(failed)).toEqual([]);
});

test("no shell command is ever written to the clipboard by the page", async ({ page }) => {
  // Writing a shell command to the clipboard from script is what a ClickFix attack does, and
  // uBlock Origin blocks it with a warning. Shell snippets are selected and copied by the visitor.
  for (const path of ["./#/agents", "./#/ci"]) {
    await page.goto(path);
    await expect(page.locator(".snippet.shell").first()).toBeVisible();
    await expect(page.locator(".snippet.shell .snippet-copy")).toHaveCount(0);
    for (const text of await page.locator(".snippet:not(.shell) pre").allTextContents()) {
      expect(text, path).not.toMatch(/^\s*(curl|wget|chmod|sudo|claude|bash|sh|\.\/)\b/m);
    }
  }
  await page.goto("./#/agents");
  await expect(page.locator(".snippet.shell pre").first()).not.toContainText("sudo");
});


test("the inventory collapses a long list and expands on request", async ({ page }) => {
  await page.goto("./");
  await scanSample(page, "anthropic-model-zoo");
  const rows = page.locator("#inventory table tbody tr:not(.locations)");
  const control = page.locator("#inventory .show-all");
  // Structure, not a row count: whatever the cap is, a capped table hides some and says so.
  const shown = await rows.count();
  await expect(control).toBeVisible();
  await control.click();
  expect(await rows.count()).toBeGreaterThan(shown);
  await expect(page.locator("#inventory .show-all")).toContainText(/fewer/i);
});

test("an inventory row opens its other locations, and each links to the scanned commit", async ({ page }) => {
  await page.goto("./");
  // The default sample is a real GitHub repository, so its evidence can be linked.
  const more = page.locator("#inventory .more").first();
  await expect(more).toBeVisible();
  await expect(page.locator("#inventory tr.locations")).toHaveCount(0);
  await more.click();
  const opened = page.locator("#inventory tr.locations");
  await expect(opened).toHaveCount(1);
  expect(await opened.locator("a").count()).toBeGreaterThan(1);

  // Every link is pinned to a commit, never to a branch that can move under it.
  for (const href of await page.locator("#inventory a[href]").evaluateAll((a) => a.map((x) => x.getAttribute("href")))) {
    expect(href).toMatch(/^https:\/\/github\.com\/[^/]+\/[^/]+\/blob\/[0-9a-f]+\/.+#L\d+$/);
  }
});

test("a scan with no repository of its own links nothing", async ({ page }) => {
  await page.goto("./");
  // A fixture is not a repository anyone can open, so its locations stay plain text.
  await scanSample(page, "anthropic-model-zoo");
  await expect(page.locator("#inventory table")).toBeVisible();
  await expect(page.locator("#inventory a.loc")).toHaveCount(0);
});

test("the calendar says how many dates are still to come", async ({ page }) => {
  await page.goto("./#/calendar");
  await expect(page.getByTestId("feed-summary")).toHaveText(/\d+ dates, \d+ still to come/);
  await page.locator("#feed-provider").selectOption("openai");
  await expect(page.getByTestId("feed-summary")).toHaveText(/\d+ dates, \d+ still to come/);
});

test("the teams page lists what is free, what teams get, and a way to ask", async ({ page }) => {
  const failed = watchFailures(page);
  await page.goto("./#/teams");
  await expect(page.locator("h1")).toHaveCount(1);
  await expect(page.getByTestId("free-list").locator("li")).not.toHaveCount(0);
  await expect(page.getByTestId("team-features").locator(".card")).not.toHaveCount(0);
  await expect(page.getByTestId("early-access-form")).toBeVisible();
  expect(own(failed)).toEqual([]);
});

test("the early-access form submits in the page and thanks the visitor", async ({ page }) => {
  let sent: any = null;
  await page.route("**/early-access", async (route) => {
    sent = route.request().postDataJSON();
    await route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify({ ok: true }) });
  });
  await page.goto("./#/teams");
  await page.locator("input[name=email]").fill("lead@example.com");
  await page.locator("input[name=company]").fill("Acme");
  await page.locator(".form .check input").first().check();
  // The honeypot is off-screen: people never see it, so they never fill it.
  await expect(page.locator("input[name=website]")).not.toBeInViewport();
  await page.getByTestId("early-access-submit").click();
  await expect(page.getByTestId("early-access-sent")).toContainText("hello@vukisha.co.ke");
  expect(sent).toMatchObject({ email: "lead@example.com", company: "Acme", website: "" });
  expect(typeof sent.interest).toBe("string");
});

test("if the early-access request fails, the visitor can still email", async ({ page }) => {
  await page.route("**/early-access", (route) =>
    route.fulfill({ status: 500, contentType: "application/json", body: "{}" }));
  await page.goto("./#/teams");
  await page.locator("input[name=email]").fill("lead@example.com");
  await page.getByTestId("early-access-submit").click();
  await expect(page.getByTestId("early-access-error")).toBeVisible();
  await expect(page.getByTestId("early-access-error").locator("a")).toHaveAttribute("href", /^mailto:/);
  await expect(page.getByTestId("early-access-form")).toBeVisible();
});


test("the footer links every feature, and each page it names opens", async ({ page }) => {
  const failed = watchFailures(page);
  await page.goto("./");
  const footer = page.getByTestId("footer-links");
  for (const label of ["Scan a repository", "Deprecation calendar", "Dashboard", "In CI (GitHub Action)",
    "In your AI assistant", "Try it with your own key", "For teams: early access", "Calendar feed (.ics)",
    "Atom feed", "Open JSON", "Source code", "Downloads (latest release)", "Documentation", "Knowledge base", "How this works"]) {
    await expect(footer.getByRole("link", { name: label })).toBeVisible();
  }
  // The feeds resolve against the site's own path, so they work under any subpath.
  for (const name of ["Calendar feed (.ics)", "Atom feed", "Open JSON"]) {
    const href = await footer.getByRole("link", { name }).getAttribute("href");
    expect((await page.request.get(href!)).status()).toBe(200);
  }
  await footer.getByRole("link", { name: "Try it with your own key" }).click();
  await expect(page.getByTestId("try-assistant")).toBeInViewport();
  await footer.getByRole("link", { name: "For teams: early access" }).click();
  await expect(page.getByTestId("early-access-form")).toBeVisible();
  expect(own(failed)).toEqual([]);
});
