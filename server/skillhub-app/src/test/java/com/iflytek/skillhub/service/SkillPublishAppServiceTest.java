package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.review.ReviewTaskRepository;
import com.iflytek.skillhub.domain.skill.SkillVersion;
import com.iflytek.skillhub.domain.skill.SkillVersionStatus;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadataParser;
import com.iflytek.skillhub.domain.skill.service.SkillPublishService;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import com.iflytek.skillhub.observability.RequestIdAccessor;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * The portal publish path no longer creates a review task for a direct publication, so the
 * publish audit entry is the remaining record of who released which version.
 */
class SkillPublishAppServiceTest {

    private final SkillPublishService publications = Mockito.mock(SkillPublishService.class);
    private final SkillAvatarUploadService uploads = Mockito.mock(SkillAvatarUploadService.class);
    private final SkillAvatarAppService avatars = Mockito.mock(SkillAvatarAppService.class);
    private final SkillMetadataParser metadata = Mockito.mock(SkillMetadataParser.class);
    private final AuditLogService audit = Mockito.mock(AuditLogService.class);
    private final RequestIdAccessor requestIds = Mockito.mock(RequestIdAccessor.class);
    private final ReviewTaskRepository reviews = Mockito.mock(ReviewTaskRepository.class);

    private SkillPublishAppService service() {
        SkillPublishAppService service = new SkillPublishAppService(
                publications, uploads, avatars, metadata, audit, requestIds);
        service.setReviews(reviews);
        return service;
    }

    private SkillVersion publishedVersion() {
        SkillVersion version = Mockito.mock(SkillVersion.class);
        Mockito.when(version.getId()).thenReturn(31L);
        Mockito.when(version.getVersion()).thenReturn("1.2.3");
        Mockito.when(version.getStatus()).thenReturn(SkillVersionStatus.PUBLISHED);
        return version;
    }

    @Test
    void recordsPublishAuditForDirectPublication() {
        SkillVersion version = publishedVersion();
        Mockito.when(publications.publishFromEntries(
                        ArgumentMatchers.eq("global"), ArgumentMatchers.anyList(), ArgumentMatchers.eq("user-1"),
                        ArgumentMatchers.eq(SkillVisibility.PUBLIC), ArgumentMatchers.anySet(),
                        ArgumentMatchers.eq(false)))
                .thenReturn(new SkillPublishService.PublishResult(9L, "demo-skill", version));
        Mockito.when(reviews.findBySkillVersionIdAndStatus(
                        ArgumentMatchers.eq(31L), ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        Mockito.when(requestIds.current()).thenReturn("req-1");

        List<PackageEntry> entries = List.of(new PackageEntry("SKILL.md", "x".getBytes(), 1, "text/markdown"));

        org.mockito.ArgumentCaptor<String> detailCaptor = org.mockito.ArgumentCaptor.forClass(String.class);

        service().publish("global", entries, "user-1", SkillVisibility.PUBLIC, Set.of("USER"), false,
                SkillPublishAppService.AvatarAction.KEEP, null, null,
                new AuditRequestContext("10.0.0.7", "junit-agent"));

        Mockito.verify(audit).record(
                ArgumentMatchers.eq("user-1"),
                ArgumentMatchers.eq("SKILL_PUBLISH"),
                ArgumentMatchers.eq("SKILL_VERSION"),
                ArgumentMatchers.eq(31L),
                ArgumentMatchers.eq("req-1"),
                ArgumentMatchers.eq("10.0.0.7"),
                ArgumentMatchers.eq("junit-agent"),
                detailCaptor.capture());

        String detail = detailCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertNotNull(detail);
        org.junit.jupiter.api.Assertions.assertTrue(detail.contains("\"visibility\":\"PUBLIC\""), detail);
        org.junit.jupiter.api.Assertions.assertTrue(detail.contains("\"status\":\"PUBLISHED\""), detail);
        org.junit.jupiter.api.Assertions.assertTrue(detail.contains("\"slug\":\"demo-skill\""), detail);
    }
}
