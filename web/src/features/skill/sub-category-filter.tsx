import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, ChevronDown, X } from 'lucide-react'
import type { SkillCategoryNode } from '@/api/types'
import { Button } from '@/shared/ui/button'
import { cn } from '@/shared/lib/utils'

interface SubCategoryFilterProps {
  /** Second-level nodes of the currently selected first-level category. */
  options: SkillCategoryNode[]
  selected: string[]
  onToggle: (slug: string) => void
  onClear: () => void
  /** False when no first-level category is selected: a second level only means something inside one. */
  enabled?: boolean
  className?: string
}

/**
 * Multi-select second-level filter with removable chips plus a checkbox panel.
 *
 * Chips are the primary surface because they show the active filter without opening anything; the
 * panel is only a picker. Selections are OR-ed by the API and are restricted to the selected
 * first-level category, so this control never has to reason about mixing categories.
 */
export function SubCategoryFilter({
  options,
  selected,
  onToggle,
  onClear,
  enabled = true,
  className,
}: SubCategoryFilterProps) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const containerRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    if (!open) {
      return
    }

    const onPointerDown = (event: MouseEvent) => {
      if (!containerRef.current?.contains(event.target as Node)) {
        setOpen(false)
      }
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false)
      }
    }

    document.addEventListener('mousedown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('mousedown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open])

  const selectedNodes = options.filter(option => selected.includes(option.slug))

  return (
    <div ref={containerRef} className={cn('relative flex min-w-0 flex-wrap items-center gap-1.5', className)}>
      {selectedNodes.map(node => (
        <span
          key={node.slug}
          data-category-slug={node.slug}
          className="inline-flex max-w-full items-center gap-1 rounded-md border border-border/70 bg-secondary/60 px-2 py-1 text-xs font-medium text-secondary-foreground"
        >
          <span className="truncate" title={node.displayName}>{node.displayName}</span>
          <button
            type="button"
            aria-label={t('search.subCategoryRemove', { name: node.displayName })}
            className="shrink-0 rounded-sm p-0.5 hover:bg-background/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            onClick={() => onToggle(node.slug)}
          >
            <X className="h-3 w-3" aria-hidden="true" />
          </button>
        </span>
      ))}

      <Button
        data-analytics-id="search.subcategory.open"
        type="button"
        variant="outline"
        size="sm"
        className="gap-1"
        aria-expanded={open}
        aria-haspopup="true"
        disabled={!enabled}
        title={enabled ? t('search.subCategoryPick') : t('search.subCategoryNeedsCategory')}
        onClick={() => setOpen(value => !value)}
      >
        <span className="truncate">{t('search.subCategoryPick')}</span>
        <ChevronDown className="h-3.5 w-3.5" aria-hidden="true" />
      </Button>

      {selected.length > 0 ? (
        <Button
          data-analytics-id="search.subcategory.clear"
          type="button"
          variant="ghost"
          size="sm"
          onClick={onClear}
        >
          {t('search.subCategoryClear')}
        </Button>
      ) : null}

      {open ? (
        <div
          role="group"
          aria-label={t('search.subCategoryPick')}
          className="absolute left-0 top-full z-30 mt-1 w-60 rounded-lg border border-border bg-popover p-1.5 shadow-[var(--shadow-card)]"
        >
          {options.length === 0 ? (
            <p className="px-2 py-1.5 text-xs text-muted-foreground">{t('search.subCategoryEmpty')}</p>
          ) : (
            options.map(option => {
              const isSelected = selected.includes(option.slug)
              return (
                <button
                  key={option.slug}
                  type="button"
                  role="checkbox"
                  aria-checked={isSelected}
                  data-category-slug={option.slug}
                  onClick={() => onToggle(option.slug)}
                  className="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                >
                  <span
                    aria-hidden="true"
                    className={cn(
                      'flex h-4 w-4 shrink-0 items-center justify-center rounded border',
                      isSelected
                        ? 'border-primary bg-primary text-primary-foreground'
                        : 'border-border bg-background',
                    )}
                  >
                    {isSelected ? <Check className="h-3 w-3" /> : null}
                  </span>
                  <span className="min-w-0 flex-1 truncate" title={option.displayName}>{option.displayName}</span>
                  <span className="shrink-0 text-xs text-muted-foreground">{option.count}</span>
                </button>
              )
            })
          )}
        </div>
      ) : null}
    </div>
  )
}
