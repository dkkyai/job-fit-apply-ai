import { test, expect, type Page, type Route } from "@playwright/test";

// Hermetic: the bundle's API base defaults to http://localhost:8765 — the live bridge on a
// dev machine — so every /api/tracks call is answered here instead of going to the network.
const now = new Date().toISOString();
const TRACKS = [
  {
    id: 1, company: "Acme", role_title: "Staff SDET", location: "Seattle, WA", remote_policy: "hybrid",
    fit_score: 91, job_url: "https://jobs.example.com/1", artifact_url: "https://reports.example.com/1/",
    tech_stack: ["Kotlin", "Postgres"], status: "backlog", created_at: now, duplicate: false,
  },
  {
    id: 2, company: "Globex", role_title: "QA Engineer", location: "Remote", remote_policy: "remote",
    fit_score: 72, job_url: "https://jobs.example.com/2", artifact_url: null,
    tech_stack: null, status: "interested", created_at: now, duplicate: false,
  },
];

async function mockBridge(page: Page, opts: { statusCode?: number } = {}) {
  const statusPosts: { url: string; body: unknown }[] = [];
  await page.route("**/api/tracks", (route: Route) =>
    route.fulfill({ json: TRACKS, headers: { "Access-Control-Allow-Origin": "*" } }));
  await page.route("**/api/tracks/*/status", async (route: Route) => {
    if (route.request().method() === "OPTIONS") {
      return route.fulfill({
        status: 204,
        headers: {
          "Access-Control-Allow-Origin": "*",
          "Access-Control-Allow-Methods": "POST",
          "Access-Control-Allow-Headers": "Content-Type",
        },
      });
    }
    statusPosts.push({ url: route.request().url(), body: route.request().postDataJSON() });
    return route.fulfill({
      status: opts.statusCode ?? 200,
      json: { ok: true },
      headers: { "Access-Control-Allow-Origin": "*" },
    });
  });
  return statusPosts;
}

test.describe("Job Tracker App", () => {
  test.beforeEach(async ({ page }) => {
    await mockBridge(page);
    await page.goto("/");
  });

  test("should display the job tracker title", async ({ page }) => {
    await expect(page.getByText("Job Tracker Backlog")).toBeVisible();
  });

  test("should display status summary chips", async ({ page }) => {
    await page.waitForSelector("main");
    // Badge renders as a div with the rounded-full class.
    const statusChips = await page.locator(".rounded-full").count();
    expect(statusChips).toBeGreaterThan(0);
  });

  test("should display loading state initially", async ({ page }) => {
    await expect(page.locator("main")).toBeVisible();
  });

  test("should have a table with job applications", async ({ page }) => {
    await page.waitForSelector("table");
    await expect(page.locator("table")).toBeVisible();
    const headers = ["ID", "Company", "Role", "Location", "Fit", "Status", "Date", "Job", "Report"];
    for (const header of headers) {
      await expect(page.getByRole("columnheader", { name: header })).toBeVisible();
    }
  });

  test("should have navigation elements", async ({ page }) => {
    await expect(page.locator("header")).toBeVisible();
  });

  test("renders the tracks returned by the bridge", async ({ page }) => {
    await expect(page.getByRole("cell", { name: "Acme", exact: true })).toBeVisible();
    await expect(page.getByRole("cell", { name: "Globex", exact: true })).toBeVisible();
    await expect(page.getByText("2 applications tracked")).toBeVisible();
    await expect(page.getByText("Kotlin", { exact: true })).toBeVisible();   // tech-stack badge
  });

  test("clicking a column header re-sorts the rows", async ({ page }) => {
    const firstCompany = () => page.locator("tbody tr").first().locator("td").nth(1);
    await expect(firstCompany()).toHaveText("Acme");          // default: fit score, descending
    await page.getByRole("columnheader", { name: "Fit" }).click();
    await expect(firstCompany()).toHaveText("Globex");        // fit score, ascending
  });
});

test.describe("Status updates", () => {
  test("choosing a status POSTs it to the bridge and confirms with a toast", async ({ page }) => {
    const posts = await mockBridge(page);
    await page.goto("/");
    const acmeRow = page.locator("tbody tr", { hasText: "Acme" });
    await acmeRow.getByRole("combobox").click();
    await page.getByRole("option", { name: "applied" }).click();

    await expect(page.getByText("Status updated")).toBeVisible();
    await expect(page.getByText("Changed to applied")).toBeVisible();
    expect(posts).toEqual([{ url: expect.stringContaining("/api/tracks/1/status"), body: { status: "applied" } }]);
  });

  test("a rejected update shows the error toast", async ({ page }) => {
    await mockBridge(page, { statusCode: 500 });
    await page.goto("/");
    const acmeRow = page.locator("tbody tr", { hasText: "Acme" });
    await acmeRow.getByRole("combobox").click();
    await page.getByRole("option", { name: "skipped" }).click();

    await expect(page.getByText("Update failed")).toBeVisible();
    await expect(page.getByText("Failed to update status (HTTP 500)")).toBeVisible();
  });
});

test("an unknown route renders the 404 page", async ({ page }) => {
  await mockBridge(page);
  await page.goto("/no-such-page");
  await expect(page.getByRole("heading", { name: "404" })).toBeVisible();
  await expect(page.getByText("Oops! Page not found")).toBeVisible();
});
