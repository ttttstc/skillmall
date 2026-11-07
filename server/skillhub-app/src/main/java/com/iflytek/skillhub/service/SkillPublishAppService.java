package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.audit.AuditDetail;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.review.ReviewTaskRepository;
import com.iflytek.skillhub.domain.review.ReviewTaskStatus;
import org.springframework.beans.factory.annotation.Autowired;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadataParser;
import com.iflytek.skillhub.domain.namespace.SlugValidator;
import java.nio.charset.StandardCharsets;
import com.iflytek.skillhub.domain.skill.service.SkillPublishService;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import com.iflytek.skillhub.observability.RequestIdAccessor;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commits the skill version and the requested avatar action as one database operation. */
@Service
public class SkillPublishAppService {
    public enum AvatarAction { KEEP, REPLACE, REMOVE }
    private final SkillPublishService publications;
    private final SkillAvatarUploadService uploads;
    private final SkillAvatarAppService avatars;
    private final SkillMetadataParser metadata;
    private final AuditLogService audit;
    private final RequestIdAccessor requestIds;
    private ReviewTaskRepository reviews;
    @Autowired
    public void setReviews(ReviewTaskRepository reviews) { this.reviews = reviews; }

    /** Setter-injected so the category write is optional for callers that build this by hand. */
    private com.iflytek.skillhub.domain.skill.service.SkillDomainService domains;
    @Autowired
    public void setDomains(com.iflytek.skillhub.domain.skill.service.SkillDomainService domains) {
        this.domains = domains;
    }


    public SkillPublishAppService(SkillPublishService publications, SkillAvatarUploadService uploads,
                                  SkillAvatarAppService avatars, SkillMetadataParser metadata,
                                  AuditLogService audit, RequestIdAccessor requestIds) {
        this.publications = publications;
        this.uploads = uploads;
        this.avatars = avatars;
        this.metadata = metadata;
        this.audit = audit;
        this.requestIds = requestIds;
    }

    @Transactional
    public SkillPublishService.PublishResult publish(String namespace, List<PackageEntry> entries, String actor,
            SkillVisibility visibility, Set<String> roles, boolean confirmWarnings, AvatarAction action,
            String uploadId, String expectedSlug, AuditRequestContext context) {
        return publish(namespace, entries, actor, visibility, roles, confirmWarnings, action,
                uploadId, expectedSlug, null, context);
    }

    @Transactional
    public SkillPublishService.PublishResult publish(String namespace, List<PackageEntry> entries, String actor,
            SkillVisibility visibility, Set<String> roles, boolean confirmWarnings, AvatarAction action,
            String uploadId, String expectedSlug, String displayName, AuditRequestContext context) {
        return publish(namespace, entries, actor, visibility, roles, confirmWarnings, action,
                uploadId, expectedSlug, displayName, null, context);
    }

    @Transactional
    public SkillPublishService.PublishResult publish(String namespace, List<PackageEntry> entries, String actor,
            SkillVisibility visibility, Set<String> roles, boolean confirmWarnings, AvatarAction action,
            String uploadId, String expectedSlug, String displayName, String changelog,
            AuditRequestContext context) {
        return publish(namespace, entries, actor, visibility, roles, confirmWarnings, action,
                uploadId, expectedSlug, displayName, changelog, null, context);
    }

    /**
     * @param domain the first-level category chosen in the publish form, or {@code null} to keep
     *               whatever the Skill already has (a new Skill starts at
     *               {@link com.iflytek.skillhub.domain.skill.SkillDomain#DEFAULT}). It is written in
     *               the same transaction as the version, so a published skill can never be left
     *               categorised differently from what the publisher asked for.
     */
    @Transactional
    public SkillPublishService.PublishResult publish(String namespace, List<PackageEntry> entries, String actor,
            SkillVisibility visibility, Set<String> roles, boolean confirmWarnings, AvatarAction action,
            String uploadId, String expectedSlug, String displayName, String changelog,
            com.iflytek.skillhub.domain.skill.SkillDomain domain,
            AuditRequestContext context) {
        if (action == AvatarAction.REPLACE && (uploadId == null || uploadId.isBlank())
                || action != AvatarAction.REPLACE && uploadId != null && !uploadId.isBlank()) {
            throw new DomainBadRequestException("error.skill.avatar.invalidUpload");
        }
        if (action == AvatarAction.REPLACE || expectedSlug != null && !expectedSlug.isBlank()) {
            PackageEntry skillMd = entries.stream().filter(entry -> "SKILL.md".equals(entry.path())).findFirst()
                    .orElseThrow(() -> new DomainBadRequestException("error.skill.publish.skillMd.notFound"));
            String targetSlug = SlugValidator.slugify(metadata.parse(new String(skillMd.content(), StandardCharsets.UTF_8)).name());
            if (expectedSlug != null && !expectedSlug.isBlank() && !expectedSlug.equals(targetSlug)) {
                throw new DomainBadRequestException("error.skill.avatar.skillMismatch");
            }
            if (action == AvatarAction.REPLACE) {
                uploads.validateReference(namespace, targetSlug, actor, uploadId);
            }
        }
        boolean hasDisplayName = displayName != null && !displayName.isBlank();
        boolean hasChangelog = changelog != null && !changelog.isBlank();
        SkillPublishService.PublishResult result;
        if (hasChangelog) {
            result = publications.publishFromEntries(namespace, entries, actor, visibility, roles,
                    confirmWarnings, hasDisplayName ? displayName : null, changelog);
        } else if (hasDisplayName) {
            result = publications.publishFromEntries(namespace, entries, actor, visibility, roles,
                    confirmWarnings, displayName);
        } else {
            result = publications.publishFromEntries(namespace, entries, actor, visibility, roles, confirmWarnings);
        }
        recordPublishAudit(actor, namespace, visibility, result, context);
        if (domain != null && domains != null) {
            domains.applyForPublish(result.skillId(), domain);
        }
        var review = reviews.findBySkillVersionIdAndStatus(
                result.version().getId(), ReviewTaskStatus.PENDING).orElse(null);
        if (action == AvatarAction.REPLACE) {
            uploads.submitForReview(namespace, result.slug(), actor, uploadId, visibility, context, review);
        } else if (action == AvatarAction.REMOVE && review != null) {
            avatars.prepareRemoval(review, namespace, result.slug(), actor);
        } else if (action == AvatarAction.REMOVE) {
            avatars.remove(namespace, result.slug(), actor, false, null, Map.of(), roles, context);
        }
        if (action == AvatarAction.KEEP && review != null) {
            avatars.attachScopeToReview(review.getSkillId(), visibility, review, actor, context);
        }
        return result;
    }

    /**
     * Records the publish attempt itself, independent of whether the version waits for review:
     * a direct publication is no longer reviewed, so the audit trail is the remaining record of
     * who published which version under which visibility.
     */
    private void recordPublishAudit(String actor, String namespace, SkillVisibility visibility,
                                    SkillPublishService.PublishResult result, AuditRequestContext context) {
        audit.record(
                actor,
                "SKILL_PUBLISH",
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
                        .put("status", result.version().getStatus() == null ? null : result.version().getStatus().name())
                        .build());
    }
}
