package com.iflytek.skillhub.dto;

import java.time.Instant;

/**
 * Read-only provenance of an imported version: repository, skill path, full commit and the digest
 * of the exact file set that was published. Source repositories are never re-synchronised, so this
 * record describes the snapshot that was imported, not the repository's current state.
 */
public record SkillImportSourceResponse(
        String provider,
        String repoUrl,
        String repoOwner,
        String repoName,
        String branch,
        String commit,
        String skillPath,
        String contentDigest,
        int fileCount,
        long totalSize,
        String importedBy,
        Instant createdAt) {
}
