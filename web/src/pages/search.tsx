import { startTransition, useEffect, useRef, useState } from 'react'
import { useNavigate, useSearch } from '@tanstack/react-router'
import { useTranslation } from 'react-i18next'
import { Loader2, LayoutGrid, List, RefreshCw, X } from 'lucide-react'
import type { SkillSummary } from '@/api/types'
import { useAuth } from '@/features/auth/use-auth'
import { DomainTree } from '@/features/search/domain-tree'
import { SearchBar } from '@/features/search/search-bar'
import { SkillCard } from '@/features/skill/skill-card'
import { SkillListRow } from '@/features/skill/skill-list-row'
import { SkeletonList } from '@/shared/components/skeleton-loader'
import { EmptyState } from '@/shared/components/empty-state'
import { LoadError } from '@/shared/components/load-error'
import { useSearchSkills } from '@/shared/hooks/use-skill-queries'
import { useVisibleLabels } from '@/shared/hooks/use-label-queries'
import { useMyStars } from '@/shared/hooks/use-user-queries'
import { toRouterPath } from '@/shared/lib/base-path'
import { cn } from '@/shared/lib/utils'
import { formatNamespaceSearchInput, normalizeSearchQuery, parseNamespaceSearchInput } from '@/shared/lib/search-query'
import { Button } from '@/shared/ui/button'
import { APP_SHELL_PAGE_CLASS_NAME } from '@/app/page-shell-style'

const PAGE_SIZE = 12

/**
 * Ordering tabs. Only the orderings the registry can actually serve are offered: relevance,
 * install count and newest. The UCD comp also shows "all" and "popular" tabs, which would map to
 * the same two orderings here, so they stay out until the search API can back them.
 */
const SORT_OPTIONS = [
  { value: 'relevance', labelKey: 'search.sort.relevance' },
  { value: 'downloads', labelKey: 'search.sort.downloads' },
  { value: 'newest', labelKey: 'search.sort.newest' },
] as const

const RAIL_ITEM_CLASS_NAME = 'h-9 w-full justify-start rounded-lg px-2.5 text-sm font-normal'
const RAIL_ITEM_ACTIVE_CLASS_NAME = 'border-transparent bg-muted text-foreground shadow-none hover:bg-muted hover:text-foreground'
const CHIP_CLASS_NAME = 'h-7 min-w-0 gap-1 rounded-md border-0 bg-secondary px-2 text-xs font-normal text-secondary-foreground shadow-none hover:bg-secondary/70'

function blurActiveElement() {
  if (typeof document === 'undefined' || typeof HTMLElement === 'undefined') {
    return
  }

  if (document.activeElement instanceof HTMLElement) {
    document.activeElement.blur()
  }
}

function scrollToTopOnPageChange() {
  if (typeof window === 'undefined') {
    return () => {}
  }

  let secondFrame = 0
  const firstFrame = window.requestAnimationFrame(() => {
    window.scrollTo({ top: 0, behavior: 'auto' })
    secondFrame = window.requestAnimationFrame(() => {
      window.scrollTo({ top: 0, behavior: 'auto' })
    })
  })

  return () => {
    window.cancelAnimationFrame(firstFrame)
    if (secondFrame) {
      window.cancelAnimationFrame(secondFrame)
    }
  }
}

/**
 * Skill discovery page with synchronized URL state.
 *
 * Search text, sorting, pagination, and the starred-only filter are mirrored into router search
 * params so the page can be shared, restored, and revisited without losing state.
 */
function filterStarredSkills(skills: SkillSummary[], query: string, namespace: string): SkillSummary[] {
  const normalizedQuery = query.trim().toLowerCase()
  const normalizedNamespace = namespace.trim().toLowerCase()

  return skills.filter((skill) => {
    const matchesNamespace = !normalizedNamespace || skill.namespace.toLowerCase() === normalizedNamespace
    if (!matchesNamespace) {
      return false
    }
    if (!normalizedQuery) {
      return true
    }
    return [skill.displayName, skill.summary, skill.namespace, skill.slug]
        .filter(Boolean)
        .some((value) => value!.toLowerCase().includes(normalizedQuery))
  })
}

function sortStarredSkills(skills: SkillSummary[], sort: string): SkillSummary[] {
  const sorted = [...skills]
  if (sort === 'downloads') {
    return sorted.sort((left, right) => right.downloadCount - left.downloadCount)
  }
  if (sort === 'newest' || sort === 'relevance') {
    return sorted.sort((left, right) => new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime())
  }
  return sorted
}

export function SearchPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const searchParams = useSearch({ from: '/search' })
  const { isAuthenticated } = useAuth()

  const q = normalizeSearchQuery(searchParams.q || '')
  const namespace = (searchParams.namespace || '').replace(/^@/, '')
  const selectedLabel = searchParams.label || ''
  const sort = searchParams.sort || 'newest'
  const page = searchParams.page ?? 0
  const starredOnly = searchParams.starredOnly ?? false
  const view = searchParams.view === 'list' ? 'list' : 'grid'
  const [queryInput, setQueryInput] = useState(formatNamespaceSearchInput(namespace, q))
  const previousPageRef = useRef(page)
  const loadingMoreRef = useRef(false)
  const [loadedPages, setLoadedPages] = useState<{ key: string; page: number; items: SkillSummary[] } | null>(null)
  const resultsKey = JSON.stringify([q, namespace, selectedLabel, sort, starredOnly])

  useEffect(() => {
    setQueryInput(formatNamespaceSearchInput(namespace, q))
  }, [namespace, q])

  useEffect(() => {
    if (previousPageRef.current !== page) {
      blurActiveElement()
      const cleanupScroll = loadingMoreRef.current ? () => {} : scrollToTopOnPageChange()
      loadingMoreRef.current = false

      previousPageRef.current = page
      return () => {
        cleanupScroll()
      }
    }

    previousPageRef.current = page
  }, [page])

  const { data, isLoading, isFetching, isPlaceholderData, isError, refetch } = useSearchSkills({
    q,
    namespace: namespace || undefined,
    label: selectedLabel || undefined,
    sort,
    page,
    size: PAGE_SIZE,
    starredOnly,
  }, !starredOnly)
  const { data: labels } = useVisibleLabels()
  const {
    data: starredSkills,
    isLoading: isLoadingStarred,
    isFetching: isFetchingStarred,
    isError: isErrorStarred,
    refetch: refetchStarred,
  } = useMyStars(starredOnly && isAuthenticated)
  const viewSearch = view === 'list' ? ('list' as const) : undefined
  const handleSearch = (query: string) => {
    const parsedInput = parseNamespaceSearchInput(query)
    setQueryInput(query)
    startTransition(() => {
      navigate({ to: '/search', search: { q: parsedInput.query, namespace: parsedInput.namespace, label: selectedLabel, sort, page: 0, starredOnly, view: viewSearch }, replace: true })
    })
  }

  const handleSortChange = (newSort: string) => {
    navigate({ to: '/search', search: { q, namespace, label: selectedLabel, sort: newSort, page: 0, starredOnly, view: viewSearch } })
  }

  const handleViewChange = (nextView: 'grid' | 'list') => {
    if (nextView === view) {
      return
    }
    blurActiveElement()
    navigate({ to: '/search', search: { q, namespace, label: selectedLabel, sort, page, starredOnly, view: nextView === 'list' ? 'list' as const : undefined } })
  }

  const handleLabelToggle = (label: string) => {
    const nextLabel = selectedLabel === label ? '' : label
    navigate({ to: '/search', search: { q, namespace, label: nextLabel, sort, page: 0, starredOnly, view: viewSearch } })
  }

  const handleNamespaceClear = () => {
    navigate({ to: '/search', search: { q, namespace: '', label: selectedLabel, sort, page: 0, starredOnly, view: viewSearch } })
  }

  const handleStarredToggle = () => {
    if (!isAuthenticated) {
      navigate({
        to: '/login',
        search: {
          returnTo: toRouterPath(window.location.pathname, window.location.search, window.location.hash),
        },
      })
      return
    }

    navigate({ to: '/search', search: { q, namespace, label: selectedLabel, sort, page: 0, starredOnly: !starredOnly, view: viewSearch } })
  }

  const handleSkillClick = (namespace: string, slug: string) => {
    navigate({
      to: `/space/${namespace}/${encodeURIComponent(slug)}`,
      search: { returnTo: toRouterPath(window.location.pathname, window.location.search) },
    })
  }

  const filteredStarredSkills = starredOnly
    ? sortStarredSkills(filterStarredSkills(starredSkills ?? [], q, namespace), sort)
    : []
  const starredPageItems = starredOnly
    ? filteredStarredSkills.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE)
    : []
  const totalPages = starredOnly
    ? Math.ceil(filteredStarredSkills.length / PAGE_SIZE)
    : data
      ? Math.ceil(data.total / data.size)
      : 0
  const currentPageItems = starredOnly
    ? starredPageItems
    : (data?.items ?? [])
  const previousItems = loadedPages?.key === resultsKey && loadedPages.page + 1 === page
    ? loadedPages.items
    : []
  const displayItems = previousItems.length > 0
    ? [...previousItems, ...(isPlaceholderData && !starredOnly ? [] : currentPageItems)]
    : currentPageItems
  const handleLoadMore = () => {
    if (isFetching || page + 1 >= totalPages) return
    setLoadedPages({ key: resultsKey, page, items: displayItems })
    loadingMoreRef.current = true
    navigate({ to: '/search', search: { q, namespace, label: selectedLabel, sort, page: page + 1, starredOnly, view: viewSearch } })
  }
  const isPageLoading = starredOnly ? isLoadingStarred : isLoading
  const isUpdatingResults = starredOnly
    ? isFetchingStarred && !isLoadingStarred
    : isFetching && !isLoading
  // A failed request must be reported as a failure, never folded into an empty result set.
  const resultsError = starredOnly ? isErrorStarred : isError
  const retryResults = () => {
    if (starredOnly) {
      void refetchStarred()
      return
    }
    void refetch()
  }
  const resultCount = starredOnly
    ? filteredStarredSkills.length
    : (data?.total ?? 0)
  const selectedLabelName = labels?.find((label) => label.slug === selectedLabel)?.displayName ?? selectedLabel
  const hasActiveFilters = Boolean(selectedLabel || namespace || starredOnly)

  return (
    <div className={APP_SHELL_PAGE_CLASS_NAME}>
      {/* Page Heading: title + result count share one baseline, as in the UCD. */}
      <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
        <h1 className="text-3xl font-bold tracking-tight" style={{ color: 'hsl(var(--foreground))' }}>
          {t('search.allSkillsTitle')}
        </h1>
        <p className="text-base text-muted-foreground">
          {resultCount > 0 ? t('search.results', { count: resultCount }) : t('search.allSkillsHint')}
        </p>
      </div>

      <div className="grid items-start gap-3 lg:grid-cols-[236px_minmax(0,1fr)]">
        {/* Left column: domain tree on top, the filters the registry can actually serve below it. */}
        <aside className="min-w-0 space-y-3" aria-label={t('home.domainTitle')}>
          <DomainTree />

          <div className="border-t border-border/60 pt-3" role="group" aria-label={t('search.filters.label')}>
            <div className="flex flex-wrap items-center gap-2">
              <Button data-analytics-id="search.starred.filter"
                className={cn(RAIL_ITEM_CLASS_NAME, starredOnly && RAIL_ITEM_ACTIVE_CLASS_NAME)}
                variant={starredOnly ? 'default' : 'outline'}
                size="sm"
                onClick={handleStarredToggle}
              >
                {t('search.filterStarred')}
              </Button>
              {!starredOnly && labels?.map((label) => (
                <Button data-analytics-id="search.label.filter"
                  className={cn(RAIL_ITEM_CLASS_NAME, selectedLabel === label.slug && RAIL_ITEM_ACTIVE_CLASS_NAME)}
                  key={label.slug}
                  variant={selectedLabel === label.slug ? 'default' : 'outline'}
                  size="sm"
                  onClick={() => handleLabelToggle(label.slug)}
                >
                  {label.displayName}
                </Button>
              ))}
            </div>
          </div>
        </aside>

        <div className="min-w-0 space-y-5">
          {/* One toolbar row: ordering tabs, search field, applied-filter chips, view switch. */}
          <div className="space-y-3">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="flex min-w-0 flex-wrap items-center gap-3">
                <div className="flex max-w-full flex-wrap gap-2">
                  {SORT_OPTIONS.map((option) => (
                    <Button data-analytics-id={
                      option.value === 'relevance'
                        ? 'search.sort.relevance'
                        : option.value === 'downloads'
                          ? 'search.sort.downloads'
                          : 'search.sort.newest'
                    }
                      key={option.value}
                      className="rounded-full px-4"
                      variant={sort === option.value ? 'default' : 'outline'}
                      onClick={() => handleSortChange(option.value)}
                    >
                      {t(option.labelKey)}
                    </Button>
                  ))}
                </div>
              </div>

              <div className="flex min-w-0 flex-wrap items-center gap-2">
                <div className="w-[210px] max-w-full">
                  <SearchBar analyticsId="search.query.submit"
                    appearance="toolbar"
                    value={queryInput}
                    isSearching={isUpdatingResults}
                    onChange={setQueryInput}
                    onSearch={handleSearch}
                  />
                </div>

                {/* Applied filters, each removable in place. Rendered only when a filter is on:
                    an always-visible but inert box would read as a dead control. */}
                {hasActiveFilters ? (
                  <div
                    className="flex h-9 w-[208px] max-w-full items-center gap-1 overflow-hidden rounded-lg border border-border bg-card px-1.5"
                    role="group"
                    aria-label={t('search.filters.label')}
                  >
                    {namespace ? (
                      <Button data-analytics-id="search.namespace.clear"
                        className={CHIP_CLASS_NAME}
                        variant="secondary"
                        size="sm"
                        onClick={handleNamespaceClear}
                      >
                        <span className="truncate">{t('search.namespaceFilter', { namespace })}</span>
                        <X aria-hidden="true" className="h-3 w-3 shrink-0 opacity-60" />
                      </Button>
                    ) : null}
                    {selectedLabel ? (
                      <Button className={CHIP_CLASS_NAME}
                        variant="secondary"
                        size="sm"
                        onClick={() => handleLabelToggle(selectedLabel)}
                      >
                        <span className="truncate">{selectedLabelName}</span>
                        <X aria-hidden="true" className="h-3 w-3 shrink-0 opacity-60" />
                      </Button>
                    ) : null}
                    {starredOnly ? (
                      <Button className={CHIP_CLASS_NAME}
                        variant="secondary"
                        size="sm"
                        onClick={handleStarredToggle}
                      >
                        <span className="truncate">{t('search.filterStarred')}</span>
                        <X aria-hidden="true" className="h-3 w-3 shrink-0 opacity-60" />
                      </Button>
                    ) : null}
                  </div>
                ) : null}

                <div
                  className="ml-1 flex shrink-0 items-center gap-0.5 rounded-lg bg-muted p-0.5"
                  role="group"
                  aria-label={t('search.viewToggleLabel')}
                >
                  <Button data-analytics-id="search.view.list"
                    className={cn(
                      'h-8 w-8 rounded-md px-0',
                      view === 'list' ? 'bg-background text-foreground shadow-sm hover:bg-background' : 'text-muted-foreground hover:bg-background/60',
                    )}
                    variant="ghost"
                    size="sm"
                    aria-pressed={view === 'list'}
                    aria-label={t('search.viewList')}
                    title={t('search.viewList')}
                    onClick={() => handleViewChange('list')}
                  >
                    <List className="h-4 w-4" />
                  </Button>
                  <Button data-analytics-id="search.view.grid"
                    className={cn(
                      'h-8 w-8 rounded-md px-0',
                      view === 'grid' ? 'bg-background text-foreground shadow-sm hover:bg-background' : 'text-muted-foreground hover:bg-background/60',
                    )}
                    variant="ghost"
                    size="sm"
                    aria-pressed={view === 'grid'}
                    aria-label={t('search.viewGrid')}
                    title={t('search.viewGrid')}
                    onClick={() => handleViewChange('grid')}
                  >
                    <LayoutGrid className="h-4 w-4" />
                  </Button>
                </div>
              </div>
            </div>

            {isUpdatingResults ? (
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <Loader2 className="h-4 w-4 animate-spin" />
                <span>{t('search.loadingMore')}</span>
              </div>
            ) : null}
          </div>

          {/* Results */}
          {resultsError ? (
            <LoadError onRetry={retryResults} />
          ) : isPageLoading ? (
            <SkeletonList count={PAGE_SIZE} />
          ) : displayItems.length > 0 ? (
            <>
              {view === 'list' ? (
                <div className="overflow-hidden rounded-xl border border-border/60 bg-card shadow-[var(--shadow-card)] [&>div+div]:border-t [&>div+div]:border-border/60" data-testid="skill-list-view">
                  {displayItems.map((skill, idx) => (
                    <div key={skill.id} className={`animate-fade-up delay-${Math.min(idx % 6 + 1, 6)}`}>
                      <SkillListRow analyticsId="skill.card.open"
                        skill={skill}
                        highlightStarred
                        onClick={() => handleSkillClick(skill.namespace, skill.slug)}
                      />
                    </div>
                  ))}
                </div>
              ) : (
                <div className="grid grid-cols-1 gap-x-3 gap-y-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4" data-testid="skill-grid-view">
                  {displayItems.map((skill, idx) => (
                    <div key={skill.id} className={`h-full animate-fade-up delay-${Math.min(idx % 6 + 1, 6)}`}>
                      <SkillCard analyticsId="skill.card.open"
                        skill={skill}
                        market
                        highlightStarred
                        onClick={() => handleSkillClick(skill.namespace, skill.slug)}
                      />
                    </div>
                  ))}
                </div>
              )}
              {page + 1 < totalPages && (
                <div className="flex justify-center">
                  <Button className="gap-2 px-3 text-sm font-normal text-muted-foreground hover:bg-transparent hover:text-foreground"
                    variant="ghost"
                    onClick={handleLoadMore}
                    disabled={isFetching || isFetchingStarred}
                  >
                    {isUpdatingResults ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
                    {t('search.loadMore')}
                  </Button>
                </div>
              )}
            </>
          ) : (
            <EmptyState
              title={starredOnly ? t('search.noStarredResults') : t('search.noResults')}
              description={
                starredOnly
                  ? (q ? t('search.noStarredResultsFor', { q }) : t('search.noStarredSkills'))
                  : (q ? t('search.noResultsFor', { q }) : undefined)
              }
            />
          )}
        </div>
      </div>
    </div>
  )
}
