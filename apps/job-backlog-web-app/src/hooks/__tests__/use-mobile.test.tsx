import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, act, waitFor } from "@testing-library/react";
import { useIsMobile } from "../use-mobile";

describe("useIsMobile", () => {
  interface MockMediaQueryList {
    matches: boolean;
    media: string;
    onchange: ((this: MediaQueryList, ev: MediaQueryListEvent) => void) | null;
    addListener: (callback: (this: MediaQueryList, ev: MediaQueryListEvent) => void) => void;
    removeListener: (callback: (this: MediaQueryList, ev: MediaQueryListEvent) => void) => void;
    addEventListener: (type: string, callback: (this: MediaQueryList, ev: MediaQueryListEvent) => void) => void;
    removeEventListener: (type: string, callback: (this: MediaQueryList, ev: MediaQueryListEvent) => void) => void;
    dispatchEvent: (event: Event) => boolean;
  }

  let matchMedia: (query: string) => MockMediaQueryList;
  let mockMql: MockMediaQueryList;

  beforeEach(() => {
    mockMql = {
      matches: false,
      media: "(max-width: 767px)",
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn().mockReturnValue(true),
    };
    matchMedia = vi.fn().mockReturnValue(mockMql);
    window.matchMedia = matchMedia;
  });

  it("should return false initially (due to !!isMobile)", () => {
    const { result } = renderHook(() => useIsMobile());
    expect(result.current).toBe(false);
  });

  it("should return true when window width is less than 768px", () => {
    Object.defineProperty(window, "innerWidth", { configurable: true, value: 700 });
    const { result } = renderHook(() => useIsMobile());
    expect(result.current).toBe(true);
  });

  it("should return false when window width is 768px or more", () => {
    Object.defineProperty(window, "innerWidth", { configurable: true, value: 800 });
    const { result } = renderHook(() => useIsMobile());
    expect(result.current).toBe(false);
  });

  it("should update when media query changes", async () => {
    // Set initial mobile width
    Object.defineProperty(window, "innerWidth", { configurable: true, value: 700 });
    const { result } = renderHook(() => useIsMobile());
    
    // Wait for initial effect to complete
    await waitFor(() => {
      expect(result.current).toBe(true);
    });
    
    // Update window width to non-mobile
    Object.defineProperty(window, "innerWidth", { configurable: true, value: 800 });
    
    // Get the onChange callback that was registered
    const onChangeCallback = vi.fn();
    const addEventListenerCall = (mockMql.addEventListener as unknown as { mock: { calls: unknown[][] } }).mock.calls.find((call: [string, unknown]) => call[0] === "change");
    if (addEventListenerCall) {
      const originalCallback = addEventListenerCall[1] as (event: MediaQueryListEvent) => void;
      onChangeCallback.mockImplementation((event: MediaQueryListEvent) => {
        originalCallback(event);
      });
    }
    
    // Call the onChange callback directly
    act(() => {
      onChangeCallback();
    });
    
    // Wait for the state to update
    await waitFor(() => {
      expect(result.current).toBe(false);
    });
    
    expect(result.current).toBe(false);
  });
});
