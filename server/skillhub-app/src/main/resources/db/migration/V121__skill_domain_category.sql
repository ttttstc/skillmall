-- Issue #5: one fixed first-level category per Skill.
--
-- The column is nullable on purpose. The six enum values live in code (SkillDomain) and in the
-- UI, so the database must not be the place that decides the catalogue: a CHECK constraint would
-- force a migration every time the product adds a category. Nullable also keeps this migration
-- safe to apply while an older instance is still writing rows.
--
-- Existing rows are backfilled to OTHER rather than guessed: the repository has no trustworthy
-- signal to infer a category for a skill published before this field existed, and a wrong guess
-- is worse than an honest "other" the owner can correct from the edit page.

ALTER TABLE skill ADD COLUMN IF NOT EXISTS domain VARCHAR(32) DEFAULT 'OTHER';

UPDATE skill SET domain = 'OTHER' WHERE domain IS NULL;

-- Filtering is always combined with the public-distribution predicate (status/hidden), so the
-- index is partial and matches that predicate instead of indexing rows nobody can discover.
CREATE INDEX IF NOT EXISTS idx_skill_domain_public
    ON skill (domain)
    WHERE status = 'ACTIVE' AND hidden = FALSE;
