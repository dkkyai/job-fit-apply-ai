import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import Index from "../Index";

// Mock Supabase client
vi.mock("@/integrations/supabase/client", () => {
  const mockQueryBuilder = {
    select: vi.fn().mockReturnThis(),
    order: vi.fn().mockResolvedValue({ data: [], error: null }),
  };
  
  return {
    supabase: {
      from: vi.fn().mockReturnValue(mockQueryBuilder),
    },
  };
});

// Mock toast
vi.mock("@/hooks/use-toast", () => ({
  toast: vi.fn(),
}));

// Mock matchMedia
beforeEach(() => {
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

describe("Index Page", () => {
  it("should render loading state initially", async () => {
    render(<Index />);
    
    // The page should render with a header
    expect(document.querySelector("header")).toBeInTheDocument();
  });

  it("should render header with title", async () => {
    render(<Index />);
    
    await waitFor(() => {
      expect(screen.getByText("Job Tracker Backlog")).toBeInTheDocument();
    });
  });

  it("should render status chips", async () => {
    render(<Index />);
    
    // Wait for the page to load
    await waitFor(() => {
      // Check for status chips using the correct selector (badge elements)
      const statusElements = document.querySelectorAll(".capitalize.text-xs");
      expect(statusElements.length).toBeGreaterThan(0);
    });
  });
});
