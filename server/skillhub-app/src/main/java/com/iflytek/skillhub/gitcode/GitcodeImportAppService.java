package com.iflytek.skillhub.gitcode;

import com.iflytek.skillhub.domain.audit.AuditDetail;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainConflictException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillVersion;
import com.iflytek.skillhub.domain.skill.SkillVersionRepository;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.service.SkillPublishService;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import com.iflytek.skillhub.dto.GitcodeInspectResponse;
import com.iflytek.skillhub.dto.PublishResponse;
import com.iflytek.skillhub.observability.RequestIdAccessor;
import com.iflytek.skillhub.service.AuditRequestContext;
import com.iflytek.skillhub.service.SkillPublishAppService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Orchestrates GitCode preview and import.
 *
 * <p>Preview resolves the address to a concrete commit and reports what the scan found. Import
 * builds the same {@link PackageEntry} list an archive upload would produce and hands it to
 * {@link SkillPublishAppService}, so validation, scanning, review, and version conflict semantics
 * stay in one place. The binding row is written in the same transaction as the publication: a
 * failed publish leaves no binding behind, and a failed binding write rolls the publication back.</p>
 *
 * <p>Version selection follows the design contract: a tag ref names the version, otherwise the
 * SKILL.md frontmatter must carry one. There is no synthesized version — an import without either
 * is refused.</p>
 */
@Service
public class GitcodeImportAppService {

    static final String AUDIT_ACTION = "SKILL_GITCODE_IMPORT";
    static final String VERSION_FIELD = "version";
    static final String MISSING_VERSION_CODE = "error.skill.metadata.requiredField.missing";
    static final String CONFLICT_CODE = "error.request.conflict";
    private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-fA-F]{7,40}");
    private static final Pattern PLAIN_VERSION = Pattern.compile("[A-Za-z0-9._+-]{1,64}");
    private static final String FRONTMATTER_DELIMITER = "---";

    private final GitcodeUrlParser urlParser;
    private final GitcodeClient client;
    private final GitcodeSkillScanner scanner;
    private final SkillPublishAppService publish;
    private final GitcodeSourceBindingRepository bindings;
    private final NamespaceRepository namespaces;
    private final SkillRepository skills;
    private final SkillVersionRepository versions;
    private final AuditLogService audit;
    private final RequestIdAccessor requestIds;
    private final Clock clock;

    public GitcodeImportAppService(GitcodeUrlParser urlParser, GitcodeClient client, GitcodeSkillScanner scanner,
                                   SkillPublishAppService publish, GitcodeSourceBindingRepository bindings,
                                   NamespaceRepository namespaces, SkillRepository skills,
                                   SkillVersionRepository versions, AuditLogService audit,
                                   RequestIdAccessor requestIds, Clock clock) {
        this.urlParser = urlParser;
        this.client = client;
        this.scanner = scanner;
        this.publish = publish;
        this.bindings = bindings;
        this.namespaces = namespaces;
        this.skills = skills;
        this.versions = versions;
        this.audit = audit;
        this.requestIds = requestIds;
        this.clock = clock;
    }

    /** A resolved revision: what {@code ref} means and which commit it points at. */
    public record Revision(String owner, String repository, String defaultBranch, String ref,
                           GitcodeRefType refType, String commitSha) {
    }

    /** Preview: resolve the address, discover the subtree, and describe every skill found. */
    public GitcodeInspectResponse inspect(String repoUrl, String ref, String path) {
        GitcodeUrlParser.ParsedRepositoryUrl parsed = urlParser.parse(repoUrl);
        GitcodeClient.Budget budget = GitcodeClient.Budget.startingNow(clock);
        Revision revision = resolve(budget, parsed, ref);
        String requestedPath = firstNonBlank(path, parsed.path());
        // Discovery reads one SKILL.md per candidate; only the pinned directory is loaded in full.
        GitcodeSkillScanner.Discovery discovery = scanner.discover(
                budget, revision.owner(), revision.repository(), revision.commitSha(), requestedPath);
        List<GitcodeInspectResponse.Skill> summaries = discovery.skills().stream()
                .map(skill -> new GitcodeInspectResponse.Skill(skill.path(), skill.name(), skill.version(),
                        skill.description(), skill.fileCount(), null))
                .toList();
        String pinnedPath = pinnedPath(discovery.skills(), requestedPath);
        List<GitcodeInspectResponse.File> files = null;
        String skillMdPath = null;
        String skillMd = null;
        if (pinnedPath != null) {
            GitcodeSkillScanner.ScannedSkill pinned = scanner.load(
                    budget, revision.owner(), revision.repository(), revision.commitSha(),
                    discovery.tree(), pinnedPath);
            files = pinned.entries().stream()
                    .map(entry -> new GitcodeInspectResponse.File(entry.path(), entry.size()))
                    .toList();
            PackageEntry skillMdEntry = pinned.entries().stream()
                    .filter(entry -> "SKILL.md".equalsIgnoreCase(entry.path()))
                    .findFirst()
                    .orElse(null);
            if (skillMdEntry != null) {
                skillMdPath = skillMdEntry.path();
                skillMd = new String(skillMdEntry.content(), StandardCharsets.UTF_8);
            }
        }
        return new GitcodeInspectResponse(revision.owner(), revision.repository(), revision.defaultBranch(),
                revision.ref(), revision.refType(), revision.commitSha(), summaries, files, skillMdPath, skillMd);
    }

    /** The skill the preview should render, or {@code null} while the caller still has to choose. */
    private String pinnedPath(List<GitcodeSkillScanner.DiscoveredSkill> skills, String path) {
        if (skills.size() == 1) {
            return skills.get(0).path();
        }
        String normalized = path == null ? "" : path.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return skills.stream()
                .map(GitcodeSkillScanner.DiscoveredSkill::path)
                .filter(normalized::equals)
                .findFirst()
                .orElse(null);
    }

    /**
     * Imports one skill directory of the repository into {@code namespace} through the existing
     * publish pipeline and records the binding.
     */
    @Transactional
    public PublishResponse importSkill(String namespace, String repoUrl, String ref, String path,
                                       SkillVisibility targetVisibility, boolean confirmWarnings, String displayName,
                                       String changelog, String requestedVersion, String actor, Set<String> roles,
                                       AuditRequestContext context) {
        Namespace targetNamespace = namespaces.findBySlug(namespace)
                .orElseThrow(() -> new DomainBadRequestException("error.namespace.slug.notFound", namespace));
        GitcodeUrlParser.ParsedRepositoryUrl parsed = urlParser.parse(repoUrl);
        GitcodeClient.Budget budget = GitcodeClient.Budget.startingNow(clock);
        Revision revision = resolve(budget, parsed, ref);
        String requestedPath = firstNonBlank(path, parsed.path());
        GitcodeSkillScanner.Tree tree = scanner.walk(
                budget, revision.owner(), revision.repository(), revision.commitSha(), requestedPath);
        String skillPath = selectSkillPath(tree, requestedPath);

        Optional<GitcodeSourceBinding> existing = bindings
                .findByNamespaceIdAndOwnerLoginAndRepositoryNameAndSkillPath(
                        targetNamespace.getId(), revision.owner(), revision.repository(), skillPath);
        PublishResponse unchanged = unchangedImport(existing, revision, targetVisibility, namespace);
        if (unchanged != null) {
            return unchanged;
        }

        GitcodeSkillScanner.ScannedSkill scanned = scanner.load(
                budget, revision.owner(), revision.repository(), revision.commitSha(), tree, skillPath);
        String version = resolveVersion(revision, scanned, requestedVersion);
        List<PackageEntry> entries = entriesWithVersion(scanned, version);

        SkillPublishService.PublishResult result = publish.publish(namespace, entries, actor, targetVisibility,
                roles, confirmWarnings, SkillPublishAppService.AvatarAction.KEEP, null, null,
                displayName, changelog, context);

        persistBinding(targetNamespace.getId(), revision, skillPath, targetVisibility, actor, result);
        recordAudit(actor, namespace, revision, skillPath, targetVisibility, result, context);

        return new PublishResponse(result.skillId(), namespace, result.slug(), result.version().getVersion(),
                result.version().getStatus().name(), fileCount(result.version()), totalSize(result.version()));
    }

    /**
     * Re-importing the same commit of the same directory returns the version that import already
     * published, instead of publishing (and therefore rejecting) a duplicate. Any other change —
     * newer commit, other visibility — goes through the normal publish path so existing conflict
     * semantics decide.
     */
    private PublishResponse unchangedImport(Optional<GitcodeSourceBinding> existing, Revision revision,
                                            SkillVisibility visibility, String namespace) {
        if (existing.isEmpty()) {
            return null;
        }
        GitcodeSourceBinding binding = existing.get();
        if (binding.getSkillVersionId() == null
                || !revision.commitSha().equalsIgnoreCase(binding.getCommitSha())
                || binding.getVisibility() != visibility) {
            return null;
        }
        Optional<SkillVersion> version = versions.findById(binding.getSkillVersionId());
        if (version.isEmpty()) {
            return null;
        }
        binding.recordImport(revision.ref(), revision.refType(), revision.commitSha(), visibility,
                binding.getSkillId(), binding.getSkillVersionId(), clock.instant());
        bindings.save(binding);
        SkillVersion imported = version.get();
        Skill skill = skills.findById(imported.getSkillId()).orElse(null);
        return new PublishResponse(imported.getSkillId(), namespace, skill == null ? null : skill.getSlug(),
                imported.getVersion(), imported.getStatus().name(), fileCount(imported), totalSize(imported));
    }

    private void persistBinding(long namespaceId, Revision revision, String skillPath, SkillVisibility visibility,
                                String actor, SkillPublishService.PublishResult result) {
        GitcodeSourceBinding binding = bindings
                .findByNamespaceIdAndOwnerLoginAndRepositoryNameAndSkillPath(
                        namespaceId, revision.owner(), revision.repository(), skillPath)
                .orElseGet(() -> new GitcodeSourceBinding(namespaceId, revision.owner(), revision.repository(),
                        skillPath, revision.ref(), revision.refType(), revision.commitSha(),
                        GitcodeLinkType.ONE_SHOT, visibility, actor, clock.instant()));
        binding.recordImport(revision.ref(), revision.refType(), revision.commitSha(), visibility,
                result.skillId(), result.version().getId(), clock.instant());
        try {
            bindings.save(binding);
            // Surface a concurrent first-import of the same coordinate here, in the import
            // transaction, rather than as a commit-time failure after the response was built.
            bindings.flush();
        } catch (DataIntegrityViolationException exception) {
            throw new DomainConflictException(CONFLICT_CODE);
        }
    }

    private void recordAudit(String actor, String namespace, Revision revision, String skillPath,
                             SkillVisibility visibility, SkillPublishService.PublishResult result,
                             AuditRequestContext context) {
        audit.record(actor, AUDIT_ACTION, "SKILL_VERSION", result.version().getId(), requestIds.current(),
                context == null ? null : context.clientIp(),
                context == null ? null : context.userAgent(),
                AuditDetail.builder()
                        .put("namespace", namespace)
                        .put("owner", revision.owner())
                        .put("repository", revision.repository())
                        .put("path", skillPath)
                        .put("ref", revision.ref())
                        .put("refType", revision.refType().name())
                        .put("commitSha", revision.commitSha())
                        .put("visibility", visibility.name())
                        .put("linkType", GitcodeLinkType.ONE_SHOT.name())
                        .put("skillId", result.skillId())
                        .put("version", result.version().getVersion())
                        .put("status", result.version().getStatus().name())
                        .build());
    }

    /**
     * Turns the requested ref into a concrete revision. A branch, tag, or commit sha is accepted;
     * anything else cannot name content, so the address itself is reported as invalid.
     */
    Revision resolve(GitcodeClient.Budget budget, GitcodeUrlParser.ParsedRepositoryUrl parsed, String requestedRef) {
        List<GitcodeClient.Branch> branches = client.listBranches(budget, parsed.owner(), parsed.repository());
        if (branches.isEmpty()) {
            throw new DomainNotFoundException(GitcodeErrors.REPO_NOT_FOUND);
        }
        String defaultBranch = branches.stream()
                .filter(GitcodeClient.Branch::defaultBranch)
                .map(GitcodeClient.Branch::name)
                .findFirst()
                .orElseGet(() -> branches.get(0).name());
        String ref = firstNonBlank(requestedRef, parsed.ref());
        if (ref == null) {
            GitcodeClient.Branch branch = branches.stream()
                    .filter(candidate -> candidate.name().equals(defaultBranch))
                    .findFirst()
                    .orElse(branches.get(0));
            return new Revision(parsed.owner(), parsed.repository(), defaultBranch, branch.name(),
                    GitcodeRefType.BRANCH, branch.commitSha());
        }
        for (GitcodeClient.Branch branch : branches) {
            if (branch.name().equals(ref)) {
                return new Revision(parsed.owner(), parsed.repository(), defaultBranch, ref,
                        GitcodeRefType.BRANCH, branch.commitSha());
            }
        }
        if (COMMIT_SHA.matcher(ref).matches()) {
            return new Revision(parsed.owner(), parsed.repository(), defaultBranch, ref,
                    GitcodeRefType.COMMIT, ref.toLowerCase(Locale.ROOT));
        }
        for (GitcodeClient.TagRef tag : client.listTags(budget, parsed.owner(), parsed.repository())) {
            if (tag.name().equals(ref)) {
                return new Revision(parsed.owner(), parsed.repository(), defaultBranch, ref,
                        GitcodeRefType.TAG, tag.commitSha());
            }
        }
        throw new DomainBadRequestException(GitcodeErrors.URL_INVALID);
    }

    private static String selectSkillPath(GitcodeSkillScanner.Tree tree, String requestedPath) {
        List<String> candidates = tree.skillPaths();
        String normalized = GitcodeSkillScanner.normalizePath(requestedPath);
        if (!normalized.isBlank() && candidates.contains(normalized)) {
            return normalized;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        throw new DomainBadRequestException(GitcodeErrors.SKILL_AMBIGUOUS);
    }

    private static String resolveVersion(Revision revision, GitcodeSkillScanner.ScannedSkill scanned,
                                         String requestedVersion) {
        if (revision.refType() == GitcodeRefType.TAG) {
            return revision.ref();
        }
        // Most real skill repositories carry neither a tag nor a frontmatter version, so the wizard
        // asks for one and the import writes it into SKILL.md instead of inventing a number.
        if (requestedVersion != null && !requestedVersion.isBlank()) {
            String trimmed = requestedVersion.trim();
            if (!PLAIN_VERSION.matcher(trimmed).matches()) {
                throw new DomainBadRequestException(GitcodeErrors.VERSION_INVALID);
            }
            return trimmed;
        }
        String version = scanned.version();
        if (version == null || version.isBlank()) {
            throw new DomainBadRequestException(MISSING_VERSION_CODE, VERSION_FIELD);
        }
        return version.trim();
    }

    /**
     * Aligns the package with the resolved version. Only a tag ref can disagree with the
     * frontmatter; in that case the tag names the version, so SKILL.md is rewritten — the stored
     * package must not claim a version other than the one it is published as.
     */
    private static List<PackageEntry> entriesWithVersion(GitcodeSkillScanner.ScannedSkill scanned, String version) {
        List<PackageEntry> entries = scanned.entries();
        if (version.equals(scanned.version())) {
            return entries;
        }
        List<PackageEntry> aligned = new ArrayList<>(entries.size());
        for (PackageEntry entry : entries) {
            if (GitcodeSkillScanner.SKILL_MD.equalsIgnoreCase(entry.path())) {
                byte[] content = withFrontmatterVersion(new String(entry.content(), StandardCharsets.UTF_8), version)
                        .getBytes(StandardCharsets.UTF_8);
                aligned.add(new PackageEntry(entry.path(), content, content.length, entry.contentType()));
            } else {
                aligned.add(entry);
            }
        }
        return aligned;
    }

    /**
     * Rewrites the top-level {@code version:} of a SKILL.md frontmatter block, inserting the field
     * when the frontmatter has none. The rest of the file is preserved line for line.
     */
    static String withFrontmatterVersion(String content, String version) {
        String newline = content.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = content.split("\\R", -1);
        int start = -1;
        int end = -1;
        for (int index = 0; index < lines.length; index++) {
            if (FRONTMATTER_DELIMITER.equals(lines[index].trim())) {
                if (start < 0) {
                    start = index;
                } else {
                    end = index;
                    break;
                }
            }
        }
        if (start < 0 || end < 0) {
            // The publish pipeline validates the frontmatter before this method is reached, so an
            // unparsable block cannot get here; leaving the content untouched never corrupts it.
            return content;
        }
        String versionLine = "version: " + quoteVersion(version);
        List<String> updated = new ArrayList<>(List.of(lines));
        int existing = -1;
        for (int index = start + 1; index < end; index++) {
            if (lines[index].startsWith(VERSION_FIELD) && isVersionAssignment(lines[index])) {
                existing = index;
                break;
            }
        }
        if (existing >= 0) {
            updated.set(existing, versionLine);
        } else {
            updated.add(end, versionLine);
        }
        return String.join(newline, updated);
    }

    private static boolean isVersionAssignment(String line) {
        if (!line.startsWith(VERSION_FIELD)) {
            return false;
        }
        // Only a column-zero key is the top-level version; a nested "  version:" under metadata
        // must survive untouched.
        return line.substring(VERSION_FIELD.length()).stripLeading().startsWith(":");
    }

    private static String quoteVersion(String version) {
        return PLAIN_VERSION.matcher(version).matches()
                ? version
                : "\"" + version.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static int fileCount(SkillVersion version) {
        return version.getFileCount() == null ? 0 : version.getFileCount();
    }

    private static long totalSize(SkillVersion version) {
        return version.getTotalSize() == null ? 0L : version.getTotalSize();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return null;
    }
}
