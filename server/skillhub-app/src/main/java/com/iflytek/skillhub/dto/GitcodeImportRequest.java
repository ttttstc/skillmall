package com.iflytek.skillhub.dto;

/**
 * Import request for one skill directory of a GitCode repository.
 *
 * <p>{@code visibility} follows the local upload rules. {@code confirmWarnings} has the same
 * meaning as on the archive upload endpoint: publish-time warnings must be acknowledged
 * explicitly instead of being ignored. {@code version} is only needed when neither a tag ref nor
 * the SKILL.md frontmatter names one; it is then written into the imported frontmatter so the
 * published version is the one the user saw.</p>
 */
public record GitcodeImportRequest(
        String repoUrl,
        String ref,
        String path,
        String visibility,
        Boolean confirmWarnings,
        String displayName,
        String changelog,
        String version
) {
}
