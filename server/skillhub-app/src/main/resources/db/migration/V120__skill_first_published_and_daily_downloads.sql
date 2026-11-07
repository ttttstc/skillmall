-- V120__skill_first_published_and_daily_downloads.sql
-- Two additions that the home page rankings need:
--   1. skill.first_published_at: the moment a Skill first had a PUBLISHED version.
--      Publishing a newer version must never move it, so it is written once and never updated.
--   2. skill_download_daily: per-UTC-day download buckets so a 7-day window can be summed
--      without inventing history. skill.download_count stays the cumulative counter and is
--      never used as a substitute for a windowed number.

ALTER TABLE skill ADD COLUMN first_published_at TIMESTAMP;

-- Backfill from the earliest recorded publication of any version. Yanked versions keep their
-- published_at, so the earliest publication survives later yanks and newer versions.
-- The guard keeps the backfill from touching a Skill that already has a first publication: this
-- column is set once and never moves, so an existing value must win over any recomputation.
UPDATE skill s
SET first_published_at = (
    SELECT MIN(v.published_at)
    FROM skill_version v
    WHERE v.skill_id = s.id
      AND v.published_at IS NOT NULL
)
WHERE s.first_published_at IS NULL;

CREATE INDEX idx_skill_first_published_at ON skill(first_published_at);

CREATE TABLE skill_download_daily (
    skill_id BIGINT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
    download_date DATE NOT NULL,
    download_count BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (skill_id, download_date)
);

-- No separate index on download_date: every read is the window subquery of the home rankings,
-- which is correlated by skill_id and therefore served by the leading column of the primary key.
