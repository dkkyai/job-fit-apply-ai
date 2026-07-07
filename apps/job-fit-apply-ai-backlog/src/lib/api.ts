// Data layer for the backlog UI. Talks to the jd-bridge HTTP API (Ktor), which
// reads/writes the shared `tracks` table in the Postgres container. Replaces the
// former direct supabase-js access.

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8765";

export const STATUS_OPTIONS = [
  "backlog", "duplicate", "applied", "interested", "skipped", "interviewing", "rejected", "offer",
] as const;

export type Status = (typeof STATUS_OPTIONS)[number];

export interface Track {
  id: number;
  company: string;
  role_title: string;
  location: string | null;
  remote_policy: string | null;
  fit_score: number | null;
  job_url: string | null;
  artifact_url: string | null;
  tech_stack: string[] | null;
  status: Status;
  created_at: string;
  duplicate: boolean;
}

/** All tracks, newest first (server-ordered). Filtering is done client-side. */
export async function fetchTracks(): Promise<Track[]> {
  const res = await fetch(`${API_BASE}/api/tracks`);
  if (!res.ok) throw new Error(`Failed to load tracks (HTTP ${res.status})`);
  return (await res.json()) as Track[];
}

/** Update a single track's status. Throws on any non-2xx response. */
export async function updateTrackStatus(id: number, status: Status): Promise<void> {
  const res = await fetch(`${API_BASE}/api/tracks/${id}/status`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ status }),
  });
  if (!res.ok) throw new Error(`Failed to update status (HTTP ${res.status})`);
}
