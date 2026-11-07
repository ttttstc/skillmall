package com.iflytek.skillhub.gitcode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory GitCode API double: one fake repository can hold several commits, so a test can move
 * the branch head forward and still serve the older pinned commit.
 */
public class FakeGitCodeApiClient implements GitCodeApiClient {

    private final Map<String, String> heads = new HashMap<>();
    private final Map<String, String> branches = new HashMap<>();
    private final Map<String, Map<String, List<DirectoryEntry>>> trees = new HashMap<>();
    private final Map<String, Map<String, byte[]>> blobs = new HashMap<>();
    private final Map<String, GitCodeApiException.Kind> failures = new HashMap<>();
    private int contentReads;

    public FakeGitCodeApiClient head(String ownerRepo, String commit, String branch) {
        heads.put(ownerRepo, commit);
        branches.put(ownerRepo, branch);
        return this;
    }

    public FakeGitCodeApiClient failWith(String ownerRepo, GitCodeApiException.Kind kind) {
        failures.put(ownerRepo, kind);
        return this;
    }

    public FakeGitCodeApiClient advanceHead(String ownerRepo, String commit) {
        heads.put(ownerRepo, commit);
        return this;
    }

    public FakeGitCodeApiClient dropCommit(String commit) {
        trees.remove(commit);
        blobs.remove(commit);
        return this;
    }

    /** Number of file-content reads served, so discovery can be proven to read no content. */
    public int contentReads() {
        return contentReads;
    }

    /** Registers one commit with its file contents keyed by repository-relative path. */
    public FakeGitCodeApiClient commit(String commitSha, Map<String, String> files) {
        Map<String, byte[]> content = new HashMap<>();
        files.forEach((path, text) -> content.put(path, text.getBytes(StandardCharsets.UTF_8)));
        return register(commitSha, content);
    }

    /** Registers one commit with raw bytes, for binary and non-text file cases. */
    public FakeGitCodeApiClient commitBytes(String commitSha, Map<String, byte[]> files) {
        return register(commitSha, Map.copyOf(files));
    }

    private FakeGitCodeApiClient register(String commitSha, Map<String, byte[]> content) {
        Map<String, List<DirectoryEntry>> directories = new LinkedHashMap<>();
        directories.put("", new ArrayList<>());
        content.forEach((path, bytes) -> registerEntry(directories, path, bytes.length));
        directories.values().forEach(entries -> entries.sort((left, right) -> left.path().compareTo(right.path())));
        trees.put(commitSha, directories);
        blobs.put(commitSha, content);
        return this;
    }

    private void registerEntry(Map<String, List<DirectoryEntry>> directories, String path, int size) {
        List<String> segments = Arrays.asList(path.split("/"));
        StringBuilder parent = new StringBuilder();
        for (int index = 0; index < segments.size() - 1; index++) {
            String directory = parent.isEmpty() ? "" : parent.toString();
            String child = parent.isEmpty() ? segments.get(index) : parent + "/" + segments.get(index);
            directories.computeIfAbsent(directory, key -> new ArrayList<>());
            if (directories.get(directory).stream().noneMatch(entry -> entry.path().equals(child))) {
                directories.get(directory).add(new DirectoryEntry("tree", child, 0));
            }
            directories.computeIfAbsent(child, key -> new ArrayList<>());
            parent.setLength(0);
            parent.append(child);
        }
        String directory = parent.toString();
        directories.computeIfAbsent(directory, key -> new ArrayList<>()).add(new DirectoryEntry("file", path, size));
    }

    @Override
    public RepositoryHead head(GitCodeRepoUrl repo) {
        GitCodeApiException.Kind failure = failures.get(repo.ownerRepoKey());
        if (failure != null) {
            throw new GitCodeApiException(failure, "fake failure");
        }
        String commit = heads.get(repo.ownerRepoKey());
        if (commit == null) {
            throw new GitCodeApiException(GitCodeApiException.Kind.NOT_FOUND, "no repository");
        }
        return new RepositoryHead(commit, branches.get(repo.ownerRepoKey()));
    }

    @Override
    public List<DirectoryEntry> list(GitCodeRepoUrl repo, String path, String ref) {
        Map<String, List<DirectoryEntry>> directories = trees.get(ref);
        if (directories == null) {
            throw new GitCodeApiException(GitCodeApiException.Kind.NOT_FOUND, "no commit " + ref);
        }
        List<DirectoryEntry> entries = directories.get(path);
        if (entries == null) {
            throw new GitCodeApiException(GitCodeApiException.Kind.NOT_FOUND, "no directory " + path);
        }
        return entries;
    }

    @Override
    public byte[] readFile(GitCodeRepoUrl repo, String path, String ref) {
        contentReads++;
        Map<String, byte[]> files = blobs.get(ref);
        byte[] content = files == null ? null : files.get(path);
        if (content == null) {
            throw new GitCodeApiException(GitCodeApiException.Kind.NOT_FOUND, "no file " + path);
        }
        return content;
    }
}
