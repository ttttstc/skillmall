package com.iflytek.skillhub.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Read model behind the home page rankings and the generic recommendation row.
 *
 * <p>Every method applies the same public-distribution filter: container {@code ACTIVE}, not
 * {@code hidden}, namespace not archived, and a latest-version pointer that is {@code PUBLISHED},
 * {@code download_ready} and not yanked. Anonymous callers pass an empty member set, which drops
 * {@code NAMESPACE_ONLY} skills; hidden, private, archived and non-distributable skills therefore
 * never reach an anonymous result.
 *
 * <p>Rows carry the numbers the page displays, so the client never has to infer a windowed figure
 * from a cumulative one.
 */
public interface SkillRankingQueryRepository {

    /**
     * One ranked Skill.
     *
     * @param windowDownloadCount downloads recorded inside the requested window; null when the
     *                            ranking is not windowed
     * @param firstPublishedAt    the Skill's first publication, or null when it has none recorded
     */
    record RankedSkill(Long skillId, Long windowDownloadCount, Instant firstPublishedAt) {
    }

    /**
     * Ranks by downloads recorded inside {@code [windowStart, windowEndExclusive)}.
     *
     * <p>Skills with no recorded download in the window are omitted rather than padded from the
     * cumulative counter.
     */
    List<RankedSkill> findRecentlyPopular(Set<Long> memberNamespaceIds,
                                          LocalDate windowStart,
                                          LocalDate windowEndExclusive,
                                          int limit);

    /** Ranks by the cumulative download counter. */
    List<RankedSkill> findMostDownloaded(Set<Long> memberNamespaceIds, int limit);

    /** Ranks by {@code skill.first_published_at}, newest first. */
    List<RankedSkill> findNewestPublished(Set<Long> memberNamespaceIds, int limit);

    /**
     * Draws a random sample of distributable skills, skipping {@code excludedSkillIds} when given.
     *
     * <p>The sample is genuinely random per call; it never returns a skill that is not in the
     * eligible pool.
     */
    List<RankedSkill> findRandomRecommendations(Set<Long> memberNamespaceIds,
                                                List<Long> excludedSkillIds,
                                                int limit);
}
