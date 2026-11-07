package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.skill.SkillDownloadDaily;
import com.iflytek.skillhub.domain.skill.SkillDownloadDailyRepository;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA-backed repository for daily download buckets.
 *
 * <p>Increments use the same PostgreSQL upsert shape as {@link SkillVersionStatsJpaRepository} so
 * two concurrent downloads on the same day cannot lose a count or fail on the primary key.
 */
@Repository
public interface SkillDownloadDailyJpaRepository
        extends JpaRepository<SkillDownloadDaily, SkillDownloadDaily.Key>, SkillDownloadDailyRepository {

    @Override
    @Modifying
    @Transactional
    @Query(
            value = """
                    INSERT INTO skill_download_daily (skill_id, download_date, download_count)
                    VALUES (:skillId, :downloadDate, 1)
                    ON CONFLICT (skill_id, download_date)
                    DO UPDATE SET download_count = skill_download_daily.download_count + 1
                    """,
            nativeQuery = true
    )
    void incrementDownloadCount(@Param("skillId") Long skillId, @Param("downloadDate") LocalDate downloadDate);
}
