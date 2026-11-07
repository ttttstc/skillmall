package com.iflytek.skillhub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.dto.HomeRankingResponse;
import com.iflytek.skillhub.dto.HomeSkillListResponse;
import com.iflytek.skillhub.dto.SkillSummaryResponse;
import com.iflytek.skillhub.repository.SkillRankingQueryRepository;
import com.iflytek.skillhub.repository.SkillRankingQueryRepository.RankedSkill;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Window, rotation and empty-state contract for home discovery.
 *
 * <p>Summaries are stubbed by size because this test is about which ids the service asks for and
 * in what order, not about the skill projection itself.
 */
@ExtendWith(MockitoExtension.class)
class HomeDiscoveryAppServiceTest {

    /** 2026-11-07 is a Saturday; the 7-day window is 2026-11-01 .. 2026-11-07 inclusive. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T05:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate WINDOW_START = LocalDate.of(2026, 11, 1);
    private static final LocalDate WINDOW_END_EXCLUSIVE = LocalDate.of(2026, 11, 8);

    @Mock
    private SkillRankingQueryRepository rankingQueryRepository;

    @Mock
    private SkillSearchAppService skillSearchAppService;

    private HomeDiscoveryAppService service;

    @BeforeEach
    void setUp() {
        service = new HomeDiscoveryAppService(rankingQueryRepository, skillSearchAppService, CLOCK);
        when(skillSearchAppService.toSummaries(anyList(), any())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            return ids.stream().map(HomeDiscoveryAppServiceTest::summary).toList();
        });
    }

    @Test
    void recentPopularUsesSevenCalendarDaysEndingToday() {
        when(rankingQueryRepository.findRecentlyPopular(Set.of(), WINDOW_START, WINDOW_END_EXCLUSIVE, 10))
                .thenReturn(List.of(row(4L), row(2L)));

        service.ranking("recent-popular", 10, null, null);

        assertThat(capturedSummaryIds()).containsExactly(4L, 2L);
    }

    @Test
    void recentPopularCarriesTheWindowedCountAndFirstPublishedTime() {
        Instant firstPublished = Instant.parse("2026-01-05T08:00:00Z");
        when(rankingQueryRepository.findRecentlyPopular(any(), any(), any(), any(Integer.class)))
                .thenReturn(List.of(new RankedSkill(4L, 12L, firstPublished)));

        HomeRankingResponse response = service.ranking("recent-popular", 10, null, null);

        // The windowed number is reported separately from the cumulative one.
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().windowDownloadCount()).isEqualTo(12L);
        assertThat(response.items().getFirst().firstPublishedAt()).isEqualTo(firstPublished);
        assertThat(response.type()).isEqualTo("recent-popular");
    }

    @Test
    void emptyWindowStaysEmptyInsteadOfFallingBackToCumulativeTotals() {
        when(rankingQueryRepository.findRecentlyPopular(any(), any(), any(), any(Integer.class)))
                .thenReturn(List.of());

        HomeRankingResponse response = service.ranking("recent-popular", 10, null, null);

        assertThat(response.items()).isEmpty();
        // A windowed figure must never be borrowed from the cumulative counter.
        verify(rankingQueryRepository, never()).findMostDownloaded(any(), any(Integer.class));
    }

    @Test
    void cumulativeRankingReadsTheCumulativeCounter() {
        when(rankingQueryRepository.findMostDownloaded(Set.of(), 10)).thenReturn(List.of(row(9L), row(3L)));

        service.ranking("most-downloaded", 10, null, null);

        assertThat(capturedSummaryIds()).containsExactly(9L, 3L);
    }

    @Test
    void cumulativeRankingDoesNotReportAWindowedCount() {
        when(rankingQueryRepository.findMostDownloaded(any(), any(Integer.class)))
                .thenReturn(List.of(new RankedSkill(9L, null, Instant.parse("2026-01-05T08:00:00Z"))));

        HomeRankingResponse response = service.ranking("most-downloaded", 10, null, null);

        // Null, not the cumulative total: the client cannot mislabel it as a 7-day figure.
        assertThat(response.items().getFirst().windowDownloadCount()).isNull();
    }

    @Test
    void newestRankingReadsFirstPublishedOrder() {
        when(rankingQueryRepository.findNewestPublished(Set.of(), 10)).thenReturn(List.of(row(7L)));

        service.ranking("newest", 10, null, null);

        assertThat(capturedSummaryIds()).containsExactly(7L);
    }

    @Test
    void rankingTypeAcceptsKebabAndUppercaseSpellings() {
        when(rankingQueryRepository.findMostDownloaded(Set.of(), 10)).thenReturn(List.of(row(5L)));

        service.ranking("MOST-DOWNLOADED", 10, null, null);

        assertThat(capturedSummaryIds()).containsExactly(5L);
    }

    @Test
    void unknownRankingTypeFallsBackToRecentPopular() {
        when(rankingQueryRepository.findRecentlyPopular(any(), any(), any(), any(Integer.class)))
                .thenReturn(List.of());

        service.ranking("something-else", 10, null, null);

        ArgumentCaptor<LocalDate> start = ArgumentCaptor.forClass(LocalDate.class);
        verify(rankingQueryRepository).findRecentlyPopular(eq(Set.of()), start.capture(), any(), any(Integer.class));
        assertThat(start.getValue()).isEqualTo(WINDOW_START);
    }

    @Test
    void anonymousCallerHasNoMemberNamespaces() {
        when(rankingQueryRepository.findMostDownloaded(Set.of(), 10)).thenReturn(List.of());

        service.ranking("most-downloaded", 10, null, null);

        // An empty member set is what excludes NAMESPACE_ONLY skills from an anonymous response.
        verify(rankingQueryRepository).findMostDownloaded(Set.of(), 10);
    }

    @Test
    void signedInCallerForwardsItsMemberNamespaces() {
        when(rankingQueryRepository.findMostDownloaded(Set.of(5L), 10)).thenReturn(List.of());

        service.ranking("most-downloaded", 10, "user-1", Map.of(5L, NamespaceRole.MEMBER));

        verify(rankingQueryRepository).findMostDownloaded(Set.of(5L), 10);
    }

    @Test
    void recommendationsSkipSkillsAlreadyOnScreen() {
        when(rankingQueryRepository.findRandomRecommendations(Set.of(), List.of(11L, 12L), 6))
                .thenReturn(List.of(row(1L), row(2L), row(3L), row(4L), row(5L), row(6L)));

        service.recommendations(6, List.of(11L, 12L), null, null);

        assertThat(capturedSummaryIds()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        verify(rankingQueryRepository, never()).findRandomRecommendations(Set.of(), List.of(), 8);
    }

    @Test
    void recommendationsFillFromTheRealPoolWhenTheExcludedPoolIsShort() {
        when(rankingQueryRepository.findRandomRecommendations(Set.of(), List.of(11L, 12L), 6))
                .thenReturn(List.of(row(1L), row(2L)));
        when(rankingQueryRepository.findRandomRecommendations(Set.of(), List.of(), 8))
                .thenReturn(List.of(row(1L), row(2L), row(3L), row(4L), row(5L), row(6L)));

        service.recommendations(6, List.of(11L, 12L), null, null);

        // Rotation first, then real neighbours; no duplicate and nothing invented.
        assertThat(capturedSummaryIds()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
    }

    @Test
    void recommendationsNeverReturnMoreThanThePoolHolds() {
        when(rankingQueryRepository.findRandomRecommendations(Set.of(), List.of(), 6))
                .thenReturn(List.of(row(1L)));

        HomeSkillListResponse response = service.recommendations(6, List.of(), null, null);

        assertThat(response.items()).hasSize(1);
    }

    @Test
    void recommendationsSizeIsClampedToTheMaximum() {
        when(rankingQueryRepository.findRandomRecommendations(any(), anyList(), any(Integer.class)))
                .thenReturn(List.of());

        service.recommendations(999, List.of(), null, null);

        verify(rankingQueryRepository).findRandomRecommendations(Set.of(), List.of(), 24);
    }

    private RankedSkill row(Long skillId) {
        return new RankedSkill(skillId, null, null);
    }

    /** Minimal summary fixture: only the identity matters for these assertions. */
    private static SkillSummaryResponse summary(Long id) {
        return new SkillSummaryResponse(
                id, "slug-" + id, "Skill " + id, null, "PUBLIC", "ACTIVE",
                0L, 0, null, 0, "team", Instant.EPOCH, "owner", null, false,
                null, null, null, "PUBLISHED", null, List.of());
    }

    private List<Long> capturedSummaryIds() {
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(skillSearchAppService).toSummaries(captor.capture(), any());
        return captor.getValue();
    }
}
