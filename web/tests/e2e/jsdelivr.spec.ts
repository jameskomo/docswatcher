import { test, expect } from "@playwright/test";

// Live network. Blocks the GitHub API so only the jsDelivr route can succeed,
// which is what makes this a real test of that route rather than of the fallback.
test.skip(!process.env.DOCSWATCHER_LIVE, "set DOCSWATCHER_LIVE=1 to run live network tests");

test("scans a real repo through jsDelivr with the GitHub API blocked", async ({ page }) => {
  test.setTimeout(120_000);
  await page.route("**://api.github.com/**", (r) => r.abort());
  await page.route("**://raw.githubusercontent.com/**", (r) => r.abort());

  await page.goto("./");
  await page.getByRole("tab", { name: /GitHub URL/i }).click();
  await page.getByPlaceholder(/github\.com/i).fill("https://github.com/openai/openai-quickstart-python");
  await page.getByRole("button", { name: /scan repository/i }).click();

  await expect(page.getByText(/Assistants API/i).first()).toBeVisible({ timeout: 90_000 });
  await expect(page.getByText(/beta\.assistants\.create/).first()).toBeVisible();
  await expect(page.getByText(/gpt-3\.5-turbo-0125/).first()).toBeVisible();
});
