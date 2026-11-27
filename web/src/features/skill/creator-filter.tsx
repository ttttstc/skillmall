import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ChevronDown, User, X } from 'lucide-react'
import type { SkillAuthor } from '@/api/types'
import { useSkillAuthors } from '@/shared/hooks/use-category-queries'
import { Button } from '@/shared/ui/button'
import { Input } from '@/shared/ui/input'
import { cn } from '@/shared/lib/utils'

const DEBOUNCE_MS = 250

interface CreatorFilterProps {
  /** Selected creator user id, or undefined for "anyone". */
  value?: string
  onSelect: (author: SkillAuthor | null) => void
  className?: string
}

/**
 * Creator (not owner) filter: type to search people who have publicly distributable skills.
 *
 * The URL only carries a user id, so the picker resolves the display name itself instead of
 * storing a second copy in the query string that could drift from the server's value.
 */
export function CreatorFilter({ value, onSelect, className }: CreatorFilterProps) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [debounced, setDebounced] = useState('')
  const containerRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(query.trim()), DEBOUNCE_MS)
    return () => window.clearTimeout(timer)
  }, [query])

  // Resolve the name for a creator restored from the URL. Cheap: same query key the picker uses
  // with an empty search, so TanStack Query shares one request.
  const { data: allAuthors } = useSkillAuthors('', Boolean(value))
  const { data: searchResults, isLoading } = useSkillAuthors(debounced, open)

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

  const selectedAuthor = value
    ? (allAuthors?.items ?? []).find(author => author.userId === value)
      ?? (searchResults?.items ?? []).find(author => author.userId === value)
    : undefined
  const options = searchResults?.items ?? []

  return (
    <div ref={containerRef} className={cn('relative flex min-w-0 items-center gap-1.5', className)}>
      {value ? (
        <span className="inline-flex max-w-full items-center gap-1 rounded-md border border-border/70 bg-secondary/60 px-2 py-1 text-xs font-medium text-secondary-foreground">
          <User className="h-3 w-3 shrink-0" aria-hidden="true" />
          <span className="truncate" title={selectedAuthor?.displayName ?? value}>
            {t('search.creatorChip', { name: selectedAuthor?.displayName ?? value })}
          </span>
          <button
            type="button"
            aria-label={t('search.creatorClear')}
            className="shrink-0 rounded-sm p-0.5 hover:bg-background/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            onClick={() => onSelect(null)}
          >
            <X className="h-3 w-3" aria-hidden="true" />
          </button>
        </span>
      ) : (
        <Button
          data-analytics-id="search.creator.open"
          type="button"
          variant="outline"
          size="sm"
          className="gap-1"
          aria-expanded={open}
          aria-haspopup="true"
          onClick={() => setOpen(current => !current)}
        >
          <User className="h-3.5 w-3.5" aria-hidden="true" />
          <span className="truncate">{t('search.creatorPick')}</span>
          <ChevronDown className="h-3.5 w-3.5" aria-hidden="true" />
        </Button>
      )}

      {open ? (
        <div className="absolute right-0 top-full z-30 mt-1 w-64 rounded-lg border border-border bg-popover p-1.5 shadow-[var(--shadow-card)]">
          <Input
            autoFocus
            value={query}
            onChange={event => setQuery(event.target.value)}
            placeholder={t('search.creatorSearchPlaceholder')}
            aria-label={t('search.creatorSearchPlaceholder')}
            className="mb-1.5 h-8 text-sm"
          />
          {isLoading ? (
            <p className="px-2 py-1.5 text-xs text-muted-foreground">{t('search.creatorLoading')}</p>
          ) : options.length === 0 ? (
            <p className="px-2 py-1.5 text-xs text-muted-foreground">{t('search.creatorEmpty')}</p>
          ) : (
            options.map(author => (
              <button
                key={author.userId}
                type="button"
                data-creator-id={author.userId}
                onClick={() => {
                  onSelect(author)
                  setOpen(false)
                  setQuery('')
                }}
                className="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                <span className="min-w-0 flex-1 truncate" title={author.displayName}>{author.displayName}</span>
                <span className="shrink-0 text-xs text-muted-foreground">{author.skillCount}</span>
              </button>
            ))
          )}
        </div>
      ) : null}
    </div>
  )
}
