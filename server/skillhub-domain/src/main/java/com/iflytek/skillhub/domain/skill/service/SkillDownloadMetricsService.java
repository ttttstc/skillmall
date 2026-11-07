package com.iflytek.skillhub.domain.skill.service;

import com.iflytek.skillhub.domain.skill.SkillDownloadDailyRepository;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillVersionStatsRepository;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records the three counters of one published download in a single transaction.
 *
 * <p>The cumulative counter, the per-version counter and the per-day bucket describe the same
 * event, so they must move together: if the day bucket failed after the cumulative total had
 * already committed, the 7-day window would permanently trail the cumulative total and no later
 * download could repair the gap.
 *
 * <p>This write lives in its own bean instead of a private method of the caller because Spring's
 * transaction proxy only applies to calls that cross a bean boundary; a self-invoked
 * {@code @Transactional} method would silently run without a transaction of its own.
 */
@Service
public class SkillDownloadMetricsService {

    private final SkillRepository skillRepository;
    private final SkillVersionStatsRepository skillVersionStatsRepository;
    private final SkillDownloadDailyRepository skillDownloadDailyRepository;

    public SkillDownloadMetricsService(SkillRepository skillRepository,
                                       SkillVersionStatsRepository skillVersionStatsRepository,
                                       SkillDownloadDailyRepository skillDownloadDailyRepository) {
        this.skillRepository = skillRepository;
        this.skillVersionStatsRepository = skillVersionStatsRepository;
        this.skillDownloadDailyRepository = skillDownloadDailyRepository;
    }

    /**
     * Adds one download to the cumulative, per-version and per-day counters together.
     *
     * @param downloadDate UTC calendar day the download is attributed to
     */
    @Transactional
    public void recordPublishedDownload(Long skillId, Long versionId, LocalDate downloadDate) {
        skillRepository.incrementDownloadCount(skillId);
        skillVersionStatsRepository.incrementDownloadCount(versionId, skillId);
        skillDownloadDailyRepository.incrementDownloadCount(skillId, downloadDate);
    }
}
