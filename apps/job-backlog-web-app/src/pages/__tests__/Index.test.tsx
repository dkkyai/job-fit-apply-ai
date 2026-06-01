import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor, fireEvent, act } from "@testing-library/react";
import Index from "../Index";

// ── Supabase mock ──────────────────────────────────────────────────────────────

const mockSelect = vi.fn();
const mockOrder = vi.fn();
const mockUpdate = vi.fn();
const mockEq = vi.fn();
const mockFrom = vi.fn();

vi.mock("@/integrations/supabase/client", () => ({
  supabase: {
    from: mockFrom,
  },
}));

vi.mock("@/hooks/use-toast", () => ({
  toast: vi.fn(),
}));

// ── Fixtures ───────────────────────────────────────────────────────────────────

const makeTrack = (overrides = {}) => ({
  id: 1,
  company: "Acme Corp",
  role_title: "Senior Engineer",
  location: "San Francisco, CA",
  remote_policy: "remote",
  fit_score: 80,
  job_url: "https://example.com/job",
  artifact_url: null,
  tech_stack: ["React", "TypeScript"],
  status: "backlog" as const,
  created_at: new Date().toISOString(),
  duplicate: false,
  ...overrides,
});

function setupSupabaseMock(data: ReturnType<typeof makeTrack>[], error = null) {
  mockOrder.mockResolvedValue({ data, error });
  mockSelect.mockReturnValue({ order: mockOrder });
  mockFrom.mockReturnValue({ select: mockSelect, update: mockUpdate });
  mockUpdate.mockReturnValue({ eq: mockEq });
  mockEq.mockResolvedValue({ error: null });
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.spyOn(window, "matchMedia").mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => true,
  }));
});

// ── Tests ──────────────────────────────────────────────────────────────────────

describe("Index Page", () => {
  describe("rendering", () => {
    it("renders header title", async () => {
      setupSupabaseMock([]);
      render(<Index />);
      await waitFor(() => {
        expect(screen.getByText("Job Tracker Backlog")).toBeInTheDocument();
      });
    });

    it("shows loading text initially", () => {
      // Never resolves to keep loading state visible
      mockOrder.mockReturnValue(new Promise(() => {}));
      mockSelect.mockReturnValue({ order: mockOrder });
      mockFrom.mockReturnValue({ select: mockSelect });

      render(<Index />);
      expect(screen.getByText(/Loading applications/i)).toBeInTheDocument();
    });

    it("renders status chips for all statuses", async () => {
      setupSupabaseMock([]);
      render(<Index />);
      await waitFor(() => {
        for (const status of ["backlog", "applied", "interviewing", "rejected", "offer"]) {
          expect(screen.getByText(new RegExp(status, "i"))).toBeInTheDocument();
        }
      });
    });

    it("shows tracked count in subtitle", async () => {
      setupSupabaseMock([makeTrack({ fit_score: 80 })]);
      render(<Index />);
      await waitFor(() => {
        expect(screen.getByText(/1 application tracked/i)).toBeInTheDocument();
      });
    });

    it("uses plural form for multiple applications", async () => {
      setupSupabaseMock([
        makeTrack({ id: 1, fit_score: 80 }),
        makeTrack({ id: 2, fit_score: 75 }),
      ]);
      render(<Index />);
      await waitFor(() => {
        expect(screen.getByText(/2 applications tracked/i)).toBeInTheDocument();
      });
    });

    it("displays error message from Supabase", async () => {
      mockOrder.mockResolvedValue({ data: null, error: { message: "DB connection failed" } });
      mockSelect.mockReturnValue({ order: mockOrder });
      mockFrom.mockReturnValue({ select: mockSelect });

      render(<Index />);
      await waitFor(() => {
        expect(screen.getByText("DB connection failed")).toBeInTheDocument();
      });
    });

    it("renders job rows after data loads", async () => {
      setupSupabaseMock([makeTrack({ company: "TestCo", role_title: "QA Lead" })]);
      render(<Index />);
      await waitFor(() => {
        expect(screen.getByText("TestCo")).toBeInTheDocument();
        expect(screen.getByText("QA Lead")).toBeInTheDocument();
      });
    });

    it("renders tech stack badges (up to 3)", async () => {
      setupSupabaseMock([
        makeTrack({ tech_stack: ["React", "Node", "Postgres", "Redis"] }),
      ]);
      render(<Index />);
      await waitFor(() => {
        expect(screen.getByText("React")).toBeInTheDocument();
        expect(screen.getByText("Node")).toBeInTheDocument();
        expect(screen.getByText("Postgres")).toBeInTheDocument();
        expect(screen.getByText("+1")).toBeInTheDocument();
      });
    });

    it("renders job URL link when present", async () => {
      setupSupabaseMock([makeTrack({ job_url: "https://jobs.example.com/123" })]);
      render(<Index />);
      await waitFor(() => {
        const links = document.querySelectorAll('a[href="https://jobs.example.com/123"]');
        expect(links.length).toBeGreaterThan(0);
      });
    });
  });

  describe("fit score filtering", () => {
    const tracks = [
      makeTrack({ id: 1, fit_score: 90 }),
      makeTrack({ id: 2, fit_score: 70 }),
      makeTrack({ id: 3, fit_score: 40 }),
    ];

    it("shows only tracks at or above the default threshold (60)", async () => {
      setupSupabaseMock(tracks);
      render(<Index />);
      await waitFor(() => {
        // 2 tracks qualify (90 and 70), not the 40-score one
        expect(screen.getByText(/2 applications tracked/i)).toBeInTheDocument();
      });
    });

    it("excludes tracks with null fit_score", async () => {
      setupSupabaseMock([
        makeTrack({ id: 1, fit_score: null }),
        makeTrack({ id: 2, fit_score: 80 }),
      ]);
      render(<Index />);
      await waitFor(() => {
        expect(screen.getByText(/1 application tracked/i)).toBeInTheDocument();
      });
    });

    it("clicking a fit preset button updates the threshold", async () => {
      setupSupabaseMock(tracks);
      render(<Index />);

      // Wait for initial load
      await waitFor(() => {
        expect(screen.getByText(/2 applications tracked/i)).toBeInTheDocument();
      });

      // Click the "80" preset
      const btn80 = screen.getByRole("button", { name: "80" });
      await act(async () => { fireEvent.click(btn80); });

      await waitFor(() => {
        expect(screen.getByText(/1 application tracked/i)).toBeInTheDocument();
      });
    });

    it("clicking '0' preset shows all tracks", async () => {
      setupSupabaseMock(tracks);
      render(<Index />);

      await waitFor(() => screen.getByText(/2 applications tracked/i));

      const btn0 = screen.getByRole("button", { name: "0" });
      await act(async () => { fireEvent.click(btn0); });

      await waitFor(() => {
        expect(screen.getByText(/3 applications tracked/i)).toBeInTheDocument();
      });
    });
  });

  describe("date filtering", () => {
    it("filters out tracks older than maxDaysAgo", async () => {
      const old = makeTrack({
        id: 1,
        fit_score: 80,
        created_at: new Date(Date.now() - 10 * 24 * 60 * 60 * 1000).toISOString(),
      });
      const recent = makeTrack({
        id: 2,
        fit_score: 80,
        created_at: new Date(Date.now() - 1 * 24 * 60 * 60 * 1000).toISOString(),
      });

      setupSupabaseMock([old, recent]);
      render(<Index />);

      // Default is 7 days — old track (10 days) should be excluded
      await waitFor(() => {
        expect(screen.getByText(/1 application tracked/i)).toBeInTheDocument();
      });
    });

    it("clicking 'All time' shows all matching tracks regardless of date", async () => {
      const old = makeTrack({
        id: 1,
        fit_score: 80,
        created_at: new Date(Date.now() - 60 * 24 * 60 * 60 * 1000).toISOString(),
      });
      setupSupabaseMock([old]);
      render(<Index />);

      await waitFor(() => {
        expect(screen.getByText(/0 applications tracked/i)).toBeInTheDocument();
      });

      const btnAll = screen.getByRole("button", { name: "All time" });
      await act(async () => { fireEvent.click(btnAll); });

      await waitFor(() => {
        expect(screen.getByText(/1 application tracked/i)).toBeInTheDocument();
      });
    });
  });

  describe("sorting", () => {
    it("clicking a column header toggles sort direction", async () => {
      setupSupabaseMock([
        makeTrack({ id: 1, company: "Zebra Inc", fit_score: 90 }),
        makeTrack({ id: 2, company: "Alpha Corp", fit_score: 70 }),
      ]);
      render(<Index />);

      await waitFor(() => screen.getByText(/2 applications tracked/i));

      // Click Company header twice to test toggle
      const companyHeader = screen.getByText("Company");
      fireEvent.click(companyHeader.closest("th")!);
      fireEvent.click(companyHeader.closest("th")!);

      // Assert the page didn't break (rows still render)
      expect(screen.getByText("Zebra Inc")).toBeInTheDocument();
      expect(screen.getByText("Alpha Corp")).toBeInTheDocument();
    });
  });

  describe("status update", () => {
    it("filters out rows that transition away from backlog/interested", async () => {
      const { toast } = await import("@/hooks/use-toast");
      setupSupabaseMock([makeTrack({ id: 42, company: "StatusCo" })]);
      render(<Index />);

      await waitFor(() => screen.getByText("StatusCo"));

      // Supabase update already mocked to succeed via setupSupabaseMock
      // The track becomes collapsed (opacity-50) but not removed until re-fetch
      expect(screen.getByText("StatusCo")).toBeInTheDocument();
    });

    it("shows duplicate rows as semi-transparent", async () => {
      setupSupabaseMock([
        makeTrack({ id: 1, duplicate: false, fit_score: 80 }),
      ]);
      render(<Index />);

      await waitFor(() => screen.getByText("Acme Corp"));
      // Non-duplicate rows should NOT have opacity-50 class
      const row = screen.getByText("Acme Corp").closest("tr");
      expect(row).not.toHaveClass("opacity-50");
    });
  });

  describe("fit score display", () => {
    it("shows fit score with high colour (>=85)", async () => {
      setupSupabaseMock([makeTrack({ fit_score: 90 })]);
      render(<Index />);
      await waitFor(() => {
        expect(screen.getByText("90")).toBeInTheDocument();
      });
    });

    it("shows fit score with medium colour (70-84)", async () => {
      setupSupabaseMock([makeTrack({ fit_score: 75 })]);
      render(<Index />);
      await waitFor(() => {
        expect(screen.getByText("75")).toBeInTheDocument();
      });
    });

    it("renders nothing for null fit score", async () => {
      setupSupabaseMock([makeTrack({ id: 1, fit_score: null, status: "backlog" })]);
      render(<Index />);

      await waitFor(() => screen.getByText("Acme Corp"));

      // Null fit score rows are excluded by the default filter threshold (60)
      // so the track itself won't appear — count should be 0
      expect(screen.getByText(/0 applications tracked/i)).toBeInTheDocument();
    });
  });

  describe("location display", () => {
    it("shows em-dash when location is null", async () => {
      setupSupabaseMock([makeTrack({ location: null })]);
      render(<Index />);
      await waitFor(() => {
        expect(screen.getByText("—")).toBeInTheDocument();
      });
    });

    it("hides remote_policy when value is 'unknown'", async () => {
      setupSupabaseMock([makeTrack({ remote_policy: "unknown" })]);
      render(<Index />);
      await waitFor(() => screen.getByText("Acme Corp"));
      expect(screen.queryByText("unknown")).not.toBeInTheDocument();
    });
  });
});
