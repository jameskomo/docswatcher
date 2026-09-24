import { test, expect } from "@playwright/test";

// Live network test against a real public gitlab.com project, which proves the API still answers
// the page's cross-origin reads. Skipped unless DOCSWATCHER_LIVE=1, so CI stays offline.
// Run with: DOCSWATCHER_LIVE=1 npx playwright test gitlab-live
test.skip(!process.env.DOCSWATCHER_LIVE, "set DOCSWATCHER_LIVE=1 to run live network tests");

test("scans a real gitlab.com project by URL", async ({ page }) => {
  test.setTimeout(180_000);
  const failed: string[] = [];
  page.on("requestfailed", (r) => { if (r.url().startsWith("https://gitlab.com/")) failed.push(r.url()); });
  await page.goto("./");
  await page.getByRole("tab", { name: /GitHub or GitLab URL/i }).click();
  await page.getByPlaceholder(/gitlab\.com/i).fill("https://gitlab.com/gitlab-org/cli");
  await page.getByRole("button", { name: /scan repository/i }).click();

  await expect(page.locator("#results")).toContainText("gitlab.com/gitlab-org/cli", { timeout: 150_000 });
  await expect(page.locator("#results")).toContainText(/\d+ text files read/);
  await expect(page).toHaveURL(/#\/\?repo=gitlab\.com\/gitlab-org\/cli$/);
  expect(failed).toEqual([]);
});
