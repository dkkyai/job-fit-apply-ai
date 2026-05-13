-- Migration 001: Resume Tailoring Subgraph
-- Run once against your Supabase project.
-- Safe to re-run (all statements use IF NOT EXISTS / IF NOT EXISTS equivalents).

-- ── New table: resume_tailoring ───────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS resume_tailoring (
    id                   SERIAL PRIMARY KEY,
    track_id             INTEGER REFERENCES tracks(id) ON DELETE CASCADE,
    role_title           TEXT,
    company              TEXT,
    jd_structured        JSONB,    -- JdStructured: required/preferred skills, ATS phrases, etc.
    gap_analysis         JSONB,    -- GapAnalysis: skills table, coverage score, gaps, strengths
    tailored_summary     TEXT,     -- Rewritten professional summary (plain text)
    tailored_bullets     JSONB,    -- List<TailoredBullet>: original + rewritten + alignment score
    restructured_skills  JSONB,    -- RestructuredSkills: grouped categories, JD-matched list
    ats_overall_score    INTEGER,  -- AtsScore.overallScore (0-100)
    ats_keyword_match    INTEGER,  -- AtsScore.keywordMatch (0-100)
    ats_remaining_gaps   JSONB,    -- AtsScore.remainingGaps (list of strings)
    ats_top_improvements JSONB,    -- AtsScore.top3Improvements (list of strings)
    created_at           TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_resume_tailoring_track_id
    ON resume_tailoring(track_id);

CREATE INDEX IF NOT EXISTS idx_resume_tailoring_created_at
    ON resume_tailoring(created_at DESC);

-- ── Extend tracks table ────────────────────────────────────────────────────────
ALTER TABLE tracks
    ADD COLUMN IF NOT EXISTS ats_score INTEGER;

-- ── Row-level security (match tracks table policy) ─────────────────────────────
-- Enable RLS so the anon key cannot read tailoring data from other users.
ALTER TABLE resume_tailoring ENABLE ROW LEVEL SECURITY;

-- Allow authenticated service role full access (used by the pipeline).
-- The anon key policy below restricts public reads if you expose the API.
CREATE POLICY IF NOT EXISTS "service_role_full_access"
    ON resume_tailoring
    FOR ALL
    TO service_role
    USING (true)
    WITH CHECK (true);
