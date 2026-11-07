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
UPDATE skill s
SET first_published_at = (
    SELECT MIN(v.published_at)
    FROM skill_version v
    WHERE v.skill_id = s.id
      AND v.published_at IS NOT NULL
);

CREATE INDEX idx_skill_first_published_at ON skill(first_published_at);

CREATE TABLE skill_download_daily (
    skill_id BIGINT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
    download_date DATE NOT NULL,
    download_count BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (skill_id, download_date)
);

CREATE INDEX idx_skill_download_daily_date ON skill_download_daily(download_date);
