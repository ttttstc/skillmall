import { startTransition, useEffect, useRef, useState } from 'react'
import { useNavigate, useSearch } from '@tanstack/react-router'
import { useTranslation } from 'react-i18next'
import { Loader2, LayoutGrid, List } from 'lucide-react'
import type { SkillSummary } from '@/api/types'
import { useAuth } from '@/features/auth/use-auth'
import { SearchBar } from '@/features/search/search-bar'
import { SkillCard } from '@/features/skill/skill-card'
import { SkillListRow } from '@/features/skill/skill-list-row'
import { SkeletonList } from '@/shared/components/skeleton-loader'
import { EmptyState } from '@/shared/components/empty-state'
import { Pagination } from '@/shared/components/pagination'
import { useSearchSkills } from '@/shared/hooks/use-skill-queries'
import { useVisibleLabels } from '@/shared/hooks/use-label-queries'
import { useMyStars } from '@/shared/hooks/use-user-queries'
import { toRouterPath } from '@/shared/lib/base-path'
import { formatNamespaceSearchInput, normalizeSearchQuery, parseNamespaceSearchInput } from '@/shared/lib/search-query'
import { Button } from '@/shared/ui/button'
import { APP_SHELL_PAGE_CLASS_NAME } from '@/app/page-shell-style'

const PAGE_SIZE = 12

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

  const { data, isLoading, isFetching, isPlaceholderData } = useSearchSkills({
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

  const handlePageChange = (newPage: number) => {
    setLoadedPages(null)
    blurActiveElement()
    navigate({ to: '/search', search: { q, namespace, label: selectedLabel, sort, page: newPage, starredOnly, view: viewSearch } })
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
  const resultCount = starredOnly
    ? filteredStarredSkills.length
    : (data?.total ?? 0)

  return (
    <div className={APP_SHELL_PAGE_CLASS_NAME}>
      {/* Page Heading */}
      <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
        <h1 className="text-3xl font-bold tracking-tight" style={{ color: 'hsl(var(--foreground))' }}>
          {t('search.allSkillsTitle')}
        </h1>
        <p className="text-sm text-muted-foreground">
          {resultCount > 0 ? t('search.results', { count: resultCount }) : t('search.allSkillsHint')}
        </p>
      </div>

      {/* Search Bar */}
      <div className="ml-auto max-w-md">
        <SearchBar analyticsId="search.query.submit"
          value={queryInput}
          isSearching={isUpdatingResults}
          onChange={setQueryInput}
          onSearch={handleSearch}
        />
      </div>

      {/* Sort And Filters */}
      <div className="space-y-4">
        <div className="flex items-center justify-between flex-wrap gap-4">
          <div className="flex min-w-0 flex-wrap items-center gap-3">
            <span className="text-sm font-medium text-muted-foreground">{t('search.sort.label')}</span>
            <div className="flex max-w-full flex-wrap gap-2">
              <Button data-analytics-id="search.sort.relevance"
                variant={sort === 'relevance' ? 'default' : 'outline'}
                size="sm"
                onClick={() => handleSortChange('relevance')}
              >
                {t('search.sort.relevance')}
              </Button>
              <Button data-analytics-id="search.sort.downloads"
                variant={sort === 'downloads' ? 'default' : 'outline'}
                size="sm"
                onClick={() => handleSortChange('downloads')}
              >
                {t('search.sort.downloads')}
              </Button>
              <Button data-analytics-id="search.sort.newest"
                variant={sort === 'newest' ? 'default' : 'outline'}
                size="sm"
                onClick={() => handleSortChange('newest')}
              >
                {t('search.sort.newest')}
              </Button>
            </div>
          </div>

          <div
            className="flex items-center gap-1 rounded-lg border border-border p-0.5"
            role="group"
            aria-label={t('search.viewToggleLabel')}
          >
            <Button data-analytics-id="search.view.grid"
              variant={view === 'grid' ? 'default' : 'ghost'}
              size="sm"
              aria-pressed={view === 'grid'}
              aria-label={t('search.viewGrid')}
              title={t('search.viewGrid')}
              onClick={() => handleViewChange('grid')}
            >
              <LayoutGrid className="h-4 w-4" />
            </Button>
            <Button data-analytics-id="search.view.list"
              variant={view === 'list' ? 'default' : 'ghost'}
              size="sm"
              aria-pressed={view === 'list'}
              aria-label={t('search.viewList')}
              title={t('search.viewList')}
              onClick={() => handleViewChange('list')}
            >
              <List className="h-4 w-4" />
            </Button>
          </div>
        </div>

        {isUpdatingResults ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" />
            <span>{t('search.loadingMore')}</span>
          </div>
        ) : null}

        <div className="flex flex-wrap items-center gap-2">
          <span className="shrink-0 text-sm font-medium text-muted-foreground">{t('search.filters.label')}</span>
          <Button data-analytics-id="search.starred.filter"
            variant={starredOnly ? 'default' : 'outline'}
            size="sm"
            onClick={handleStarredToggle}
          >
            {t('search.filterStarred')}
          </Button>
          {!starredOnly && labels?.map((label) => (
            <Button data-analytics-id="search.label.filter"
              key={label.slug}
              variant={selectedLabel === label.slug ? 'default' : 'outline'}
              size="sm"
              onClick={() => handleLabelToggle(label.slug)}
            >
              {label.displayName}
            </Button>
          ))}
          {namespace ? (
            <Button data-analytics-id="search.namespace.clear"
              variant="default"
              size="sm"
              onClick={handleNamespaceClear}
            >
              {t('search.namespaceFilter', { namespace })}
            </Button>
          ) : null}
        </div>
      </div>

      {/* Results */}
      {isPageLoading ? (
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
            <div className="grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4" data-testid="skill-grid-view">
              {displayItems.map((skill, idx) => (
                <div key={skill.id} className={`h-full animate-fade-up delay-${Math.min(idx % 6 + 1, 6)}`}>
                  <SkillCard analyticsId="skill.card.open"
                    skill={skill}
                    highlightStarred
                    onClick={() => handleSkillClick(skill.namespace, skill.slug)}
                  />
                </div>
              ))}
            </div>
          )}
          {totalPages > 1 && (
            <div className="space-y-4">
              {page + 1 < totalPages && (
                <div className="flex justify-center">
                  <Button variant="ghost" onClick={handleLoadMore} disabled={isFetching || isFetchingStarred}>
                    {isUpdatingResults && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
                    {t('search.loadMore')}
                  </Button>
                </div>
              )}
              <Pagination analyticsId="search.results.paginate"
                page={page}
                totalPages={totalPages}
                onPageChange={handlePageChange}
              />
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
  )
}
