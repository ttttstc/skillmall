import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Loader2, Search, X } from 'lucide-react'
import { MAX_SEARCH_INPUT_LENGTH } from '@/shared/lib/search-query'
import { Input } from '@/shared/ui/input'
import { Button } from '@/shared/ui/button'

interface SearchBarProps {
  analyticsId?: string
  defaultValue?: string
  value?: string
  placeholder?: string
  isSearching?: boolean
  onChange?: (query: string) => void
  onSearch?: (query: string) => void
  /**
   * `default` is the boxed bar with a trailing submit button (landing hero, namespace pages).
   * `toolbar` is the single-line field used inside the discovery toolbar: the leading magnifier
   * is the submit control, so the field keeps exactly the height of the surrounding pills, chip
   * group and view toggle. Submission behaviour (click or Enter) is identical.
   */
  appearance?: 'default' | 'toolbar'
}

/**
 * Shared search bar used by search-driven pages and landing surfaces.
 *
 * It supports both controlled and uncontrolled usage so page-level containers can decide whether
 * query text should be driven from URL state or local form state.
 */
export function SearchBar({
  defaultValue = '',
  value,
  placeholder,
  isSearching = false,
  onChange,
  onSearch,
  analyticsId,
  appearance = 'default',
}: SearchBarProps) {
  const { t } = useTranslation()
  const [query, setQuery] = useState(defaultValue)
  const isControlled = value !== undefined
  const currentQuery = isControlled ? value : query
  const isToolbar = appearance === 'toolbar'

  useEffect(() => {
    if (!isControlled) {
      setQuery(defaultValue)
    }
  }, [defaultValue, isControlled])

  const handleChange = (nextQuery: string) => {
    if (!isControlled) {
      setQuery(nextQuery)
    }
    onChange?.(nextQuery)
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (onSearch) {
      onSearch(currentQuery)
    }
  }

  const handleClear = () => {
    handleChange('')
  }

  return (
    <form
      data-analytics-id={onSearch ? analyticsId : undefined}
      onSubmit={handleSubmit}
      className={
        isToolbar
          ? 'flex h-9 min-w-0 items-center gap-0.5 rounded-lg border border-border bg-card pl-0.5 pr-1 transition-[border-color,box-shadow] duration-150 ease-out focus-within:border-ring focus-within:ring-2 focus-within:ring-ring/15'
          : 'flex gap-2 rounded-lg border border-border/60 bg-card p-1.5 shadow-[var(--shadow-card)]'
      }
    >
      {isToolbar ? (
        // The magnifier doubles as the submit affordance: the UCD toolbar has no trailing button,
        // while keyboard users and automation still get one control named like the submit action.
        <button
          type="submit"
          aria-label={t('searchBar.button')}
          title={t('searchBar.button')}
          className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/40"
        >
          {isSearching ? (
            <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin" />
          ) : (
            <Search aria-hidden="true" className="h-4 w-4" />
          )}
        </button>
      ) : null}

      <div className={isToolbar ? 'relative min-w-0 flex-1' : 'relative flex-1'}>
        {isToolbar ? null : (
          <Search className="absolute left-3 top-1/2 h-5 w-5 -translate-y-1/2 text-muted-foreground pointer-events-none" />
        )}
        <Input
          type="text"
          value={currentQuery}
          onChange={(e) => handleChange(e.target.value)}
          maxLength={MAX_SEARCH_INPUT_LENGTH}
          placeholder={placeholder || t('searchBar.placeholder')}
          className={
            isToolbar
              ? 'h-8 border-0 bg-transparent pl-1 pr-8 text-sm focus-visible:ring-0 focus-visible:ring-offset-0'
              : 'pl-10 pr-10 border-0 bg-transparent focus-visible:ring-0 focus-visible:ring-offset-0 h-10'
          }
        />
        {currentQuery ? (
          <button data-analytics-id={analyticsId ? 'search.query.clear' : undefined}
            type="button"
            onClick={handleClear}
            className={
              isToolbar
                ? 'absolute right-0 top-1/2 inline-flex h-6 w-6 -translate-y-1/2 items-center justify-center rounded-full text-muted-foreground/70 transition-colors hover:bg-secondary hover:text-foreground'
                : 'absolute right-3 top-1/2 inline-flex h-7 w-7 -translate-y-1/2 items-center justify-center rounded-full text-muted-foreground transition-colors hover:bg-secondary/70 hover:text-foreground'
            }
            aria-label={t('searchBar.clear')}
            title={t('searchBar.clear')}
          >
            <X className={isToolbar ? 'h-3.5 w-3.5' : 'h-4 w-4'} />
          </button>
        ) : null}
      </div>

      {isToolbar ? null : (
        <Button type="submit" size="default" className="px-5 min-w-24 rounded-md" disabled={isSearching}>
          {isSearching ? <Loader2 className="h-4 w-4 animate-spin" /> : t('searchBar.button')}
        </Button>
      )}
    </form>
  )
}
