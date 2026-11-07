package com.iflytek.skillhub.domain.skill;

import java.time.LocalDate;

/**
 * Domain repository contract for per-day download buckets.
 *
 * <p>Buckets exist only for days that actually recorded a download, so "no rows" means
 * "no downloads recorded in this window" rather than "unknown".
 */
public interface SkillDownloadDailyRepository {

    /** Adds one download to the bucket of {@code downloadDate} for {@code skillId}. */
    void incrementDownloadCount(Long skillId, LocalDate downloadDate);

    void deleteBySkillId(Long skillId);
}
