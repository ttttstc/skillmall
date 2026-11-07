package com.iflytek.skillhub.dto;

/** Published version plus its persisted provenance. */
public record GitCodeImportPublishResponse(
        Long skillId,
        Long skillVersionId,
        String namespace,
        String slug,
        String version,
        String status,
        int fileCount,
        long totalSize,
        String repoUrl,
        String skillPath,
        String branch,
        String commit,
        String contentDigest) {
}
