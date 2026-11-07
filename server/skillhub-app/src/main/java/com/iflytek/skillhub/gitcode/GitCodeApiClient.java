package com.iflytek.skillhub.gitcode;

import java.util.List;

/**
 * Read-only access to a public GitCode repository at an exact commit.
 *
 * <p>Implementations must address content by commit SHA and never by branch name: the whole import
 * invariant is that a preview and its publication observe the same immutable snapshot.
 */
public interface GitCodeApiClient {

    /** Head of the repository's default branch plus the branch name when it can be determined. */
    RepositoryHead head(GitCodeRepoUrl repo);

    /** Entries of one directory at the given ref. The repository root is addressed with "". */
    List<DirectoryEntry> list(GitCodeRepoUrl repo, String path, String ref);

    /** Raw bytes of one file at the given ref. */
    byte[] readFile(GitCodeRepoUrl repo, String path, String ref);

    record RepositoryHead(String commit, String defaultBranch) {
    }

    /** {@code type} is the provider's own value: {@code tree}, {@code file} or {@code submodule}. */
    record DirectoryEntry(String type, String path, long size) {

        public boolean isDirectory() {
            return "tree".equals(type) || "dir".equals(type);
        }

        public boolean isFile() {
            return "file".equals(type) || "blob".equals(type);
        }

        public String name() {
            int slash = path.lastIndexOf('/');
            return slash < 0 ? path : path.substring(slash + 1);
        }
    }
}
