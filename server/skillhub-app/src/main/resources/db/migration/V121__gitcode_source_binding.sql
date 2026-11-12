-- GitCode imports keep their own provider table next to github_source_binding instead of a
-- shared abstraction (phase one). One namespace may import several skill directories of the
-- same repository, so the binding identity is (namespace, owner, repository, skill path).
CREATE TABLE gitcode_source_binding (
    id BIGSERIAL PRIMARY KEY,
    namespace_id BIGINT NOT NULL REFERENCES namespace(id),
    owner_login VARCHAR(128) NOT NULL,
    repository_name VARCHAR(256) NOT NULL,
    skill_path VARCHAR(512) NOT NULL DEFAULT '',
    git_ref VARCHAR(256) NOT NULL,
    ref_type VARCHAR(20) NOT NULL,
    commit_sha VARCHAR(64) NOT NULL,
    link_type VARCHAR(20) NOT NULL,
    visibility VARCHAR(20) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    last_imported_at TIMESTAMPTZ NOT NULL,
    skill_id BIGINT,
    skill_version_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_gitcode_source UNIQUE (namespace_id, owner_login, repository_name, skill_path)
);

CREATE INDEX idx_gitcode_binding_namespace ON gitcode_source_binding (namespace_id);
