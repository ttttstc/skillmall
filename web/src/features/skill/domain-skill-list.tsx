import { Link } from '@tanstack/react-router'
import { useTranslation } from 'react-i18next'
import { ArrowRight, Download, Star } from 'lucide-react'
import type { SkillDomain } from '@/shared/lib/skill-domains'
import { useSearchSkills } from '@/shared/hooks/use-skill-queries'
import { LoadError } from '@/shared/components/load-error'
import { formatCompactCount } from '@/shared/lib/number-format'

/** Six rows keep the section a summary; the full list lives behind 查看更多. */
const DOMAIN_LIST_SIZE = 6

interface DomainSkillListProps {
  /** Selected category, or undefined for the whole marketplace. */
  domain?: SkillDomain
  onSelect: (namespace: string, slug: string) => void
}

/**
 * The row list under 领域 Skills on the landing page.
 *
 * The chips used to change the recommendation and ranking blocks above while this section
 * stayed empty, so pressing a chip here looked like it did nothing. The list reuses the
 * marketplace listing endpoint with the same `domain` filter, ordered the same way as the
 * 查看更多 target, so the section answers the chip directly.
 *
 * The category chip only appears when a single category is selected: the summary payload
 * does not carry each Skill's category, and guessing one would be worse than omitting it.
 */
export function DomainSkillList({ domain, onSelect }: DomainSkillListProps) {
  const { t } = useTranslation()
  const { data, isLoading, isError, refetch } = useSearchSkills({
    q: '',
    sort: 'downloads',
    page: 0,
    size: DOMAIN_LIST_SIZE,
    ...(domain ? { domain } : {}),
  })
  const skills = data?.items ?? []

  if (isLoading) {
    return (
      <div className="space-y-2">
        {Array.from({ length: 3 }).map((_, index) => (
          <div key={index} className="h-16 animate-shimmer rounded-xl" />
        ))}
      </div>
    )
  }

  if (isError) {
    return <LoadError compact onRetry={() => { void refetch() }} />
  }

  if (skills.length === 0) {
    return (
      <div className="rounded-xl border border-border/60 bg-card px-6 py-12 text-center text-sm text-muted-foreground">
        {t('home.domainEmpty')}
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <div className="overflow-hidden rounded-xl border border-border/70 bg-card divide-y divide-border/60">
        {skills.map(skill => (
          <button
            key={skill.id}
            type="button"
            data-analytics-id="discovery.domain.open"
            onClick={() => onSelect(skill.namespace, skill.slug)}
            className="flex w-full items-center gap-4 px-5 py-3 text-left transition-colors hover:bg-accent"
          >
            <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-muted text-sm font-semibold text-foreground">
              {skill.displayName.slice(0, 1).toUpperCase()}
            </span>
            <span className="min-w-0 flex-1">
              <span className="flex items-center gap-2">
                <span className="truncate font-medium text-foreground">{skill.displayName}</span>
                {domain ? (
                  <span className="shrink-0 rounded-full border border-border/70 px-2 py-0.5 text-[11px] text-muted-foreground">
                    {t(`home.domain.${domain}`)}
                  </span>
                ) : null}
              </span>
              {skill.summary ? (
                <span className="mt-0.5 block truncate text-sm text-muted-foreground">{skill.summary}</span>
              ) : null}
            </span>
            <span className="flex shrink-0 items-center gap-3 text-xs text-muted-foreground">
              <span className="flex items-center gap-1">
                <Star className="h-3.5 w-3.5" aria-hidden="true" />
                {formatCompactCount(skill.starCount)}
              </span>
              <span className="flex items-center gap-1">
                <Download className="h-3.5 w-3.5" aria-hidden="true" />
                {formatCompactCount(skill.downloadCount)}
              </span>
            </span>
          </button>
        ))}
      </div>
      <div className="text-center">
        <Link
          data-analytics-id="discovery.domain.viewall"
          to="/search"
          search={{ q: '', sort: 'downloads', page: 0, starredOnly: false, ...(domain ? { domain } : {}) }}
          className="inline-flex items-center gap-2 text-sm font-medium text-foreground transition-colors hover:text-primary"
        >
          {t('home.domainViewAll')} <ArrowRight className="h-4 w-4" aria-hidden="true" />
        </Link>
      </div>
    </div>
  )
}
