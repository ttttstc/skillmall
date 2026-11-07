import { useTranslation } from 'react-i18next'
import { AlertTriangle } from 'lucide-react'
import { Button } from '@/shared/ui/button'
import { cn } from '@/shared/lib/utils'

interface LoadErrorProps {
  className?: string
  /** Inline variant for a single section inside a larger page. */
  compact?: boolean
  title?: string
  description?: string
  onRetry?: () => void
}

/**
 * Honest failure state for market surfaces.
 *
 * A failed request must never be presented as "no results" or as a permission denial: this
 * component states that loading failed and offers a retry when the caller can refetch.
 */
export function LoadError({ className, compact = false, title, description, onRetry }: LoadErrorProps) {
  const { t } = useTranslation()
  const resolvedTitle = title ?? t('common.loadFailedTitle')
  const resolvedDescription = description ?? t('common.loadFailedDescription')

  if (compact) {
    return (
      <div
        role="alert"
        className={cn(
          'flex flex-wrap items-center justify-between gap-3 rounded-xl border border-dashed border-border/70 bg-secondary/40 px-4 py-3 text-sm text-muted-foreground',
          className,
        )}
      >
        <span className="flex items-center gap-2">
          <AlertTriangle className="h-4 w-4 shrink-0" aria-hidden="true" />
          {resolvedTitle}
        </span>
        {onRetry ? (
          <Button variant="outline" size="sm" onClick={onRetry}>
            {t('common.retry')}
          </Button>
        ) : null}
      </div>
    )
  }

  return (
    <div role="alert" className={cn('flex flex-col items-center justify-center gap-3 py-16 text-center', className)}>
      <AlertTriangle className="h-8 w-8 text-muted-foreground" aria-hidden="true" />
      <div className="space-y-1">
        <p className="text-base font-semibold text-foreground">{resolvedTitle}</p>
        <p className="text-sm text-muted-foreground">{resolvedDescription}</p>
      </div>
      {onRetry ? (
        <Button variant="outline" size="sm" onClick={onRetry}>
          {t('common.retry')}
        </Button>
      ) : null}
    </div>
  )
}
