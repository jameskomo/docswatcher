import { test, expect, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// Scanning a GitLab project by URL, against a mocked GitLab API serving the files of the vendored
// openai-quickstart-python, so the findings are known. And GitHub, through the same field, as before.
//
// When the private deployment checkout is present, scripts/serve-export.mjs applies the production
// Content-Security-Policy. These tests then fail until its connect-src allows https://gitlab.com,
// which is the point: the live site would refuse the same requests.

const here = dirname(fileURLToPath(import.meta.url));
const vendored = JSON.parse(readFileSync(join(here, "..", "..", "vendor", "openai__openai-quickstart-python.json"), "utf8")) as { files: Array<{ path: string; text: string }> };
const SHA = "5f1e2d3c4b5a69788796a5b4c3d2e1f0a9b8c7d6";

/** Serves one project in a nested group from a mocked GitLab at origin. Returns the URLs asked for. */
async function mockGitLab(page: Page, origin: string, projectPath: string) {
  const asked: string[] = [];
  const api = `${origin}/api/v4`;
  const tree = vendored.files.map((f) => ({ id: "0", name: f.path.split("/").pop(), type: "blob", path: f.path, mode: "100644" }));
  await page.route(`${origin}/**`, async (route) => {
    const url = route.request().url();
    asked.push(url);
    const cors = { "access-control-allow-origin": "*", "access-control-expose-headers": "Link, X-Next-Page" };
    if (url === `${api}/projects/${encodeURIComponent(projectPath)}`) {
      return route.fulfill({ json: { id: 7, default_branch: "main", path_with_namespace: projectPath }, headers: cors });
    }
    if (url === `${api}/projects/7/repository/commits/main`) return route.fulfill({ json: { id: SHA }, headers: cors });
    if (url.startsWith(`${api}/projects/7/repository/tree?`)) {
      // Two pages, so the scan has to follow the Link header.
      const page2 = new URL(url).searchParams.get("page") === "2";
      const next = new URL(url); next.searchParams.set("page", "2");
      return route.fulfill({
        json: page2 ? tree.slice(8) : tree.slice(0, 8),
        headers: page2 ? cors : { ...cors, link: `<${next}>; rel="next"` },
      });
    }
    const raw = new RegExp(`^${api}/projects/7/repository/files/([^/]+)/raw\\?ref=${SHA}$`).exec(url);
    const file = raw && vendored.files.find((f) => f.path === decodeURIComponent(raw[1]));
    if (file) return route.fulfill({ body: file.text, contentType: "text/plain", headers: cors });
    return route.fulfill({ status: 404, json: { message: "404 Not Found" }, headers: cors });
  });
  return asked;
}

/** Serves the page under this Content-Security-Policy, or under none, whatever the server sends. */
async function withPolicy(page: Page, policy: string | null) {
  await page.route((u) => u.pathname.endsWith("/prefix/"), async (route) => {
    if (route.request().resourceType() !== "document") return route.continue();
    const res = await route.fetch();
    const headers = { ...res.headers() };
    delete headers["content-security-policy"];
    if (policy) headers["content-security-policy"] = policy;
    await route.fulfill({ response: res, headers });
  });
}

async function scanUrl(page: Page, address: string) {
  await expect(page.locator("#results")).toBeVisible();
  await page.getByRole("tab", { name: /GitHub or GitLab URL/i }).click();
  await page.getByPlaceholder(/gitlab\.com/i).fill(address);
  await page.getByRole("button", { name: /scan repository/i }).click();
}

test("scans a gitlab.com project in a nested group, links its lines, and offers a link that re-runs it", async ({ page }) => {
  const asked = await mockGitLab(page, "https://gitlab.com", "acme/platform/quickstart");
  await page.goto("./");
  await scanUrl(page, "https://gitlab.com/acme/platform/quickstart");

  await expect(page.locator("#results")).toContainText("gitlab.com/acme/platform/quickstart");
  await expect(page.locator("#findings")).toContainText(/Assistants API/i);
  await expect(page.locator("#inventory")).toContainText("beta.assistants.create");
  // Both tree pages were read, and every file came from the pinned commit.
  expect(asked.filter((u) => u.includes("/repository/tree?")).length).toBe(2);
  for (const u of asked.filter((u) => u.includes("/raw?"))) expect(u).toContain(`ref=${SHA}`);

  // Evidence links go to GitLab, pinned to the commit that was read.
  const hrefs = await page.locator("#inventory a[href]").evaluateAll((a) => a.map((x) => x.getAttribute("href")));
  expect(hrefs.length).toBeGreaterThan(0);
  for (const href of hrefs) expect(href).toMatch(new RegExp(`^https://gitlab\\.com/acme/platform/quickstart/-/blob/${SHA}/.+#L\\d+$`));

  // The address bar and the share controls carry a link that names the host and the whole path.
  await expect(page).toHaveURL(/#\/\?repo=gitlab\.com\/acme\/platform\/quickstart$/);
  await page.locator("[data-testid=share] summary").click();
  await expect(page.getByTestId("share-badge")).toContainText("#/?repo=gitlab.com/acme/platform/quickstart");
});

test("a gitlab.com live scan link scans that project on arrival", async ({ page }) => {
  await mockGitLab(page, "https://gitlab.com", "acme/platform/quickstart");
  await page.goto("./#/?repo=gitlab.com/acme/platform/quickstart");
  await expect(page.locator("#repo-url")).toHaveValue("https://gitlab.com/acme/platform/quickstart");
  await expect(page.locator("#results")).toContainText("gitlab.com/acme/platform/quickstart");
  await expect(page.locator("#inventory")).toContainText("beta.assistants.create");
});

test("a self-managed GitLab is scanned by its full URL where this page's policy allows it", async ({ page }) => {
  // A copy of the site someone runs for their own instance: no policy in the way.
  await withPolicy(page, null);
  const asked = await mockGitLab(page, "https://gitlab.example.com:8443", "team/api");
  await page.goto("./");
  await scanUrl(page, "https://gitlab.example.com:8443/team/api");
  await expect(page.locator("#results")).toContainText("gitlab.example.com:8443/team/api");
  await expect(page.locator("#inventory")).toContainText("beta.assistants.create");
  expect(asked[0]).toBe("https://gitlab.example.com:8443/api/v4/projects/team%2Fapi");
  await expect(page).toHaveURL(/#\/\?repo=gitlab\.example\.com:8443\/team\/api$/);
});

test("a self-managed GitLab the page's policy refuses gets a message that says so", async ({ page }) => {
  // The deployed policy lists gitlab.com and no other GitLab. Serve the page under such a policy.
  await withPolicy(page, "connect-src 'self' https://gitlab.com");
  const asked = await mockGitLab(page, "https://gitlab.example.com", "team/api");
  await page.goto("./");
  await scanUrl(page, "https://gitlab.example.com/team/api");

  const alert = page.getByRole("alert");
  await expect(alert).toContainText("Content-Security-Policy does not allow requests to https://gitlab.example.com");
  await expect(alert).toContainText("run the site locally");
  expect(asked).toEqual([]);
  // A refused self-managed host says nothing about GitHub, gitlab.com or the URL field: it stays usable.
  await expect(page.getByRole("tab", { name: /GitHub or GitLab URL/i })).toHaveAttribute("aria-selected", "true");
});

test("GitHub scanning is unchanged: jsDelivr first, owner/name links, and no GitLab request", async ({ page }) => {
  const gitlab: string[] = [];
  await page.route("https://gitlab.com/**", (r) => { gitlab.push(r.request().url()); return r.abort(); });
  await page.route("https://data.jsdelivr.com/**", (r) => r.fulfill({
    json: { version: "ec8890d", files: vendored.files.map((f) => ({ name: "/" + f.path })) },
    headers: { "access-control-allow-origin": "*" },
  }));
  await page.route("https://cdn.jsdelivr.net/**", (r) => {
    const path = decodeURIComponent(new URL(r.request().url()).pathname.replace(/^\/gh\/[^/]+\/[^/]+@[^/]+\//, ""));
    const file = vendored.files.find((f) => f.path === path);
    return file
      ? r.fulfill({ body: file.text, contentType: "text/plain", headers: { "access-control-allow-origin": "*" } })
      : r.fulfill({ status: 404, body: "" });
  });
  await page.goto("./");
  await scanUrl(page, "https://github.com/openai/openai-quickstart-python");
  // The sample scan on arrival reads nothing over the network; a scan by URL says what it read.
  await expect(page.locator("#results")).toContainText(/\d+ text files read/);
  await expect(page.locator("#inventory")).toContainText("beta.assistants.create");
  await expect(page).toHaveURL(/#\/\?repo=openai\/openai-quickstart-python$/);
  await page.locator("[data-testid=share] summary").click();
  await expect(page.getByTestId("share-badge")).toContainText("#/?repo=openai/openai-quickstart-python)");
  expect(gitlab).toEqual([]);
});
