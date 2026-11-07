package com.iflytek.skillhub.gitcode;

import com.iflytek.skillhub.config.SkillPublishProperties;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.skill.validation.SkillPackagePolicy;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns a GitCode repository at one pinned commit into either a list of skill roots or a
 * fully materialized file set with a stable content digest.
 *
 * <p>This is the only place that talks to the provider, so every provider failure is translated
 * here into one explicit, localized error before any write happens. Discovery never descends into a
 * directory that is already a skill root and never reads file content, so previewing a repository
 * with dozens of skills stays a bounded number of provider calls.
 */
@Component
public class GitCodeSnapshotMaterializer {

    public static final String SKILL_MD = "SKILL.md";

    private final GitCodeApiClient client;
    private final GitCodeImportProperties properties;
    private final SkillPublishProperties publishProperties;

    public GitCodeSnapshotMaterializer(GitCodeApiClient client,
                                       GitCodeImportProperties properties,
                                       SkillPublishProperties publishProperties) {
        this.client = client;
        this.properties = properties;
        this.publishProperties = publishProperties;
    }

    /** A repository-relative directory that contains a {@code SKILL.md}. */
    public record Candidate(String path) {
    }

    /** One file of a materialized snapshot; {@code path} is relative to the skill root. */
    public record FileBlob(String path, long size, String sha256, byte[] content) {
    }

    public record Snapshot(String path, String commit, List<FileBlob> files, String digest,
                           String manifestJson, int fileCount, long totalSize) {
    }

    /** Resolves the default branch head. Branches are advisory; the returned commit is the fact. */
    public GitCodeApiClient.RepositoryHead head(GitCodeRepoUrl repo) {
        try {
            return client.head(repo);
        } catch (GitCodeApiException exception) {
            throw translate(exception, false);
        }
    }

    /** Discovers every skill root below the repository root at {@code commit}. */
    public List<Candidate> discover(GitCodeRepoUrl repo, String commit) {
        Deque<String> pending = new ArrayDeque<>();
        pending.add("");
        List<Candidate> candidates = new ArrayList<>();
        int directories = 0;
        while (!pending.isEmpty()) {
            String directory = pending.removeFirst();
            int depth = directory.isEmpty() ? 0 : directory.split("/").length;
            if (depth > properties.getMaxDepth()) {
                continue;
            }
            if (++directories > properties.getMaxDirectories()) {
                throw new DomainBadRequestException("error.skill.import.limit.exceeded",
                        properties.getMaxDirectories());
            }
            List<GitCodeApiClient.DirectoryEntry> entries = list(repo, directory, commit);
            boolean isSkillRoot = entries.stream()
                    .anyMatch(entry -> entry.isFile() && SKILL_MD.equals(entry.name()));
            if (isSkillRoot) {
                candidates.add(new Candidate(directory));
                continue;
            }
            entries.stream()
                    .filter(GitCodeApiClient.DirectoryEntry::isDirectory)
                    .map(GitCodeApiClient.DirectoryEntry::path)
                    .sorted()
                    .forEach(pending::addLast);
        }
        candidates.sort(Comparator.comparing(Candidate::path));
        return candidates;
    }

    /** Reads every file below {@code skillPath} at {@code commit} and hashes it. */
    public Snapshot materialize(GitCodeRepoUrl repo, String commit, String skillPath) {
        String normalized = normalizePath(skillPath);
        List<GitCodeApiClient.DirectoryEntry> entries = list(repo, normalized, commit);
        boolean hasSkillMd = entries.stream()
                .anyMatch(entry -> entry.isFile() && SKILL_MD.equals(entry.name()));
        if (!hasSkillMd) {
            throw new DomainBadRequestException("error.skill.import.skill.notFound", normalized);
        }
        List<GitCodeApiClient.DirectoryEntry> files = collectFiles(repo, commit, normalized);
        List<FileBlob> blobs = new ArrayList<>(files.size());
        long totalSize = 0;
        for (GitCodeApiClient.DirectoryEntry file : files) {
            byte[] content = readFile(repo, file.path(), commit);
            // GitCode reports no size for listed files, so the byte count below is authoritative and
            // the provider value is only used when it is actually present.
            if (file.size() > 0 && content.length != file.size()) {
                throw new DomainBadRequestException("error.skill.import.file.invalid", file.path());
            }
            if (content.length > publishProperties.getMaxSingleFileSize()) {
                throw new DomainBadRequestException("error.skill.import.limit.exceeded",
                        publishProperties.getMaxSingleFileSize());
            }
            totalSize += content.length;
            if (totalSize > publishProperties.getMaxPackageSize()) {
                throw new DomainBadRequestException("error.skill.import.limit.exceeded",
                        publishProperties.getMaxPackageSize());
            }
            String relative = normalized.isEmpty()
                    ? file.path()
                    : file.path().substring(normalized.length() + 1);
            // The provider owns these paths; re-validate them as skill-root-relative content so a
            // hostile or buggy response cannot smuggle traversal into the published package.
            if (!relative.equals(normalizePath(relative))) {
                throw new DomainBadRequestException("error.skill.import.path.unsafe", file.path());
            }
            validateFileContent(relative, content);
            blobs.add(new FileBlob(relative, content.length, sha256Hex(content), content));
        }
        blobs.sort(Comparator.comparing(FileBlob::path));
        return new Snapshot(normalized, commit, List.copyOf(blobs), digestOf(blobs),
                manifestJsonOf(blobs), blobs.size(), totalSize);
    }

    /**
     * Rejects content that could not be published as a skill file: binary payloads hiding behind a
     * text extension, content that contradicts its own extension, and file types outside the
     * package allowlist. Extensionless files are accepted only when they are valid UTF-8 text.
     */
    void validateFileContent(String relativePath, byte[] content) {
        String mismatch = SkillPackagePolicy.validateContentMatchesExtension(relativePath, content);
        if (mismatch != null) {
            throw new DomainBadRequestException("error.skill.import.file.invalid", relativePath);
        }
        String extension = extensionOf(relativePath);
        if (extension == null) {
            if (!SkillPackagePolicy.isUtf8Text(content)) {
                throw new DomainBadRequestException("error.skill.import.file.binary", relativePath);
            }
            return;
        }
        if (!allowedExtensions().contains(extension)) {
            throw new DomainBadRequestException("error.skill.import.file.unsupported", relativePath);
        }
    }

    private Set<String> allowedExtensions() {
        return publishProperties.getAllowedFileExtensions().stream()
                .map(extension -> extension.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Lower-case dotted extension of the last path segment, or {@code null} when there is none. */
    static String extensionOf(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private List<GitCodeApiClient.DirectoryEntry> collectFiles(GitCodeRepoUrl repo, String commit, String root) {
        Deque<String> pending = new ArrayDeque<>();
        pending.add(root);
        List<GitCodeApiClient.DirectoryEntry> files = new ArrayList<>();
        int directories = 0;
        long totalSize = 0;
        while (!pending.isEmpty()) {
            String directory = pending.removeFirst();
            int depth = directory.isEmpty() ? 0 : directory.split("/").length;
            if (depth - (root.isEmpty() ? 0 : root.split("/").length) > properties.getMaxDepth()) {
                throw new DomainBadRequestException("error.skill.import.limit.exceeded",
                        properties.getMaxDepth());
            }
            if (++directories > properties.getMaxDirectories()) {
                throw new DomainBadRequestException("error.skill.import.limit.exceeded",
                        properties.getMaxDirectories());
            }
            for (GitCodeApiClient.DirectoryEntry entry : list(repo, directory, commit)) {
                if (!entry.path().equals(normalizePath(entry.path()))) {
                    throw new DomainBadRequestException("error.skill.import.path.unsafe", entry.path());
                }
                if (entry.isDirectory()) {
                    pending.addLast(entry.path());
                } else if (entry.isFile()) {
                    if (files.size() >= publishProperties.getMaxFileCount()) {
                        throw new DomainBadRequestException("error.skill.import.limit.exceeded",
                                publishProperties.getMaxFileCount());
                    }
                    if (entry.size() > 0 && entry.size() > publishProperties.getMaxSingleFileSize()) {
                        throw new DomainBadRequestException("error.skill.import.limit.exceeded",
                                publishProperties.getMaxSingleFileSize());
                    }
                    if (entry.size() > 0) {
                        totalSize += entry.size();
                        if (totalSize > publishProperties.getMaxPackageSize()) {
                            throw new DomainBadRequestException("error.skill.import.limit.exceeded",
                                    publishProperties.getMaxPackageSize());
                        }
                    }
                    files.add(entry);
                } else {
                    // Submodules and symlinks cannot be published as content: a symlink would let
                    // the published package point outside the skill root.
                    throw new DomainBadRequestException("error.skill.import.file.invalid", entry.path());
                }
            }
        }
        files.sort(Comparator.comparing(GitCodeApiClient.DirectoryEntry::path));
        return files;
    }

    private List<GitCodeApiClient.DirectoryEntry> list(GitCodeRepoUrl repo, String path, String commit) {
        try {
            return client.list(repo, path, commit);
        } catch (GitCodeApiException exception) {
            throw translate(exception, true);
        }
    }

    private byte[] readFile(GitCodeRepoUrl repo, String path, String commit) {
        try {
            return client.readFile(repo, path, commit);
        } catch (GitCodeApiException exception) {
            throw translate(exception, true);
        }
    }

    private DomainBadRequestException translate(GitCodeApiException exception, boolean pinnedCommit) {
        return switch (exception.kind()) {
            case NOT_FOUND -> new DomainBadRequestException(pinnedCommit
                    ? "error.skill.import.snapshot.unavailable"
                    : "error.skill.import.source.notFound");
            case FORBIDDEN -> new DomainBadRequestException("error.skill.import.source.notPublic");
            case RATE_LIMITED -> new DomainBadRequestException("error.skill.import.source.busy");
            case UNAVAILABLE -> new DomainBadRequestException("error.skill.import.source.unreachable");
            case INVALID_RESPONSE -> new DomainBadRequestException("error.skill.import.source.unreachable");
        };
    }

    /** Rejects absolute paths, parent traversal, empty segments and over-long paths. */
    public String normalizePath(String rawPath) {
        if (rawPath == null) {
            return "";
        }
        String path = rawPath.trim().replace('\\', '/');
        if (path.isEmpty()) {
            return "";
        }
        if (path.length() > properties.getMaxPathLength()) {
            throw new DomainBadRequestException("error.skill.import.path.unsafe", path);
        }
        if (path.startsWith("/") || path.contains(":") || path.startsWith("~")) {
            throw new DomainBadRequestException("error.skill.import.path.unsafe", path);
        }
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) {
                throw new DomainBadRequestException("error.skill.import.path.unsafe", path);
            }
            if (".".equals(segment) || "..".equals(segment)) {
                throw new DomainBadRequestException("error.skill.import.path.unsafe", path);
            }
            if (segment.length() > 255 || segment.chars().anyMatch(Character::isISOControl)) {
                throw new DomainBadRequestException("error.skill.import.path.unsafe", path);
            }
            segments.add(segment);
        }
        return String.join("/", segments);
    }

    /** Stable rollup: SHA-256 over sorted "{path}\n{fileSha256}\n" lines. */
    static String digestOf(List<FileBlob> files) {
        MessageDigest digest = newDigest();
        files.stream().sorted(Comparator.comparing(FileBlob::path)).forEach(file -> {
            digest.update(file.path().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(file.sha256().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        });
        return HexFormat.of().formatHex(digest.digest());
    }

    static String manifestJsonOf(List<FileBlob> files) {
        StringBuilder builder = new StringBuilder("[");
        List<FileBlob> sorted = files.stream().sorted(Comparator.comparing(FileBlob::path)).toList();
        for (int index = 0; index < sorted.size(); index++) {
            FileBlob file = sorted.get(index);
            if (index > 0) {
                builder.append(',');
            }
            builder.append("{\"path\":").append(jsonString(file.path()))
                    .append(",\"size\":").append(file.size())
                    .append(",\"sha256\":").append(jsonString(file.sha256()))
                    .append('}');
        }
        return builder.append(']').toString();
    }

    private static String jsonString(String value) {
        StringBuilder builder = new StringBuilder("\"");
        for (char character : value.toCharArray()) {
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (character < 0x20) {
                        builder.append(String.format("\\u%04x", (int) character));
                    } else {
                        builder.append(character);
                    }
                }
            }
        }
        return builder.append('"').toString();
    }

    static String sha256Hex(byte[] content) {
        return HexFormat.of().formatHex(newDigest().digest(content));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /** Exposed for callers that only need the provider handle set; kept tiny on purpose. */
    public Map<String, String> describeProvider() {
        return Map.of("provider", "GITCODE", "api", properties.getApiBaseUrl());
    }
}
