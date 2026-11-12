package com.iflytek.skillhub.dto;

import com.iflytek.skillhub.gitcode.GitcodeRefType;

import java.util.List;

/**
 * Preview of what an import would publish: the resolved revision plus every skill directory the
 * scan found. {@code version} stays {@code null} when neither a tag ref nor the SKILL.md
 * frontmatter supplies one — the import then refuses instead of inventing a version.
 */
public record GitcodeInspectResponse(
        String owner,
        String repository,
        String defaultBranch,
        String ref,
        GitcodeRefType refType,
        String commitSha,
        List<Skill> skills,
        List<File> files,
        String skillMdPath,
        String skillMd
) {

    public record Skill(
            String path,
            String name,
            String version,
            String description,
            int fileCount,
            /** Unknown during discovery (only SKILL.md is read); known after the preview loads a path. */
            Long totalBytes
    ) {
    }

    /** One file of the previewed skill directory, relative to that directory. */
    public record File(String path, long size) {
    }
}
