package com.iflytek.skillhub.gitcode;

import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadata;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadataParser;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import com.iflytek.skillhub.domain.skill.validation.SkillPackagePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns a GitCode directory tree into skill packages without a second validation pipeline.
 *
 * <p>The traversal only walks {@code contents} listings; file bytes are read afterwards, so a
 * hostile or simply enormous repository is stopped by the depth, entry-count, and byte bounds
 * before it can materialize anything. The produced {@link PackageEntry} list is what the existing
 * publish pipeline validates and scans — this class adds no rules of its own.</p>
 */
@Component
public class GitcodeSkillScanner {

    private static final Logger log = LoggerFactory.getLogger(GitcodeSkillScanner.class);

    /** Repository depth (the scanned directory is 0) the traversal is allowed to descend. */
    static final int MAX_DEPTH = 6;
    /** Files the scanned subtree may contain; exceeding it means "not a skill repository shape". */
    static final int MAX_FILES = 300;
    /** Upper bound on tree entries inspected for one repository, so discovery stays bounded. */
    static final int MAX_TREE_ENTRIES = 5000;
    /** Directories the traversal may list, bounding API calls for wide repositories. */
    static final int MAX_DIRECTORIES = 300;
    static final long MAX_TOTAL_BYTES = 10L * 1024 * 1024;
    static final String SKILL_MD = SkillPackagePolicy.SKILL_MD_PATH;

    private final GitcodeClient client;
    private final SkillMetadataParser metadataParser;

    public GitcodeSkillScanner(GitcodeClient client, SkillMetadataParser metadataParser) {
        this.client = client;
        this.metadataParser = metadataParser;
    }

    /**
     * Directory layout of the scanned subtree: every file path and every directory holding a
     * {@code SKILL.md}. No file content has been read yet.
     */
    public record Tree(String basePath, List<String> filePaths, List<String> skillPaths) {

        public Tree {
            filePaths = List.copyOf(filePaths);
            skillPaths = List.copyOf(skillPaths);
        }

        /** Files of one skill directory, expressed relative to that directory. */
        public List<Entry> filesUnder(String skillPath) {
            String prefix = skillPath == null || skillPath.isBlank() ? "" : skillPath + "/";
            List<Entry> files = new ArrayList<>();
            for (String filePath : filePaths) {
                if (filePath.startsWith(prefix)) {
                    files.add(new Entry(filePath, filePath.substring(prefix.length())));
                }
            }
            return files;
        }
    }

    /** A repository file and the package-relative path it gets inside the skill. */
    public record Entry(String repositoryPath, String packagePath) {
    }

    /** One detected skill: parsed frontmatter plus the package it would publish. */
    public record ScannedSkill(String path, String name, String description, String version,
                               int fileCount, long totalBytes, List<PackageEntry> entries) {

        public ScannedSkill {
            entries = List.copyOf(entries);
        }
    }

    /** Walks the subtree and returns every skill directory it found. */
    public Tree walk(GitcodeClient.Budget budget, String owner, String repository, String ref, String path) {
        String basePath = normalizePath(path);
        List<String> filePaths = new ArrayList<>();
        Set<String> skillPaths = new LinkedHashSet<>();
        // One repository call instead of one call per directory: the anonymous API rate-limits
        // per-directory listing on real repositories long before the import budget runs out.
        int listedDirectories = 0;
        int scannedEntries = 0;
        for (GitcodeClient.Entry entry : client.tree(budget, owner, repository, ref)) {
            if (!withinBase(entry.path(), basePath)) {
                continue;
            }
            scannedEntries++;
            if (scannedEntries > MAX_TREE_ENTRIES) {
                // Guard memory and request volume for very large repositories; the per-skill file
                // limit is enforced when that skill is loaded, not repository-wide.
                throw new DomainBadRequestException(GitcodeErrors.TOO_MANY_FILES);
            }
            if (entry.isDirectory()) {
                listedDirectories++;
                if (listedDirectories > MAX_DIRECTORIES) {
                    throw new DomainBadRequestException(GitcodeErrors.TOO_MANY_FILES);
                }
                continue;
            }
            if (!entry.isFile()) {
                // Symlinks and submodules are not package files; publishing them as bytes would
                // misrepresent the repository.
                continue;
            }
            if (relativeDepth(entry.path(), basePath) > MAX_DEPTH) {
                // Too deep to belong to a skill package; skip instead of failing the whole import.
                continue;
            }
            filePaths.add(entry.path());
            if (SKILL_MD.equalsIgnoreCase(entry.name())) {
                skillPaths.add(parentOf(entry.path()));
            }
        }
        if (skillPaths.isEmpty()) {
            throw new DomainNotFoundException(GitcodeErrors.SKILL_MD_NOT_FOUND);
        }
        List<String> sortedSkillPaths = new ArrayList<>(skillPaths);
        sortedSkillPaths.sort(String::compareTo);
        return new Tree(basePath, filePaths, sortedSkillPaths);
    }

    private static boolean withinBase(String path, String basePath) {
        return basePath.isEmpty() || path.equals(basePath) || path.startsWith(basePath + "/");
    }

    private static int relativeDepth(String path, String basePath) {
        String relative = basePath.isEmpty() ? path : path.substring(basePath.length() + 1);
        return (int) relative.chars().filter(character -> character == '/').count();
    }

    private static String parentOf(String path) {
        int index = path.lastIndexOf('/');
        return index < 0 ? "" : path.substring(0, index);
    }

    /** One detected skill directory: parsed frontmatter plus how many files it would publish. */
    public record DiscoveredSkill(String path, String name, String description, String version, int fileCount) {
    }

    /** A repository tree and the skills found in it, so callers can load exactly one of them. */
    public record Discovery(Tree tree, List<DiscoveredSkill> skills) {
    }

    /**
     * Discovers every skill directory by reading only its {@code SKILL.md}.
     *
     * <p>Discovery must stay cheap: reading all files of every candidate exhausts the anonymous
     * rate limit on real repositories long before the import budget does. The full package is
     * loaded for the one directory the user actually previews or imports.</p>
     */
    public Discovery discover(GitcodeClient.Budget budget, String owner, String repository, String ref,
                              String path) {
        Tree tree = walk(budget, owner, repository, ref, path);
        List<DiscoveredSkill> skills = new ArrayList<>();
        for (String skillPath : tree.skillPaths()) {
            try {
                skills.add(metadata(budget, owner, repository, ref, tree, skillPath));
            } catch (DomainBadRequestException | DomainNotFoundException exception) {
                // One malformed SKILL.md must not hide the rest of the repository: real repositories
                // do ship directories whose SKILL.md lacks frontmatter. The import of such a
                // directory still fails loudly through the publish pipeline.
                log.warn("Skipping GitCode skill directory '{}': {}", skillPath, exception.getMessage());
            }
        }
        if (skills.isEmpty()) {
            throw new DomainNotFoundException(GitcodeErrors.SKILL_MD_NOT_FOUND);
        }
        return new Discovery(tree, skills);
    }

    /** Reads one skill's {@code SKILL.md} and counts its files from the tree; no other request. */
    private DiscoveredSkill metadata(GitcodeClient.Budget budget, String owner, String repository, String ref,
                                     Tree tree, String skillPath) {
        String skillMdRepositoryPath = skillPath.isEmpty() ? SKILL_MD : skillPath + "/" + SKILL_MD;
        GitcodeClient.FileContent content =
                client.readFile(budget, owner, repository, ref, skillMdRepositoryPath);
        SkillMetadata metadata = metadataParser.parse(new String(content.content(), StandardCharsets.UTF_8));
        return new DiscoveredSkill(skillPath, metadata.name(), metadata.description(), metadata.version(),
                tree.filesUnder(skillPath).size());
    }

    /** Reads every file of one skill directory and parses its frontmatter. */
    public ScannedSkill load(GitcodeClient.Budget budget, String owner, String repository, String ref,
                             Tree tree, String skillPath) {
        List<Entry> files = tree.filesUnder(skillPath);
        if (files.isEmpty()) {
            throw new DomainNotFoundException(GitcodeErrors.SKILL_MD_NOT_FOUND);
        }
        if (files.size() > MAX_FILES) {
            // Per-skill limit: one import publishes one skill package, so a large repository is
            // fine as long as the chosen directory stays within the package bounds.
            throw new DomainBadRequestException(GitcodeErrors.TOO_MANY_FILES);
        }
        List<PackageEntry> entries = new ArrayList<>(files.size());
        long totalBytes = 0;
        for (Entry file : files) {
            GitcodeClient.FileContent content =
                    client.readFile(budget, owner, repository, ref, file.repositoryPath());
            if (content.size() > GitcodeClient.MAX_SINGLE_FILE_BYTES) {
                throw new DomainBadRequestException(GitcodeErrors.TOO_LARGE);
            }
            totalBytes += content.size();
            if (totalBytes > MAX_TOTAL_BYTES) {
                throw new DomainBadRequestException(GitcodeErrors.TOO_LARGE);
            }
            entries.add(new PackageEntry(file.packagePath(), content.content(), content.size(),
                    contentType(file.packagePath())));
        }
        PackageEntry skillMd = entries.stream()
                .filter(entry -> SKILL_MD.equalsIgnoreCase(entry.path()))
                .findFirst()
                .orElseThrow(() -> new DomainNotFoundException(GitcodeErrors.SKILL_MD_NOT_FOUND));
        SkillMetadata metadata = metadataParser.parse(new String(skillMd.content(), StandardCharsets.UTF_8));
        return new ScannedSkill(skillPath == null ? "" : skillPath, metadata.name(), metadata.description(),
                metadata.version(), entries.size(), totalBytes, entries);
    }

    /** Walks once and loads every skill directory; used by the preview endpoint. */
    public List<ScannedSkill> scan(GitcodeClient.Budget budget, String owner, String repository, String ref,
                                   String path) {
        Tree tree = walk(budget, owner, repository, ref, path);
        List<ScannedSkill> skills = new ArrayList<>();
        for (String skillPath : tree.skillPaths()) {
            skills.add(load(budget, owner, repository, ref, tree, skillPath));
        }
        return skills;
    }

    private record Directory(String path, int depth) {
    }

    static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.contains("..")) {
            throw new DomainBadRequestException(GitcodeErrors.URL_INVALID);
        }
        return normalized;
    }

    /**
     * Mirrors the content types the archive extractor assigns, so a file imported from GitCode is
     * stored under the same content type as the same file uploaded as a ZIP.
     */
    static String contentType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".py")) return "text/x-python";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "application/x-yaml";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".xml")) return "application/xml";
        if (lower.endsWith(".js") || lower.endsWith(".cjs") || lower.endsWith(".mjs")) return "text/javascript";
        if (lower.endsWith(".ts")) return "text/typescript";
        if (lower.endsWith(".sh") || lower.endsWith(".bash") || lower.endsWith(".zsh")) return "text/x-shellscript";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".toml")) return "application/toml";
        return "application/octet-stream";
    }
}
