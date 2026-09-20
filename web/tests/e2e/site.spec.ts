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
  for (const path of ["./", "./#/calendar", "./#/app", "./#/about"]) {
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
