import type { SkillSummary } from '@/api/types'
import { useTranslation } from 'react-i18next'
import { useAuth } from '@/features/auth/use-auth'
import { useStarredIdSet } from '@/features/social/use-star'
import { Card } from '@/shared/ui/card'
import { NamespaceBadge } from '@/shared/components/namespace-badge'
import { SkillAvatar } from './skill-avatar'
import { getHeadlineVersion } from '@/shared/lib/skill-lifecycle'
import { formatCompactCount } from '@/shared/lib/number-format'
import { formatRelativeTime } from '@/shared/lib/format-relative-time'
import { Bookmark, Clock, User } from 'lucide-react'

interface SkillListRowProps {
  analyticsId?: string
  skill: SkillSummary
  onClick?: () => void
  highlightStarred?: boolean
}

/**
 * Single-row rendering of one skill for the list view of the discovery page.
 *
 * Kept deliberately close to SkillCard so both views expose the same facts
 * (name, coordinate, summary, version, downloads, stars, owner, freshness).
 */
export function SkillListRow({ skill, onClick, highlightStarred = true, analyticsId }: SkillListRowProps) {
  const { t, i18n } = useTranslation()
  const { isAuthenticated } = useAuth()
  const { starredIds } = useStarredIdSet(highlightStarred && isAuthenticated)
  const showStarredHighlight = highlightStarred && isAuthenticated && starredIds.has(skill.id)
  const headlineVersion = getHeadlineVersion(skill)
  const isInteractive = typeof onClick === 'function'
  const downloadLabel = t('skillCard.downloads', { value: formatCompactCount(skill.downloadCount) })
  const starLabel = t('skillCard.stars', { count: skill.starCount })

  return (
    <Card
      data-analytics-id={isInteractive ? analyticsId : undefined}
      className="group min-w-0 cursor-pointer rounded-none border-0 bg-card px-5 py-4 text-card-foreground shadow-none transition-colors hover:bg-accent/40 focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-inset focus-visible:ring-ring/15"
      onClick={onClick}
      onKeyDown={(event) => {
        if (!isInteractive) {
          return
        }

        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault()
          onClick()
        }
      }}
      role={isInteractive ? 'link' : undefined}
      tabIndex={isInteractive ? 0 : undefined}
    >
      <div className="flex min-w-0 items-center gap-4">
        <SkillAvatar name={skill.displayName} url={skill.avatarUrl} />

        <div className="min-w-0 flex-1">
          <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
            <h3
              className="min-w-0 truncate text-base font-semibold group-hover:text-primary transition-colors duration-150"
              style={{ color: 'hsl(var(--foreground))' }}
              title={skill.displayName}
            >
              {skill.displayName}
            </h3>
            <NamespaceBadge
              className="shrink-0"
              type="TEAM"
              name={`@${skill.namespace}`}
              title={`@${skill.namespace}`}
            />
          </div>
          {skill.summary && (
            <p className="mt-1 truncate text-xs leading-5 text-muted-foreground" title={skill.summary}>
              {skill.summary}
            </p>
          )}
        </div>

        <div className="hidden shrink-0 items-center gap-4 text-xs text-muted-foreground md:flex">
          {headlineVersion && (
            <span
              title={`v${headlineVersion.version}`}
              className="rounded-full bg-secondary/60 px-2.5 py-1 font-mono text-[11px] font-semibold text-secondary-foreground"
            >
              v{headlineVersion.version}
            </span>
          )}
          <span className="flex w-14 items-center justify-end gap-1" title={downloadLabel} aria-label={downloadLabel}>
            <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M9 19l3 3m0 0l3-3m-3 3V10" />
            </svg>
            {formatCompactCount(skill.downloadCount)}
          </span>
          <span
            className={`flex w-12 items-center justify-end gap-1 ${showStarredHighlight ? 'font-semibold text-amber-600 dark:text-amber-400' : 'text-muted-foreground'}`}
            title={starLabel}
            aria-label={starLabel}
          >
            <Bookmark className={`w-3.5 h-3.5 ${showStarredHighlight ? 'fill-current' : ''}`} />
            {skill.starCount}
          </span>
          {skill.ownerDisplayName && (
            <span className="flex w-28 items-center gap-1" title={skill.ownerDisplayName}>
              <User className="h-3 w-3 shrink-0" />
              <span className="truncate">{skill.ownerDisplayName}</span>
            </span>
          )}
          {skill.updatedAt && (
            <span className="flex w-24 items-center justify-end gap-1 whitespace-nowrap">
              <Clock className="w-3 h-3" />
              {formatRelativeTime(skill.updatedAt, i18n.language)}
            </span>
          )}
        </div>
      </div>
    </Card>
  )
}
