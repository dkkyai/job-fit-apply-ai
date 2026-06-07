import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./tests",
  timeout: 30000,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 2 : undefined,
  use: {
    baseURL: process.env.PLAYWRIGHT_TEST_BASE_URL || "http://localhost:8080",
    screenshot: "only-on-failure",
    trace: process.env.CI ? "on-first-retry" : "retain-on-failure",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  webServer: {
    command: "npm run preview",
    url: "http://localhost:8080",
    reuseExistingServer: !process.env.CI,
  },
});
