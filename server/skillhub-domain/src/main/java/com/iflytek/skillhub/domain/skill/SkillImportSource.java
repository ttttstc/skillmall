package com.iflytek.skillhub.domain.skill;

import java.time.Instant;
import java.util.Objects;

/**
 * Provenance of a skill version that was imported from an external Git host's pinned snapshot.
 *
 * <p>The record is immutable and deliberately stores the full commit SHA rather than a branch
 * name: a branch can move, the published content cannot. {@code contentDigest} is the digest of
 * the exact file set that was materialized for the version, so the published bytes can be
 * compared again later without re-downloading the snapshot.
 */
public record SkillImportSource(
        Long id,
        Long skillVersionId,
        String provider,
        String repoUrl,
        String repoOwner,
        String repoName,
        String branch,
        String commitSha,
        String skillPath,
        String contentDigest,
        String fileManifest,
        int fileCount,
        long totalSize,
        String importedBy,
        Instant createdAt) {

    public static final String PROVIDER_GITCODE = "GITCODE";

    public SkillImportSource {
        Objects.requireNonNull(skillVersionId, "skillVersionId");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(repoUrl, "repoUrl");
        Objects.requireNonNull(commitSha, "commitSha");
        Objects.requireNonNull(skillPath, "skillPath");
        Objects.requireNonNull(contentDigest, "contentDigest");
    }

    /** A provenance row that has not been persisted yet. */
    public static SkillImportSource unpublished(
            Long skillVersionId,
            String provider,
            String repoUrl,
            String repoOwner,
            String repoName,
            String branch,
            String commitSha,
            String skillPath,
            String contentDigest,
            String fileManifest,
            int fileCount,
            long totalSize,
            String importedBy) {
        return new SkillImportSource(
                null, skillVersionId, provider, repoUrl, repoOwner, repoName, branch, commitSha,
                skillPath, contentDigest, fileManifest, fileCount, totalSize, importedBy, null);
    }
}
