import { useLocation, useNavigate, useSearch } from '@tanstack/react-router'
import { useTranslation } from 'react-i18next'
import type { SkillSummary } from '@/api/types'
import { buildApiUrl, WEB_API_PREFIX } from '@/api/client'
import { SkillListRow } from '@/features/skill/skill-list-row'
import { useToggleStar } from '@/features/social/use-star'
import { getHeadlineVersion } from '@/shared/lib/skill-lifecycle'
import { Pagination } from '@/shared/components/pagination'
import { useMyStarsPage } from '@/shared/hooks/use-user-queries'
import { EmptyState } from '@/shared/components/empty-state'
import { LoadError } from '@/shared/components/load-error'
import { Button } from '@/shared/ui/button'
import { DashboardPageHeader } from '@/shared/components/dashboard-page-header'
import { buildReturnTo } from '@/shared/lib/auth-route'

const PAGE_SIZE = 12

/**
 * One starred skill with the two actions the collections view is expected to offer:
 * remove the star without leaving the page, and download the publishable bundle.
 */
function StarredSkillRow({ skill, onOpen }: { skill: SkillSummary; onOpen: () => void }) {
  const { t } = useTranslation()
  const toggleStar = useToggleStar(skill.id)
  const headline = getHeadlineVersion(skill)
  const downloadHref = headline
    ? buildApiUrl(
        `${WEB_API_PREFIX}/skills/${skill.namespace}/${encodeURIComponent(skill.slug)}/versions/${encodeURIComponent(headline.version)}/download`,
      )
    : undefined

  return (
    <SkillListRow
      analyticsId="skill.card.open"
      skill={skill}
      highlightStarred={false}
      onClick={onOpen}
      actions={
        <>
          <Button
            data-analytics-id="skill.star.remove"
            variant="ghost"
            size="sm"
            disabled={toggleStar.isPending}
            onClick={() => toggleStar.mutate(true)}
          >
            {t('stars.actions.unstar')}
          </Button>
          {downloadHref ? (
            <a
              data-analytics-id="skill.download.request"
              href={downloadHref}
              className="inline-flex h-8 items-center rounded-md px-2.5 text-sm font-medium text-foreground transition-colors hover:bg-accent"
            >
              {t('stars.actions.download')}
            </a>
          ) : null}
        </>
      }
    />
  )
}

export function MyStarsPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const location = useLocation()
  const search = useSearch({ from: '/dashboard/stars' })
  const page = search.page ?? 0
  const { data, isLoading, isError, refetch } = useMyStarsPage({ page, size: PAGE_SIZE })
  const skills = data?.items ?? []
  const totalPages = data ? Math.max(Math.ceil(data.total / data.size), 1) : 1

  if (isLoading) {
    return (
      <div className="space-y-4 animate-fade-up">
        {Array.from({ length: 3 }).map((_, i) => (
          <div key={i} className="h-32 animate-shimmer rounded-xl" />
        ))}
      </div>
    )
  }

  return (
    <div className="space-y-8 animate-fade-up">
      <DashboardPageHeader title={t('stars.title')} subtitle={t('stars.subtitle')} />

      {isError ? (
        // A failed request is not an empty collection: say so and offer a retry.
        <LoadError onRetry={() => { void refetch() }} />
      ) : !skills || skills.length === 0 ? (
        <EmptyState
          title={t('stars.empty')}
          action={<Button data-analytics-id="navigation.skills.open" onClick={() => navigate({ to: '/search', search: { q: '', sort: 'relevance', page: 0, starredOnly: false } })}>{t('landing.hero.exploreSkills')}</Button>}
        />
      ) : (
        <>
          <div className="overflow-hidden rounded-xl border border-border/70 bg-card divide-y divide-border/60">
            {skills.map((skill) => (
              <StarredSkillRow
                key={skill.id}
                skill={skill}
                onOpen={() => navigate({
                  to: `/space/${skill.namespace}/${encodeURIComponent(skill.slug)}`,
                  search: { returnTo: buildReturnTo(location) },
                })}
              />
            ))}
          </div>
          {data && data.total > PAGE_SIZE ? (
            <Pagination analyticsId="skill.starred.paginate"
              page={page}
              totalPages={totalPages}
              onPageChange={(nextPage) => navigate({
                to: '/dashboard/stars',
                search: { page: nextPage > 0 ? nextPage : undefined },
              })}
            />
          ) : null}
        </>
      )}
    </div>
  )
}
