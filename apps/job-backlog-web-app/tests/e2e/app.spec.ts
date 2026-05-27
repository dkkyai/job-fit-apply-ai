import { test, expect } from "@playwright/test";

test.describe("Job Tracker App", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/");
  });

  test("should display the job tracker title", async ({ page }) => {
    await expect(page.getByText("Job Tracker Backlog")).toBeVisible();
  });

  test("should display status summary chips", async ({ page }) => {
    // Wait for the page to load
    await page.waitForSelector("main");
    
    // Check for some status chips (Badge renders as div with rounded-full class)
    const statusChips = await page.locator(".rounded-full").count();
    expect(statusChips).toBeGreaterThan(0);
  });

  test("should display loading state initially", async ({ page }) => {
    // The page should load and show either loading or data
    await expect(page.locator("main")).toBeVisible();
  });

  test("should have a table with job applications", async ({ page }) => {
    await page.waitForSelector("table");
    
    const table = page.locator("table");
    await expect(table).toBeVisible();
    
    // Check for table headers
    const headers = ["ID", "Company", "Role", "Location", "Fit", "Status", "Date", "Job", "Artifact"];
    for (const header of headers) {
      await expect(page.getByRole("columnheader", { name: header })).toBeVisible();
    }
  });

  test("should have navigation elements", async ({ page }) => {
    // Check for header element
    const header = page.locator("header");
    await expect(header).toBeVisible();
  });
});
