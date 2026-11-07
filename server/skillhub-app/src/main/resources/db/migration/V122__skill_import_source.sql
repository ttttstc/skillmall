-- Provenance for versions imported from a pinned snapshot of an external Git host (issue #4).
-- One row per skill_version: the published version is traceable to repository, path, full commit
-- SHA and a content digest, and never resynchronised automatically by any background task.
CREATE TABLE skill_import_source (
    id BIGSERIAL PRIMARY KEY,
    skill_version_id BIGINT NOT NULL UNIQUE REFERENCES skill_version(id) ON DELETE CASCADE,
    provider VARCHAR(32) NOT NULL,
    repo_url VARCHAR(512) NOT NULL,
    repo_owner VARCHAR(128) NOT NULL,
    repo_name VARCHAR(256) NOT NULL,
    branch VARCHAR(256),
    commit_sha CHAR(40) NOT NULL,
    skill_path VARCHAR(512) NOT NULL,
    content_digest CHAR(64) NOT NULL,
    file_manifest JSONB NOT NULL,
    file_count INT NOT NULL,
    total_size BIGINT NOT NULL,
    imported_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_skill_import_source_repo
    ON skill_import_source (provider, repo_owner, repo_name, commit_sha);
