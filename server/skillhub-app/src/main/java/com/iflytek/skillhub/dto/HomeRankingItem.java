package com.iflytek.skillhub.dto;

import java.time.Instant;

/**
 * One row of a home ranking.
 *
 * <p>{@code windowDownloadCount} is populated only for the windowed ranking, so a client cannot
 * accidentally render a cumulative total as a 7-day figure. {@code firstPublishedAt} is the Skill's
 * first publication and does not move when a newer version ships.
 */
public record HomeRankingItem(
        SkillSummaryResponse skill,
        Long windowDownloadCount,
        Instant firstPublishedAt
) {
}
