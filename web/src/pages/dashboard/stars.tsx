import { useLocation, useNavigate, useSearch } from '@tanstack/react-router'
import { useTranslation } from 'react-i18next'
import { Store } from 'lucide-react'
import { SkillListRow } from '@/features/skill/skill-list-row'
import { Pagination } from '@/shared/components/pagination'
import { useMyStarsPage } from '@/shared/hooks/use-user-queries'
import { EmptyState } from '@/shared/components/empty-state'
import { LoadError } from '@/shared/components/load-error'
import { Button } from '@/shared/ui/button'
import { DashboardPageHeader } from '@/shared/components/dashboard-page-header'
import { buildReturnTo } from '@/shared/lib/auth-route'

const PAGE_SIZE = 12

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
    <div className="space-y-6 animate-fade-up">
      <DashboardPageHeader title={t('stars.title')} subtitle={t('stars.subtitle')} />

      {isError ? (
        // A failed request is not an empty collection: say so and offer a retry.
        <LoadError onRetry={() => { void refetch() }} />
      ) : !skills || skills.length === 0 ? (
        <EmptyState
          title={t('stars.empty')}
          action={<Button data-analytics-id="navigation.skills.open"
            className="h-9 rounded-full bg-foreground px-4 text-xs font-medium text-background shadow-none hover:bg-foreground/90 active:scale-100"
            onClick={() => navigate({ to: '/search', search: { q: '', sort: 'relevance', page: 0, starredOnly: false } })}>
            <Store className="h-3.5 w-3.5" aria-hidden="true" />
            {t('home.browseSkills')}
          </Button>}
        />
      ) : (
        <>
          <div className="overflow-hidden rounded-xl border border-border/70 bg-card divide-y divide-border/50">
            {skills.map((skill) => (
              <SkillListRow analyticsId="skill.card.open"
                key={skill.id}
                skill={skill}
                highlightStarred={false}
                onClick={() => navigate({
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
