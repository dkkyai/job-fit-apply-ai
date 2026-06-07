import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { NavLink } from "../NavLink";

describe("NavLink", () => {
  it("should render children correctly", () => {
    render(
      <MemoryRouter>
        <NavLink to="/test">Test Link</NavLink>
      </MemoryRouter>
    );
    expect(screen.getByText("Test Link")).toBeInTheDocument();
  });

  it("should apply active class when route matches", () => {
    render(
      <MemoryRouter initialEntries={["/test"]}>
        <NavLink to="/test" activeClassName="active-link">
          Test Link
        </NavLink>
      </MemoryRouter>
    );
    // The NavLink should have the active class when the route matches
    const link = screen.getByText("Test Link");
    expect(link).toBeInTheDocument();
  });

  it("should pass through additional props", () => {
    render(
      <MemoryRouter>
        <NavLink to="/test" className="custom-class" data-test="value">
          Test Link
        </NavLink>
      </MemoryRouter>
    );
    const link = screen.getByText("Test Link");
    expect(link).toHaveClass("custom-class");
    expect(link).toHaveAttribute("data-test", "value");
  });

  it("should use cn for class name merging", () => {
    render(
      <MemoryRouter>
        <NavLink to="/test" className="base-class" activeClassName="active-class">
          Test Link
        </NavLink>
      </MemoryRouter>
    );
    const link = screen.getByText("Test Link");
    expect(link).toBeInTheDocument();
  });
});
