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
  await expect(page.locator("h1")).toContainText("expiry date");
  await expect(page.locator("#results")).toBeVisible();
  // The default scan is a vendored real repository, not a fixture.
  await expect(page.locator("#results")).toContainText("openai/openai-quickstart-python");
  await expect(page.locator("#findings")).toContainText("Breaking · act before a date");
  // callsite layer ran: the inventory lists the SDK call found through tree-sitter
  await expect(page.locator("#inventory")).toContainText("beta.assistants.create");
  expect(own(failed)).toEqual([]);
});

test("a different sample can be scanned from the picker", async ({ page }) => {
  await page.goto("./");
  await scanSample(page, "stripe-java-sources");
  await expect(page.getByText("Example · stripe-java-sources")).toBeVisible();
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

test("dashboard renders the map and the horizon from the stored scan", async ({ page }) => {
  const failed = watchFailures(page);
  await page.goto("./");
  await expect(page.locator("#results")).toBeVisible();
  await page.goto("./#/app");
  await expect(page.locator("#map svg circle").first()).toBeVisible();
  await expect(page.locator("#map svg")).toContainText("your repo");
  await expect(page.locator("#map svg")).toContainText("OpenAI");
  await expect(page.locator("#horizon svg")).toContainText("today");
  await expect(page.locator("#horizon svg circle").first()).toBeVisible();
  expect(own(failed)).toEqual([]);
});

test("finding detail shows evidence and the fix prompt", async ({ page }) => {
  await page.goto("./");
  await scanSample(page, "openai-python-model-config");
  await page.getByRole("link", { name: "Open fix" }).first().click();
  await expect(page.locator("h1")).toContainText("gpt-4-turbo shut down");
  await expect(page.locator("#fix-pr")).toBeVisible();
  await expect(page.locator("pre")).toContainText("Affected locations");
  await expect(page.locator("pre")).toContainText("src/summarize.py:5");
});

test("no horizontal scroll at phone width", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 800 });
  await page.goto("./");
  await expect(page.locator("#results")).toBeVisible();
  for (const path of ["./", "./#/calendar", "./#/app"]) {
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
