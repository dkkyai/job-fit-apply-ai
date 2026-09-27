import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, act } from "@testing-library/react";
import App from "../App";
import { toast } from "@/hooks/use-toast";

// Whole-app smoke test: the real module graph (router, query client, Radix toast + Sonner
// toasters, tooltip provider, every ui/* component Index uses) with only the network faked.
// It fails if anything the app imports at runtime has gone missing — which is what a
// component or dependency cleanup has to prove it didn't do.

const track = {
  id: 7, company: "Initech", role_title: "SDET", location: "Austin, TX", remote_policy: "onsite",
  fit_score: 88, job_url: "https://jobs.example.com/7", artifact_url: null, tech_stack: ["Go"],
  status: "backlog", created_at: new Date().toISOString(), duplicate: false,
};

beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify([track]), {
    status: 200, headers: { "Content-Type": "application/json" },
  })));
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.pushState({}, "", "/");
});

describe("App", () => {
  it("renders the backlog route with data from the bridge", async () => {
    render(<App />);
    expect(await screen.findByText("Initech")).toBeInTheDocument();
    expect(screen.getByText("Job Tracker Backlog")).toBeInTheDocument();
    expect(screen.getByText("1 application tracked")).toBeInTheDocument();
  });

  it("mounts the toaster so toast() calls are displayed", async () => {
    render(<App />);
    await screen.findByText("Initech");
    act(() => { toast({ title: "Smoke toast", description: "from the app test" }); });
    expect(await screen.findByText("Smoke toast")).toBeInTheDocument();
  });

  it("renders the 404 page for an unknown route", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});   // NotFound logs the path
    window.history.pushState({}, "", "/no-such-page");
    render(<App />);
    expect(await screen.findByText("Oops! Page not found")).toBeInTheDocument();
  });
});
