import type { SkillSummary } from '@/api/types'
import { Card } from '@/shared/ui/card'
import { SkillAvatar } from '@/features/skill/skill-avatar'

export interface RankingRowView {
  skill: SkillSummary
  /** Short right-hand label: a download count or a first-publication date. */
  meta: string | null
}

interface RankingListProps {
  title: string
  description: string
  analyticsId: string
  rows: RankingRowView[]
  isLoading: boolean
  isError: boolean
  errorLabel: string
  emptyLabel: string
  onSelect: (skill: SkillSummary) => void
}

/**
 * One home ranking. Order is the ranking, so rows render exactly as received.
 *
 * A short list or an empty list is shown as-is: the card never pads itself with placeholders,
 * because a missing skill must look missing rather than invented.
 */
export function RankingList({
  title,
  description,
  analyticsId,
  rows,
  isLoading,
  isError,
  errorLabel,
  emptyLabel,
  onSelect,
}: RankingListProps) {
  return (
    <Card
      data-analytics-id={analyticsId}
      className="flex min-w-0 flex-col gap-4 rounded-md border border-border/60 bg-card p-5"
    >
      <div className="space-y-1">
        <h3 className="text-lg font-semibold tracking-tight" style={{ color: 'hsl(var(--foreground))' }}>
          {title}
        </h3>
        <p className="text-sm" style={{ color: 'hsl(var(--muted-foreground))' }}>
          {description}
        </p>
      </div>

      {isLoading ? (
        <div className="space-y-2" aria-hidden="true">
          {Array.from({ length: 5 }).map((_, index) => (
            <div key={index} className="h-11 animate-shimmer rounded-md" />
          ))}
        </div>
      ) : isError ? (
        <p className="py-6 text-sm" style={{ color: 'hsl(var(--muted-foreground))' }}>
          {errorLabel}
        </p>
      ) : rows.length === 0 ? (
        <p className="py-6 text-sm" style={{ color: 'hsl(var(--muted-foreground))' }}>
          {emptyLabel}
        </p>
      ) : (
        <ol className="space-y-1">
          {rows.map((row, index) => (
            <li key={row.skill.id}>
              <button
                type="button"
                onClick={() => onSelect(row.skill)}
                className="flex w-full min-w-0 items-center gap-3 rounded-md px-2 py-2 text-left transition-colors hover:bg-muted/60 focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-ring/15"
              >
                <span
                  aria-hidden="true"
                  className="w-5 shrink-0 text-sm font-semibold tabular-nums"
                  style={{ color: index < 3 ? 'hsl(var(--foreground))' : 'hsl(var(--muted-foreground))' }}
                >
                  {index + 1}
                </span>
                <SkillAvatar
                  name={row.skill.displayName || row.skill.slug}
                  url={row.skill.avatarUrl}
                  size="h-8 w-8"
                />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm font-medium" style={{ color: 'hsl(var(--foreground))' }}>
                    {row.skill.displayName || row.skill.slug}
                  </span>
                  <span className="block truncate text-xs" style={{ color: 'hsl(var(--muted-foreground))' }}>
                    @{row.skill.namespace}
                  </span>
                </span>
                {row.meta ? (
                  <span className="shrink-0 text-xs tabular-nums" style={{ color: 'hsl(var(--muted-foreground))' }}>
                    {row.meta}
                  </span>
                ) : null}
              </button>
            </li>
          ))}
        </ol>
      )}
    </Card>
  )
}
