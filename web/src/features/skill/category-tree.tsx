import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { ChevronDown, ChevronRight, Folder, FolderOpen } from 'lucide-react'
import type { SkillCategoryNode } from '@/api/types'
import { cn } from '@/shared/lib/utils'

/** Extra indentation of a second-level row relative to its parent (UCD measures ~22px). */
const CHILD_INDENT_PX = 22
/** Left padding shared with the dashboard sidebar so the tree lines up with the rest of the chrome. */
const BASE_PADDING_PX = 12

type Row =
  | { kind: 'level1'; node: SkillCategoryNode }
  | { kind: 'level2'; node: SkillCategoryNode; parent: SkillCategoryNode }

interface CategoryTreeProps {
  categories: SkillCategoryNode[]
  /** Selected first-level slug, or undefined for "every category". */
  selectedCategory?: string
  /** Selected second-level slugs; OR-ed by the API. */
  selectedSubs?: string[]
  onSelectCategory: (slug: string | undefined) => void
  onToggleSub: (slug: string) => void
  isLoading?: boolean
  className?: string
}

/**
 * Two-level category tree for the discovery sidebar.
 *
 * There is deliberately no "all" node: UCD puts "全部" in the sort group, and the way back to an
 * unfiltered list is the `✕` on the toolbar chip. `aria-current` marks the active row, and
 * `aria-expanded` is only rendered on first-level rows because the product only supports two
 * levels — advertising an expandable second level would promise a third level that does not exist.
 *
 * Category names come from the server already localised, so they are rendered verbatim and never
 * passed through `t()`.
 */
export function CategoryTree({
  categories,
  selectedCategory,
  selectedSubs,
  onSelectCategory,
  onToggleSub,
  isLoading = false,
  className,
}: CategoryTreeProps) {
  const { t } = useTranslation()
  const [expanded, setExpanded] = useState<string[]>([])
  const [activeIndex, setActiveIndex] = useState(0)
  const rowRefs = useRef<Array<HTMLButtonElement | null>>([])

  // The selected category is always expanded: a collapsed branch that hides the active second-level
  // filter would make the sidebar contradict the chips in the toolbar.
  useEffect(() => {
    if (!selectedCategory) {
      return
    }
    setExpanded(previous => (previous.includes(selectedCategory) ? previous : [...previous, selectedCategory]))
  }, [selectedCategory])

  const rows = useMemo<Row[]>(() => {
    const next: Row[] = []
    for (const node of categories) {
      next.push({ kind: 'level1', node })
      if (!expanded.includes(node.slug)) {
        continue
      }
      for (const child of node.subCategories ?? []) {
        next.push({ kind: 'level2', node: child, parent: node })
      }
    }
    return next
  }, [categories, expanded])

  const rowCount = rows.length

  // Keep the roving tabindex inside the list when rows disappear (collapse, refetch).
  useEffect(() => {
    setActiveIndex(index => Math.min(index, Math.max(rowCount - 1, 0)))
  }, [rowCount])

  const focusRow = (index: number) => {
    setActiveIndex(index)
    rowRefs.current[index]?.focus()
  }

  const toggleExpanded = (slug: string) => {
    setExpanded(previous => (previous.includes(slug) ? previous.filter(item => item !== slug) : [...previous, slug]))
  }

  const handleKeyDown = (event: KeyboardEvent<HTMLButtonElement>, row: Row, index: number) => {
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      focusRow(Math.min(index + 1, rowCount - 1))
      return
    }
    if (event.key === 'ArrowUp') {
      event.preventDefault()
      focusRow(Math.max(index - 1, 0))
      return
    }
    if (event.key === 'ArrowRight') {
      event.preventDefault()
      if (row.kind === 'level1' && !expanded.includes(row.node.slug)) {
        toggleExpanded(row.node.slug)
      }
      return
    }
    if (event.key === 'ArrowLeft') {
      event.preventDefault()
      if (row.kind === 'level1') {
        if (expanded.includes(row.node.slug)) {
          toggleExpanded(row.node.slug)
        }
        return
      }
      // A second-level row collapses its parent and moves focus there, which is where the user's
      // mental model says the branch lives.
      toggleExpanded(row.parent.slug)
      const parentIndex = rows.findIndex(item => item.kind === 'level1' && item.node.slug === row.parent.slug)
      if (parentIndex >= 0) {
        focusRow(parentIndex)
      }
      return
    }
    if (event.key === 'Home') {
      event.preventDefault()
      focusRow(0)
      return
    }
    if (event.key === 'End') {
      event.preventDefault()
      focusRow(rowCount - 1)
      return
    }
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      if (row.kind === 'level1') {
        onSelectCategory(selectedCategory === row.node.slug ? undefined : row.node.slug)
        return
      }
      onToggleSub(row.node.slug)
    }
  }

  if (isLoading && categories.length === 0) {
    return (
      <div className={cn('space-y-1', className)} aria-busy="true">
        {[0, 1, 2, 3].map(row => (
          <div key={row} className="h-9 animate-pulse rounded-lg bg-muted/60" />
        ))}
      </div>
    )
  }

  if (categories.length === 0) {
    return (
      <p className={cn('px-3 py-2 text-xs text-muted-foreground', className)}>
        {t('search.categoryTreeEmpty')}
      </p>
    )
  }

  return (
    <div
      role="tree"
      aria-label={t('search.categoryTreeLabel')}
      className={cn('space-y-0.5', className)}
      // A single stable analytics id: dynamically created categories cannot supply literal ids,
      // so the specific category travels in `data-category-slug` instead.
      data-analytics-id="search.category.tree"
    >
      {rows.map((row, index) => {
        const isLevelOne = row.kind === 'level1'
        const isExpanded = isLevelOne && expanded.includes(row.node.slug)
        const isSelected = isLevelOne
          ? selectedCategory === row.node.slug
          : (selectedSubs ?? []).includes(row.node.slug)
        const paddingLeft = isLevelOne
          ? BASE_PADDING_PX
          : BASE_PADDING_PX + CHILD_INDENT_PX
        const Icon = isLevelOne ? (isExpanded ? FolderOpen : Folder) : undefined

        return (
          <button
            key={`${row.kind}-${row.node.slug}`}
            ref={element => {
              rowRefs.current[index] = element
            }}
            type="button"
            role="treeitem"
            aria-expanded={isLevelOne ? isExpanded : undefined}
            aria-current={isSelected ? 'true' : undefined}
            aria-level={isLevelOne ? 1 : 2}
            tabIndex={index === activeIndex ? 0 : -1}
            data-category-slug={row.node.slug}
            data-category-level={isLevelOne ? 1 : 2}
            title={row.node.displayName}
            onClick={() => {
              setActiveIndex(index)
              if (isLevelOne) {
                // Selecting a first-level row resets the second-level selection: a second-level
                // slug only means something inside its own parent.
                onSelectCategory(selectedCategory === row.node.slug ? undefined : row.node.slug)
                return
              }
              onToggleSub(row.node.slug)
            }}
            onKeyDown={event => handleKeyDown(event, row, index)}
            className={cn(
              'flex w-full items-center gap-2 rounded-lg py-2 text-left text-sm font-medium transition-colors duration-150',
              'hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
              isSelected ? 'bg-muted' : 'bg-transparent',
              // Level-2 rows carry neither an icon nor a chevron, so they need a text-hugging span.
              isLevelOne ? 'pr-2' : 'pr-2 text-[13px]',
              isSelected ? 'text-foreground' : 'text-[hsl(var(--text-secondary))]',
            )}
            style={{ paddingLeft: `${paddingLeft}px` }}
          >
            {isLevelOne ? (
              <>
                <span
                  aria-hidden="true"
                  className="flex h-4 w-4 shrink-0 items-center justify-center"
                  onClick={event => {
                    // The chevron is a separate affordance from "select this category".
                    event.stopPropagation()
                    toggleExpanded(row.node.slug)
                  }}
                >
                  {isExpanded ? (
                    <ChevronDown className="h-3.5 w-3.5 text-muted-foreground" />
                  ) : (
                    <ChevronRight className="h-3.5 w-3.5 text-muted-foreground" />
                  )}
                </span>
                {Icon ? <Icon className="h-4 w-4 shrink-0 text-muted-foreground" /> : null}
              </>
            ) : null}
            <span className="min-w-0 flex-1 truncate">{row.node.displayName}</span>
          </button>
        )
      })}
    </div>
  )
}
