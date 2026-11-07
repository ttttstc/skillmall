-- Issue #9: administrator-granted official mark and a recorded takedown reason.
--
-- `skill.official` is an attestation by a local platform administrator. No upload, GitHub sync,
-- bundle import, promotion or discovery path writes it: those paths only prove where a package came
-- from, never that its author is official or that the package is safe. Keeping this as its own
-- column (instead of a label definition) is deliberate: a label's assignable audience depends on
-- its mutable `type`, so an `official` label demoted to RECOMMENDED would become self-assignable by
-- a Skill owner.
--
-- Existing rows are backfilled to FALSE rather than inferred from provenance. A skill imported from
-- a repository that looks authoritative still is not official until an administrator says so.
--
-- `skill.hidden_reason` is the durable reason of the takedown (the governance overlay `hidden`).
-- It is cleared together with the overlay on restore, and it never replaces the audit record: the
-- audit entry keeps the operator, the timestamp and its own copy of the reason even after the
-- overlay is lifted.

ALTER TABLE skill ADD COLUMN IF NOT EXISTS official BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE skill ADD COLUMN IF NOT EXISTS official_at TIMESTAMPTZ;
ALTER TABLE skill ADD COLUMN IF NOT EXISTS official_by VARCHAR(128);
ALTER TABLE skill ADD COLUMN IF NOT EXISTS hidden_reason VARCHAR(500);

UPDATE skill SET official = FALSE WHERE official IS NULL;
