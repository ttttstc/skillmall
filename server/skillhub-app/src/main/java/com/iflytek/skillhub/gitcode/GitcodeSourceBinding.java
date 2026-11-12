package com.iflytek.skillhub.gitcode;

import com.iflytek.skillhub.domain.skill.SkillVisibility;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * Records which GitCode repository, directory, and revision a namespace-local skill version was
 * imported from.
 *
 * <p>The table is a provider-specific sibling of {@code github_source_binding}: phase one only
 * needs import provenance, not a generic source abstraction. One binding exists per
 * {@code (namespace, owner, repository, skill path)} so the same repository can feed several
 * skills of one namespace.</p>
 */
@Entity
@Table(name = "gitcode_source_binding", uniqueConstraints =
        @UniqueConstraint(name = "uq_gitcode_source",
                columnNames = {"namespace_id", "owner_login", "repository_name", "skill_path"}))
public class GitcodeSourceBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "namespace_id", nullable = false)
    private Long namespaceId;

    @Column(name = "owner_login", nullable = false, length = 128)
    private String ownerLogin;

    @Column(name = "repository_name", nullable = false, length = 256)
    private String repositoryName;

    @Column(name = "skill_path", nullable = false, length = 512)
    private String skillPath = "";

    @Column(name = "git_ref", nullable = false, length = 256)
    private String gitRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "ref_type", nullable = false, length = 20)
    private GitcodeRefType refType;

    @Column(name = "commit_sha", nullable = false, length = 64)
    private String commitSha;

    @Enumerated(EnumType.STRING)
    @Column(name = "link_type", nullable = false, length = 20)
    private GitcodeLinkType linkType = GitcodeLinkType.ONE_SHOT;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    private SkillVisibility visibility;

    @Column(name = "created_by", nullable = false, length = 128)
    private String createdBy;

    @Column(name = "last_imported_at", nullable = false)
    private Instant lastImportedAt;

    @Column(name = "skill_id")
    private Long skillId;

    @Column(name = "skill_version_id")
    private Long skillVersionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GitcodeSourceBinding() {
    }

    public GitcodeSourceBinding(long namespaceId, String ownerLogin, String repositoryName, String skillPath,
                                String gitRef, GitcodeRefType refType, String commitSha,
                                GitcodeLinkType linkType, SkillVisibility visibility, String createdBy,
                                Instant lastImportedAt) {
        if (namespaceId <= 0) {
            throw new IllegalArgumentException("namespace id must be positive");
        }
        this.namespaceId = namespaceId;
        this.ownerLogin = require(ownerLogin, "repository owner", 128);
        this.repositoryName = require(repositoryName, "repository name", 256);
        this.skillPath = normalizeSkillPath(skillPath);
        this.gitRef = require(gitRef, "git ref", 256);
        this.refType = requireNonNull(refType, "ref type");
        this.commitSha = require(commitSha, "commit sha", 64);
        this.linkType = requireNonNull(linkType, "link type");
        this.visibility = requireNonNull(visibility, "visibility");
        this.createdBy = require(createdBy, "creator", 128);
        this.lastImportedAt = requireNonNull(lastImportedAt, "last imported at");
    }

    /**
     * Points the binding at the revision and skill version that the latest successful import
     * produced. Called only after the publish pipeline returned a version.
     */
    public void recordImport(String gitRef, GitcodeRefType refType, String commitSha,
                             SkillVisibility visibility, Long skillId, Long skillVersionId,
                             Instant importedAt) {
        this.gitRef = require(gitRef, "git ref", 256);
        this.refType = requireNonNull(refType, "ref type");
        this.commitSha = require(commitSha, "commit sha", 64);
        this.visibility = requireNonNull(visibility, "visibility");
        this.skillId = skillId;
        this.skillVersionId = skillVersionId;
        this.lastImportedAt = requireNonNull(importedAt, "last imported at");
    }

    /** The stored path uses an empty string for "repository root", never {@code null}. */
    static String normalizeSkillPath(String skillPath) {
        if (skillPath == null) {
            return "";
        }
        String trimmed = skillPath.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.length() > 512) {
            throw new IllegalArgumentException("skill path must not exceed 512 characters");
        }
        return trimmed;
    }

    private static String require(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
        }
        return trimmed;
    }

    private static <T> T requireNonNull(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getNamespaceId() {
        return namespaceId;
    }

    public String getOwnerLogin() {
        return ownerLogin;
    }

    public String getRepositoryName() {
        return repositoryName;
    }

    public String getSkillPath() {
        return skillPath;
    }

    public String getGitRef() {
        return gitRef;
    }

    public GitcodeRefType getRefType() {
        return refType;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public GitcodeLinkType getLinkType() {
        return linkType;
    }

    public SkillVisibility getVisibility() {
        return visibility;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getLastImportedAt() {
        return lastImportedAt;
    }

    public Long getSkillId() {
        return skillId;
    }

    public Long getSkillVersionId() {
        return skillVersionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
