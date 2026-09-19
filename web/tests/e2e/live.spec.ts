import { test, expect } from "@playwright/test";

// Smoke tests against a deployed site. Skipped unless LIVE_URL is set:
//   LIVE_URL=https://docswatcher.vukisha.co.ke npx playwright test live
const BASE = process.env.LIVE_URL ?? "";
test.skip(!BASE, "set LIVE_URL to smoke test a deployment");

test("deployed site renders and scans a bundled repository", async ({ page }) => {
  test.setTimeout(120_000);
  const bad: string[] = [];
  page.on("response", (r) => { if (r.status() >= 400 && r.url().startsWith(BASE)) bad.push(`${r.status()} ${r.url()}`); });
  page.on("pageerror", (e) => bad.push(`PAGEERROR ${e.message}`));

  await page.goto(BASE, { waitUntil: "networkidle" });
  await expect(page.locator("h1")).toContainText("expiry date");
  await expect(page.locator("#results")).toContainText("openai/openai-quickstart-python");
  await expect(page.locator("#findings")).toContainText("Assistants API");
  await expect(page.locator("#inventory")).toContainText("beta.assistants.create");
  expect(bad).toEqual([]);
});

test("deployed site scans a real repository by URL", async ({ page }) => {
  test.setTimeout(180_000);
  await page.goto(BASE, { waitUntil: "networkidle" });
  await page.getByRole("tab", { name: /GitHub URL/i }).click();
  await page.getByPlaceholder(/github\.com/i).fill("https://github.com/openai/openai-quickstart-python");
  await page.getByRole("button", { name: /scan repository/i }).click();
  await expect(page.locator("#findings")).toContainText("Assistants API", { timeout: 120_000 });
});

test("calendar and dashboard render on the deployed site", async ({ page }) => {
  await page.goto(`${BASE}/#/calendar`, { waitUntil: "networkidle" });
  await expect(page.locator("h1")).toContainText("What breaks when");
  await page.goto(BASE, { waitUntil: "networkidle" });
  await expect(page.locator("#results")).toBeVisible();
  await page.goto(`${BASE}/#/app`, { waitUntil: "networkidle" });
  await expect(page.locator("#map svg circle").first()).toBeVisible();
  await expect(page.locator("#horizon svg")).toContainText("today");
});
