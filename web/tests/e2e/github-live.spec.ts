import { test, expect } from "@playwright/test";

// Live network test against real public repositories. Skipped unless DOCSWATCHER_LIVE=1,
// so CI stays offline and cannot be broken by someone else's repository changing.
// Run with: DOCSWATCHER_LIVE=1 npx playwright test github-live
test.skip(!process.env.DOCSWATCHER_LIVE, "set DOCSWATCHER_LIVE=1 to run live network tests");

test("scans a real public repo by URL and finds the Assistants API sunset", async ({ page }) => {
  test.setTimeout(120_000);
  await page.goto("./");
  await page.getByRole("tab", { name: /GitHub URL/i }).click();
  await page.getByPlaceholder(/github\.com/i).fill("https://github.com/openai/openai-quickstart-python");
  await page.getByRole("button", { name: /scan repository/i }).click();

  await expect(page.getByText(/Assistants API/i).first()).toBeVisible({ timeout: 90_000 });
  await expect(page.getByText(/beta\.assistants\.create/).first()).toBeVisible();
  await expect(page.getByText(/gpt-3\.5-turbo-0125/).first()).toBeVisible();
});
