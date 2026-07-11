import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { fetchTracks, updateTrackStatus } from "../api";

// Unit tests for the bridge API client. `fetch` is stubbed; no network / bridge needed.
describe("lib/api", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal("fetch", fetchMock);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  describe("fetchTracks", () => {
    it("GETs /api/tracks and returns the parsed rows", async () => {
      const rows = [{ id: 1, company: "Acme", status: "backlog" }];
      fetchMock.mockResolvedValue({ ok: true, json: async () => rows });

      const result = await fetchTracks();

      expect(result).toEqual(rows);
      expect(fetchMock).toHaveBeenCalledTimes(1);
      expect(fetchMock.mock.calls[0][0]).toMatch(/\/api\/tracks$/);
    });

    it("throws with the status code on a non-ok response", async () => {
      fetchMock.mockResolvedValue({ ok: false, status: 500 });
      await expect(fetchTracks()).rejects.toThrow(/500/);
    });
  });

  describe("updateTrackStatus", () => {
    it("POSTs the status as JSON to the id-scoped endpoint", async () => {
      fetchMock.mockResolvedValue({ ok: true });

      await updateTrackStatus(7, "applied");

      const [url, opts] = fetchMock.mock.calls[0];
      expect(url).toMatch(/\/api\/tracks\/7\/status$/);
      expect(opts.method).toBe("POST");
      expect(opts.headers["Content-Type"]).toBe("application/json");
      expect(JSON.parse(opts.body)).toEqual({ status: "applied" });
    });

    it("throws with the status code on a non-ok response", async () => {
      fetchMock.mockResolvedValue({ ok: false, status: 404 });
      await expect(updateTrackStatus(1, "applied")).rejects.toThrow(/404/);
    });
  });
});
