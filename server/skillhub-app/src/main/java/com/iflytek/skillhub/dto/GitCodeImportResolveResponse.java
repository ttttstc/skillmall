package com.iflytek.skillhub.dto;

import java.util.List;

/** Result of resolving one commit of a GitCode repository, optionally with a selected skill root. */
public record GitCodeImportResolveResponse(
        String provider,
        String repoUrl,
        String owner,
        String repo,
        String defaultBranch,
        String commit,
        List<Candidate> candidates,
        Selected selected) {

    /**
     * One importable skill root. Only the repository-relative path is reported here: counting a
     * candidate's files would need a full traversal per candidate, which multiplies anonymous
     * provider calls on a repository that holds dozens of skills.
     */
    public record Candidate(String path) {
    }

    public record Selected(String path, String contentDigest, int fileCount, long totalSize,
                           List<File> files) {
    }

    public record File(String path, long size, String sha256) {
    }
}
