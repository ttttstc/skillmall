package com.iflytek.skillhub.dto;

import java.util.List;

/**
 * Ordered Skill summaries for one home-page section.
 *
 * <p>Order is the ranking itself, so clients render the list as received. An empty list is a real
 * empty state, never a placeholder for missing data.
 */
public record HomeSkillListResponse(
        List<SkillSummaryResponse> items
) {
}
