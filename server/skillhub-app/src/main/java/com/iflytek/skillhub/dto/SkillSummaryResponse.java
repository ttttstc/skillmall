package com.iflytek.skillhub.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record SkillSummaryResponse(
        Long id,
        String slug,
        String displayName,
        String summary,
        String visibility,
        String status,
        Long downloadCount,
        Integer starCount,
        BigDecimal ratingAvg,
        Integer ratingCount,
        String namespace,
        Instant updatedAt,
        String ownerId,
        String ownerDisplayName,
        boolean canSubmitPromotion,
        SkillLifecycleVersionResponse headlineVersion,
        SkillLifecycleVersionResponse publishedVersion,
        SkillLifecycleVersionResponse ownerPreviewVersion,
        String resolutionMode,
        ComplianceSnapshotResponse complianceSnapshot,
        /**
         * Labels attached to the skill, present only when the caller asked for them.
         * Left out of the payload otherwise, so responses are unchanged for callers
         * that do not opt in.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        List<SkillLabelDto> labels,
        String avatarUrl,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ManagementState managementState,
        boolean hasVisibleSuites,
        /**
         * First-level marketplace category. Listing pages show it as a chip, so carrying it on the
         * summary saves them a second lookup per card. Left out of the payload when the caller's
         * projection does not carry one, which keeps responses unchanged for those callers.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String domain
) {

    public SkillSummaryResponse(
            Long id, String slug, String displayName, String summary, String visibility, String status,
            Long downloadCount, Integer starCount, BigDecimal ratingAvg, Integer ratingCount,
            String namespace, Instant updatedAt, String ownerId, String ownerDisplayName,
            boolean canSubmitPromotion, SkillLifecycleVersionResponse headlineVersion,
            SkillLifecycleVersionResponse publishedVersion, SkillLifecycleVersionResponse ownerPreviewVersion,
            String resolutionMode, ComplianceSnapshotResponse complianceSnapshot, List<SkillLabelDto> labels,
            String avatarUrl, ManagementState managementState) {
        this(id, slug, displayName, summary, visibility, status, downloadCount, starCount, ratingAvg,
                ratingCount, namespace, updatedAt, ownerId, ownerDisplayName, canSubmitPromotion,
                headlineVersion, publishedVersion, ownerPreviewVersion, resolutionMode, complianceSnapshot,
                labels, avatarUrl, managementState, false, null);
    }

    public SkillSummaryResponse withVisibleSuites(boolean hasVisibleSuites) {
        return new SkillSummaryResponse(id, slug, displayName, summary, visibility, status, downloadCount,
                starCount, ratingAvg, ratingCount, namespace, updatedAt, ownerId, ownerDisplayName,
                canSubmitPromotion, headlineVersion, publishedVersion, ownerPreviewVersion,
                resolutionMode, complianceSnapshot, labels, avatarUrl, managementState, hasVisibleSuites,
                domain);
    }

    /** Attaches the marketplace category; callers that do not set it keep the field out of the payload. */
    public SkillSummaryResponse withDomain(String domain) {
        return new SkillSummaryResponse(id, slug, displayName, summary, visibility, status, downloadCount,
                starCount, ratingAvg, ratingCount, namespace, updatedAt, ownerId, ownerDisplayName,
                canSubmitPromotion, headlineVersion, publishedVersion, ownerPreviewVersion,
                resolutionMode, complianceSnapshot, labels, avatarUrl, managementState, hasVisibleSuites,
                domain);
    }

    /** Owner workspace projection, never populated by public discovery or social lists. */
    public record ManagementState(String requestedVisibility, Long reviewTaskId,
                                  String promotionRole, String relatedNamespace, String relatedSlug,
                                  boolean ownerReadOnly) {
        public ManagementState(String requestedVisibility, Long reviewTaskId, String promotionRole,
                               String relatedNamespace, String relatedSlug) {
            this(requestedVisibility, reviewTaskId, promotionRole, relatedNamespace, relatedSlug, false);
        }
    }

    public SkillSummaryResponse(
            Long id, String slug, String displayName, String summary, String visibility, String status,
            Long downloadCount, Integer starCount, BigDecimal ratingAvg, Integer ratingCount,
            String namespace, Instant updatedAt, String ownerId, String ownerDisplayName,
            boolean canSubmitPromotion, SkillLifecycleVersionResponse headlineVersion,
            SkillLifecycleVersionResponse publishedVersion, SkillLifecycleVersionResponse ownerPreviewVersion,
            String resolutionMode, ComplianceSnapshotResponse complianceSnapshot, List<SkillLabelDto> labels,
            String avatarUrl) {
        this(id, slug, displayName, summary, visibility, status, downloadCount, starCount, ratingAvg,
                ratingCount, namespace, updatedAt, ownerId, ownerDisplayName, canSubmitPromotion,
                headlineVersion, publishedVersion, ownerPreviewVersion, resolutionMode, complianceSnapshot,
                labels, avatarUrl, null, false, null);
    }

    public SkillSummaryResponse withManagementState(ManagementState state) {
        return new SkillSummaryResponse(id, slug, displayName, summary, visibility, status, downloadCount,
                starCount, ratingAvg, ratingCount, namespace, updatedAt, ownerId, ownerDisplayName,
                canSubmitPromotion && (state == null || !state.ownerReadOnly()), headlineVersion, publishedVersion, ownerPreviewVersion,
                resolutionMode, complianceSnapshot, labels, avatarUrl, state, hasVisibleSuites, domain);
    }

    public SkillSummaryResponse(
            Long id, String slug, String displayName, String summary, String visibility, String status,
            Long downloadCount, Integer starCount, BigDecimal ratingAvg, Integer ratingCount,
            String namespace, Instant updatedAt, String ownerId, String ownerDisplayName,
            boolean canSubmitPromotion, SkillLifecycleVersionResponse headlineVersion,
            SkillLifecycleVersionResponse publishedVersion, SkillLifecycleVersionResponse ownerPreviewVersion,
            String resolutionMode, ComplianceSnapshotResponse complianceSnapshot, List<SkillLabelDto> labels) {
        this(id, slug, displayName, summary, visibility, status, downloadCount, starCount, ratingAvg,
                ratingCount, namespace, updatedAt, ownerId, ownerDisplayName, canSubmitPromotion,
                headlineVersion, publishedVersion, ownerPreviewVersion, resolutionMode, complianceSnapshot,
                labels, null);
    }

    /**
     * Summary without label projection.
     */
    public SkillSummaryResponse(
            Long id,
            String slug,
            String displayName,
            String summary,
            String visibility,
            String status,
            Long downloadCount,
            Integer starCount,
            BigDecimal ratingAvg,
            Integer ratingCount,
            String namespace,
            Instant updatedAt,
            boolean canSubmitPromotion,
            SkillLifecycleVersionResponse headlineVersion,
            SkillLifecycleVersionResponse publishedVersion,
            SkillLifecycleVersionResponse ownerPreviewVersion,
            String resolutionMode,
            ComplianceSnapshotResponse complianceSnapshot) {
        this(id, slug, displayName, summary, visibility, status, downloadCount, starCount, ratingAvg,
                ratingCount, namespace, updatedAt, null, null, canSubmitPromotion, headlineVersion, publishedVersion,
                ownerPreviewVersion, resolutionMode, complianceSnapshot, null);
    }

    /** Summary with owner information but without an optional label projection. */
    public SkillSummaryResponse(
            Long id,
            String slug,
            String displayName,
            String summary,
            String visibility,
            String status,
            Long downloadCount,
            Integer starCount,
            BigDecimal ratingAvg,
            Integer ratingCount,
            String namespace,
            Instant updatedAt,
            String ownerId,
            String ownerDisplayName,
            boolean canSubmitPromotion,
            SkillLifecycleVersionResponse headlineVersion,
            SkillLifecycleVersionResponse publishedVersion,
            SkillLifecycleVersionResponse ownerPreviewVersion,
            String resolutionMode,
            ComplianceSnapshotResponse complianceSnapshot) {
        this(id, slug, displayName, summary, visibility, status, downloadCount, starCount, ratingAvg,
                ratingCount, namespace, updatedAt, ownerId, ownerDisplayName, canSubmitPromotion,
                headlineVersion, publishedVersion, ownerPreviewVersion, resolutionMode, complianceSnapshot, null);
    }

    public SkillSummaryResponse withLabels(List<SkillLabelDto> labels) {
        return new SkillSummaryResponse(id, slug, displayName, summary, visibility, status, downloadCount,
                starCount, ratingAvg, ratingCount, namespace, updatedAt, ownerId, ownerDisplayName,
                canSubmitPromotion, headlineVersion,
                publishedVersion, ownerPreviewVersion, resolutionMode, complianceSnapshot, labels, avatarUrl, managementState, hasVisibleSuites, domain);
    }
}
