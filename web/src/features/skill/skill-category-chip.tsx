import { useTranslation } from 'react-i18next'
import type { SkillCategoryRef } from '@/api/types'
import { cn } from '@/shared/lib/utils'

interface SkillCategoryChipProps {
  /** Server-resolved first-level category. */
  category?: SkillCategoryRef | null
  /** Server-resolved second-level category; takes precedence over the first level when present. */
  subCategory?: SkillCategoryRef | null
  /**
   * Legacy wire value (DESIGN, TESTING …). Only used as a fallback while a projection still omits
   * `category`, so that mixed-version deployments keep showing something honest.
   */
  domain?: string
  variant?: 'badge' | 'pill'
  className?: string
}

/**
 * The one place that decides which category name a skill shows on a listing surface.
 *
 * Rule: second level wins, then first level, then the legacy enum. The name is rendered verbatim
 * because the server has already localised it; only the legacy `domain` fallback goes through
 * `t()`, since that value is still a fixed i18n key.
 */
export function SkillCategoryChip({
  category,
  subCategory,
  domain,
  variant = 'badge',
  className,
}: SkillCategoryChipProps) {
  const { t } = useTranslation()

  const serverLabel = subCategory?.displayName ?? category?.displayName
  const label = serverLabel ?? (domain ? t(`home.domain.${domain}`) : undefined)

  if (!label) {
    return null
  }

  return (
    <span
      data-skillslug={subCategory?.slug ?? category?.slug}
      data-skillslug-level={subCategory ? 2 : 1}
      title={label}
      className={cn(
        'inline-flex max-w-full shrink-0 items-center text-xs font-medium text-secondary-foreground',
        variant === 'pill'
          ? 'rounded-full border border-border bg-secondary/60 px-2 py-0.5'
          : 'rounded-md border border-border/70 bg-secondary/60 px-1.5 py-0.5',
        className,
      )}
    >
      <span className="truncate">{label}</span>
    </span>
  )
}

interface SkillCategoryPathProps {
  category?: SkillCategoryRef | null
  subCategory?: SkillCategoryRef | null
  domain?: string
  className?: string
}

/**
 * Detail-page presentation: first level and second level are shown separately so a visitor can
 * tell "this skill is under Testing › Integration verification" rather than seeing only the leaf.
 * The second level is omitted entirely when untagged — an empty placeholder would read as missing
 * data instead of "no second level yet".
 */
export function SkillCategoryPath({ category, subCategory, domain, className }: SkillCategoryPathProps) {
  const { t } = useTranslation()
  const firstLevel = category?.displayName ?? (domain ? t(`home.domain.${domain}`) : undefined)
  const secondLevel = subCategory?.displayName

  if (!firstLevel && !secondLevel) {
    return null
  }

  return (
    <span className={cn('inline-flex min-w-0 flex-wrap items-center gap-1', className)}>
      {firstLevel ? (
        <span
          data-category-slug={category?.slug}
          data-category-level={1}
          className="inline-flex max-w-full items-center rounded-md border border-border/70 bg-secondary/60 px-1.5 py-0.5 text-xs font-medium text-secondary-foreground"
        >
          <span className="truncate" title={firstLevel}>{firstLevel}</span>
        </span>
      ) : null}
      {secondLevel ? (
        <>
          <span aria-hidden="true" className="text-xs text-muted-foreground">/</span>
          <span
            data-category-slug={subCategory?.slug}
            data-category-level={2}
            className="inline-flex max-w-full items-center rounded-md border border-border/70 bg-secondary/60 px-1.5 py-0.5 text-xs font-medium text-secondary-foreground"
          >
            <span className="truncate" title={secondLevel}>{secondLevel}</span>
          </span>
        </>
      ) : null}
    </span>
  )
}
