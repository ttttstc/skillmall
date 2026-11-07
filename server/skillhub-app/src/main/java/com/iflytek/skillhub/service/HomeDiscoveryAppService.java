package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.skill.SkillDomain;
import com.iflytek.skillhub.dto.HomeRankingItem;
import com.iflytek.skillhub.dto.HomeRankingResponse;
import com.iflytek.skillhub.dto.HomeSkillListResponse;
import com.iflytek.skillhub.dto.SkillSummaryResponse;
import com.iflytek.skillhub.repository.SkillRankingQueryRepository;
import com.iflytek.skillhub.repository.SkillRankingQueryRepository.RankedSkill;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Home page discovery: one generic recommendation row plus the three rankings.
 *
 * <p>Recommendations are deliberately not personalised: they are a random draw over the
 * publicly distributable pool, so the page must never label them as "for you". Shared counters
 * supply the rankings; the 7-day window reads only per-day buckets.
 */
@Service
public class HomeDiscoveryAppService {

    /** The recent-popularness window is seven calendar days, today included. */
    public static final int RECENT_WINDOW_DAYS = 7;

    private static final int DEFAULT_RECOMMENDATION_SIZE = 6;
    private static final int DEFAULT_RANKING_SIZE = 10;
    private static final int MAX_SIZE = 24;

    public enum RankingType {
        RECENT_POPULAR("recent-popular"),
        MOST_DOWNLOADED("most-downloaded"),
        NEWEST("newest");

        private final String wireName;

        RankingType(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    private final SkillRankingQueryRepository rankingQueryRepository;
    private final SkillSearchAppService skillSearchAppService;
    private final Clock clock;

    public HomeDiscoveryAppService(SkillRankingQueryRepository rankingQueryRepository,
                                   SkillSearchAppService skillSearchAppService,
                                   Clock clock) {
        this.rankingQueryRepository = rankingQueryRepository;
        this.skillSearchAppService = skillSearchAppService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public HomeSkillListResponse recommendations(int size,
                                                 List<Long> excludeSkillIds,
                                                 SkillDomain domain,
                                                 String userId,
                                                 Map<Long, NamespaceRole> userNsRoles) {
        int limit = clampSize(size, DEFAULT_RECOMMENDATION_SIZE);
        Set<Long> memberNamespaceIds = memberNamespaceIds(userNsRoles);
        List<Long> excluded = normalizeExclusions(excludeSkillIds);

        List<RankedSkill> picked = new ArrayList<>(
                rankingQueryRepository.findRandomRecommendations(memberNamespaceIds, excluded, domain, limit));
        if (!excluded.isEmpty() && picked.size() < limit) {
            // The eligible pool minus the caller's exclusions is smaller than one row. Fill the
            // remaining slots from the same real pool; if even that runs out the row stays short,
            // because a missing skill is shown as missing rather than invented.
            for (RankedSkill candidate : rankingQueryRepository.findRandomRecommendations(
                    memberNamespaceIds, List.of(), domain, limit + excluded.size())) {
                if (picked.size() >= limit) {
                    break;
                }
                boolean alreadyPicked = picked.stream()
                        .anyMatch(existing -> Objects.equals(existing.skillId(), candidate.skillId()));
                if (!alreadyPicked) {
                    picked.add(candidate);
                }
            }
        }
        return new HomeSkillListResponse(
                skillSearchAppService.toSummaries(skillIds(picked), userId));
    }

    public HomeSkillListResponse recommendations(int size, List<Long> excludeSkillIds,
                                                 String userId, Map<Long, NamespaceRole> userNsRoles) {
        return recommendations(size, excludeSkillIds, null, userId, userNsRoles);
    }

    @Transactional(readOnly = true)
    public HomeRankingResponse ranking(String type,
                                       int size,
                                       SkillDomain domain,
                                       String userId,
                                       Map<Long, NamespaceRole> userNsRoles) {
        int limit = clampSize(size, DEFAULT_RANKING_SIZE);
        Set<Long> memberNamespaceIds = memberNamespaceIds(userNsRoles);
        LocalDate windowEndExclusive = LocalDate.now(clock).plusDays(1);
        LocalDate windowStart = windowEndExclusive.minusDays(RECENT_WINDOW_DAYS);
        RankingType rankingType = parseRankingType(type);

        List<RankedSkill> ranked = switch (rankingType) {
            case RECENT_POPULAR -> rankingQueryRepository.findRecentlyPopular(
                    memberNamespaceIds, windowStart, windowEndExclusive, domain, limit);
            case MOST_DOWNLOADED -> rankingQueryRepository.findMostDownloaded(memberNamespaceIds, domain, limit);
            case NEWEST -> rankingQueryRepository.findNewestPublished(memberNamespaceIds, domain, limit);
        };

        Map<Long, SkillSummaryResponse> summariesById = new LinkedHashMap<>();
        for (SkillSummaryResponse summary : skillSearchAppService.toSummaries(skillIds(ranked), userId)) {
            summariesById.put(summary.id(), summary);
        }

        List<HomeRankingItem> items = ranked.stream()
                .filter(row -> summariesById.containsKey(row.skillId()))
                .map(row -> new HomeRankingItem(
                        summariesById.get(row.skillId()),
                        row.windowDownloadCount(),
                        row.firstPublishedAt()))
                .toList();
        return new HomeRankingResponse(rankingType.wireName(), items);
    }

    public HomeRankingResponse ranking(String type, int size, String userId,
                                       Map<Long, NamespaceRole> userNsRoles) {
        return ranking(type, size, null, userId, userNsRoles);
    }

    private List<Long> skillIds(List<RankedSkill> rows) {
        return rows.stream().map(RankedSkill::skillId).toList();
    }

    private Set<Long> memberNamespaceIds(Map<Long, NamespaceRole> userNsRoles) {
        return userNsRoles == null || userNsRoles.isEmpty() ? Set.of() : userNsRoles.keySet();
    }

    private List<Long> normalizeExclusions(List<Long> excludeSkillIds) {
        if (excludeSkillIds == null || excludeSkillIds.isEmpty()) {
            return List.of();
        }
        return excludeSkillIds.stream()
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .toList();
    }

    private int clampSize(int size, int defaultSize) {
        if (size <= 0) {
            return defaultSize;
        }
        return Math.min(size, MAX_SIZE);
    }

    private RankingType parseRankingType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return RankingType.RECENT_POPULAR;
        }
        String normalized = rawType.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (RankingType type : RankingType.values()) {
            if (type.wireName.equals(normalized)) {
                return type;
            }
        }
        return RankingType.RECENT_POPULAR;
    }
}
