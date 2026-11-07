package com.iflytek.skillhub.dto;

/**
 * Publication request for an already previewed snapshot.
 *
 * <p>{@code commit} and {@code contentDigest} are mandatory: they carry the previewed facts into
 * publication so the default branch is never resolved a second time.
 */
public record GitCodeImportPublishRequest(
        String repoUrl,
        String commit,
        String branch,
        String path,
        String namespace,
        String visibility,
        String displayName,
        String changelog,
        String contentDigest) {
}
