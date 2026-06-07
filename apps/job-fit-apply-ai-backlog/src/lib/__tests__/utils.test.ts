import { describe, it, expect } from "vitest";
import { cn } from "../utils";

describe("cn", () => {
  it("should merge class names", () => {
    expect(cn("foo", "bar")).toBe("foo bar");
  });

  it("should handle empty inputs", () => {
    expect(cn()).toBe("");
  });

  it("should handle null/undefined inputs", () => {
    expect(cn("foo", null as unknown as string, "bar", undefined as unknown as string)).toBe("foo bar");
  });

  it("should handle arrays of class names", () => {
    expect(cn(["foo", "bar"])).toBe("foo bar");
  });

  it("should merge tailwind classes with twMerge", () => {
    // twMerge will optimize conflicting classes
    expect(cn("bg-red-500", "bg-blue-500")).toBe("bg-blue-500");
  });

  it("should handle class-variance-authority class names", () => {
    const btn = "btn";
    expect(cn(btn, "primary")).toBe("btn primary");
  });
});
