package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.audit.AuditDetail;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.skill.SkillImportSource;
import com.iflytek.skillhub.domain.skill.SkillImportSourceRepository;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.service.SkillPublishService;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import com.iflytek.skillhub.dto.GitCodeImportPublishResponse;
import com.iflytek.skillhub.gitcode.GitCodeRepoUrl;
import com.iflytek.skillhub.gitcode.GitCodeSnapshotMaterializer;
import com.iflytek.skillhub.observability.RequestIdAccessor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * Commits a GitCode snapshot as one skill version together with its provenance.
 *
 * <p>Publishing the version, storing the import source and recording the import audit entry share
 * a single transaction, so a failure in any step leaves no half-written skill, version or
 * provenance row behind.
 */
@Service
public class GitCodeImportPublisher {

    private final SkillPublishAppService publishService;
    private final SkillImportSourceRepository sources;
    private final AuditLogService audit;
    private final RequestIdAccessor requestIds;

    public GitCodeImportPublisher(SkillPublishAppService publishService,
                                  SkillImportSourceRepository sources,
                                  AuditLogService audit,
                                  RequestIdAccessor requestIds) {
        this.publishService = publishService;
        this.sources = sources;
        this.audit = audit;
        this.requestIds = requestIds;
    }

    @Transactional
    public GitCodeImportPublishResponse publish(GitCodeRepoUrl repo,
                                                String branch,
                                                String commit,
                                                GitCodeSnapshotMaterializer.Snapshot snapshot,
                                                String namespace,
                                                SkillVisibility visibility,
                                                String displayName,
                                                String changelog,
                                                String actor,
                                                Set<String> roles,
                                                AuditRequestContext context) {
        List<PackageEntry> entries = snapshot.files().stream()
                .map(file -> new PackageEntry(file.path(), file.content(), file.size(), null))
                .toList();

        SkillPublishService.PublishResult result = publishService.publish(
                namespace, entries, actor, visibility, roles, true,
                SkillPublishAppService.AvatarAction.KEEP, null, null, displayName, changelog, context);

        sources.save(SkillImportSource.unpublished(
                result.version().getId(),
                SkillImportSource.PROVIDER_GITCODE,
                repo.canonicalUrl(),
                repo.owner(),
                repo.repo(),
                branch,
                commit,
                snapshot.path(),
                snapshot.digest(),
                snapshot.manifestJson(),
                snapshot.fileCount(),
                snapshot.totalSize(),
                actor));

        audit.record(
                actor,
                "SKILL_IMPORT_GITCODE",
                "SKILL_VERSION",
                result.version().getId(),
                requestIds.current(),
                context == null ? null : context.clientIp(),
                context == null ? null : context.userAgent(),
                AuditDetail.builder()
                        .put("skillId", result.skillId())
                        .put("namespace", namespace)
                        .put("slug", result.slug())
                        .put("version", result.version().getVersion())
                        .put("visibility", visibility == null ? null : visibility.name())
                        .put("provider", SkillImportSource.PROVIDER_GITCODE)
                        .put("repoUrl", repo.canonicalUrl())
                        .put("commit", commit)
                        .put("skillPath", snapshot.path())
                        .put("contentDigest", snapshot.digest())
                        .build());

        return new GitCodeImportPublishResponse(
                result.skillId(),
                result.version().getId(),
                namespace,
                result.slug(),
                result.version().getVersion(),
                result.version().getStatus().name(),
                snapshot.fileCount(),
                snapshot.totalSize(),
                repo.canonicalUrl(),
                snapshot.path(),
                branch,
                commit,
                snapshot.digest());
    }
}
