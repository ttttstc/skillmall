package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.skill.SkillImportSource;
import com.iflytek.skillhub.domain.skill.SkillImportSourceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** JDBC adapter for import provenance rows attached to a skill version. */
@Repository
public class JdbcSkillImportSourceRepository implements SkillImportSourceRepository {

    private static final String COLUMNS = """
            id, skill_version_id, provider, repo_url, repo_owner, repo_name, branch, commit_sha,
            skill_path, content_digest, file_manifest::text AS file_manifest, file_count,
            total_size, imported_by, created_at
            """;

    private final JdbcTemplate jdbc;

    public JdbcSkillImportSourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public SkillImportSource save(SkillImportSource source) {
        List<SkillImportSource> saved = jdbc.query("""
                INSERT INTO skill_import_source (
                    skill_version_id, provider, repo_url, repo_owner, repo_name, branch, commit_sha,
                    skill_path, content_digest, file_manifest, file_count, total_size, imported_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                RETURNING %s
                """.formatted(COLUMNS), MAPPER,
                source.skillVersionId(), source.provider(), source.repoUrl(), source.repoOwner(),
                source.repoName(), source.branch(), source.commitSha(), source.skillPath(),
                source.contentDigest(), source.fileManifest(), source.fileCount(),
                source.totalSize(), source.importedBy());
        return saved.getFirst();
    }

    @Override
    public Optional<SkillImportSource> findBySkillVersionId(Long skillVersionId) {
        return jdbc.query("SELECT %s FROM skill_import_source WHERE skill_version_id = ?"
                        .formatted(COLUMNS), MAPPER, skillVersionId)
                .stream().findFirst();
    }

    private static final RowMapper<SkillImportSource> MAPPER = (rs, rowNum) -> {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Instant createdAtInstant = createdAt == null ? null : createdAt.toInstant();
        return new SkillImportSource(
                rs.getLong("id"),
                rs.getLong("skill_version_id"),
                rs.getString("provider"),
                rs.getString("repo_url"),
                rs.getString("repo_owner"),
                rs.getString("repo_name"),
                rs.getString("branch"),
                rs.getString("commit_sha"),
                rs.getString("skill_path"),
                rs.getString("content_digest"),
                rs.getString("file_manifest"),
                rs.getInt("file_count"),
                rs.getLong("total_size"),
                rs.getString("imported_by"),
                createdAtInstant);
    };
}
