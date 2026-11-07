package com.iflytek.skillhub.domain.skill;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Per-UTC-day download bucket for one Skill.
 *
 * <p>The cumulative {@code skill.download_count} answers "how many downloads ever". This entity
 * answers "how many downloads on this day", which is the only honest input for a 7-day window:
 * a windowed number is never derived from the cumulative counter.
 */
@Entity
@Table(name = "skill_download_daily")
@IdClass(SkillDownloadDaily.Key.class)
public class SkillDownloadDaily {

    @Id
    @Column(name = "skill_id", nullable = false)
    private Long skillId;

    @Id
    @Column(name = "download_date", nullable = false)
    private LocalDate downloadDate;

    @Column(name = "download_count", nullable = false)
    private Long downloadCount = 0L;

    protected SkillDownloadDaily() {
    }

    public SkillDownloadDaily(Long skillId, LocalDate downloadDate) {
        this.skillId = skillId;
        this.downloadDate = downloadDate;
    }

    public Long getSkillId() {
        return skillId;
    }

    public LocalDate getDownloadDate() {
        return downloadDate;
    }

    public Long getDownloadCount() {
        return downloadCount;
    }

    /** Composite primary key. JPA requires a separate class for {@link IdClass}. */
    public static class Key implements Serializable {
        private Long skillId;
        private LocalDate downloadDate;

        public Key() {
        }

        public Key(Long skillId, LocalDate downloadDate) {
            this.skillId = skillId;
            this.downloadDate = downloadDate;
        }

        public Long getSkillId() {
            return skillId;
        }

        public LocalDate getDownloadDate() {
            return downloadDate;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(skillId, key.skillId) && Objects.equals(downloadDate, key.downloadDate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(skillId, downloadDate);
        }
    }
}
