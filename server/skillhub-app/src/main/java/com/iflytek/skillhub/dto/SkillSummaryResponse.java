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
         * Legacy first-level category wire value. Kept for API compatibility only; the UI renders
         * {@link #category} instead. Omitted when the projection carries no legacy value.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String domain,
        /**
         * First-level category reference. Cards and list rows show {@code subCategory ?? category},
         * so carrying both here saves a second lookup per item.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        SkillCategoryRef category,
        /** Second-level category reference, omitted while the skill has none. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        SkillCategoryRef subCategory
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
                labels, avatarUrl, managementState, false, null, null, null);
    }

    public SkillSummaryResponse withVisibleSuites(boolean hasVisibleSuites) {
        return new SkillSummaryResponse(id, slug, displayName, summary, visibility, status, downloadCount,
                starCount, ratingAvg, ratingCount, namespace, updatedAt, ownerId, ownerDisplayName,
                canSubmitPromotion, headlineVersion, publishedVersion, ownerPreviewVersion,
                resolutionMode, complianceSnapshot, labels, avatarUrl, managementState, hasVisibleSuites,
                domain, category, subCategory);
    }

    /** Attaches the legacy marketplace category wire value. */
    public SkillSummaryResponse withDomain(String domain) {
        return new SkillSummaryResponse(id, slug, displayName, summary, visibility, status, downloadCount,
                starCount, ratingAvg, ratingCount, namespace, updatedAt, ownerId, ownerDisplayName,
                canSubmitPromotion, headlineVersion, publishedVersion, ownerPreviewVersion,
                resolutionMode, complianceSnapshot, labels, avatarUrl, managementState, hasVisibleSuites,
                domain, category, subCategory);
    }

    /** Attaches the resolved two-level category references; card chips read {@code subCategory ?? category}. */
    public SkillSummaryResponse withCategories(SkillCategoryRef category, SkillCategoryRef subCategory) {
        return new SkillSummaryResponse(id, slug, displayName, summary, visibility, status, downloadCount,
                starCount, ratingAvg, ratingCount, namespace, updatedAt, ownerId, ownerDisplayName,
                canSubmitPromotion, headlineVersion, publishedVersion, ownerPreviewVersion,
                resolutionMode, complianceSnapshot, labels, avatarUrl, managementState, hasVisibleSuites,
                domain, category, subCategory);
    }

    /** Owner workspace projection, never populated by public discovery or social lists. */
    public record ManagementState(String requestedVisibility, Long reviewTaskId,
                                  String promotionRole, String relatedNamespace, String relatedSlug,
                                  boolean ownerReadOnly,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) String createdByName,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) Instant createdAt,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) String updatedByName) {
        public ManagementState(String requestedVisibility, Long reviewTaskId, String promotionRole,
                               String relatedNamespace, String relatedSlug, boolean ownerReadOnly) {
            this(requestedVisibility, reviewTaskId, promotionRole, relatedNamespace, relatedSlug,
                    ownerReadOnly, null, null, null);
        }

        public ManagementState(String requestedVisibility, Long reviewTaskId, String promotionRole,
                               String relatedNamespace, String relatedSlug) {
            this(requestedVisibility, reviewTaskId, promotionRole, relatedNamespace, relatedSlug, false);
        }

        /** Owner listing augmentation: who created and last changed the skill, and when. */
        public ManagementState withAuthors(String createdByName, Instant createdAt, String updatedByName) {
            return new ManagementState(requestedVisibility, reviewTaskId, promotionRole, relatedNamespace,
                    relatedSlug, ownerReadOnly, createdByName, createdAt, updatedByName);
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
                labels, avatarUrl, null, false, null, null, null);
    }

    public SkillSummaryResponse withManagementState(ManagementState state) {
        return new SkillSummaryResponse(id, slug, displayName, summary, visibility, status, downloadCount,
                starCount, ratingAvg, ratingCount, namespace, updatedAt, ownerId, ownerDisplayName,
                canSubmitPromotion && (state == null || !state.ownerReadOnly()), headlineVersion, publishedVersion, ownerPreviewVersion,
                resolutionMode, complianceSnapshot, labels, avatarUrl, state, hasVisibleSuites, domain,
                category, subCategory);
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
                publishedVersion, ownerPreviewVersion, resolutionMode, complianceSnapshot, labels, avatarUrl, managementState, hasVisibleSuites, domain,
                category, subCategory);
    }
}
