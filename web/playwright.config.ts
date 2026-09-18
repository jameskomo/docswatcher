import { defineConfig, devices } from "@playwright/test";

// Runs against the static export served under a deep prefix, which is how an artifact host serves it.
const PREFIX = "/some/deep/prefix";
const PORT = 8091;

export default defineConfig({
  testDir: "tests/e2e",
  timeout: 90_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [["list"]],
  use: {
    baseURL: `http://localhost:${PORT}${PREFIX}/`,
    trace: "retain-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: `node scripts/serve-export.mjs ${PORT} ${PREFIX}`,
    url: `http://localhost:${PORT}${PREFIX}/`,
    reuseExistingServer: !process.env.CI,
    timeout: 30_000,
  },
});
