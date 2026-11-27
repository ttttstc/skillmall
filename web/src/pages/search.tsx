import { startTransition, useEffect, useRef, useState } from 'react'
import { useNavigate, useSearch } from '@tanstack/react-router'
import { useTranslation } from 'react-i18next'
import { Loader2, LayoutGrid, List, ArrowLeft } from 'lucide-react'
import type { SkillAuthor, SkillSummary } from '@/api/types'
import { useAuth } from '@/features/auth/use-auth'
import { SearchBar } from '@/features/search/search-bar'
import { SkillCard } from '@/features/skill/skill-card'
import { SkillListRow } from '@/features/skill/skill-list-row'
import { CategoryTree } from '@/features/skill/category-tree'
import { SubCategoryFilter } from '@/features/skill/sub-category-filter'
import { CreatorFilter } from '@/features/skill/creator-filter'
import { SkeletonList } from '@/shared/components/skeleton-loader'
import { EmptyState } from '@/shared/components/empty-state'
import { LoadError } from '@/shared/components/load-error'
import { useSearchSkills } from '@/shared/hooks/use-skill-queries'
import { useCategories } from '@/shared/hooks/use-category-queries'
import { useVisibleLabels } from '@/shared/hooks/use-label-queries'
import { useMyStars } from '@/shared/hooks/use-user-queries'
import { toRouterPath } from '@/shared/lib/base-path'
import { formatNamespaceSearchInput, normalizeSearchQuery, parseNamespaceSearchInput } from '@/shared/lib/search-query'
import { domainToCategorySlug, type SkillDomain } from '@/shared/lib/skill-domains'
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
  // The category lives in the URL like every other filter here, so a filtered list can be shared
  // and revisited. Absent means "all categories", not "no category".
  const domain = searchParams.domain as SkillDomain | undefined
  // `category` supersedes the legacy `domain`. An old shared link that only carries the enum keeps
  // working because it is mapped onto the seeded first-level slug here, once, instead of being
  // special-cased in every request builder.
  const category = searchParams.category ?? domainToCategorySlug(domain)
  const sub = searchParams.sub ?? []
  const creator = searchParams.creator
  const sort = searchParams.sort || 'newest'
  const page = searchParams.page ?? 0
  const starredOnly = searchParams.starredOnly ?? false
  const view = searchParams.view === 'list' ? 'list' : 'grid'
  // The UCD header carries a "返回" action. Real history is preferred so a filtered search returns
  // to whatever page the visitor came from; a direct visit falls back to the landing page.
  const handleBack = () => {
    if (typeof window !== 'undefined' && window.history.length > 1) {
      window.history.back()
      return
    }
    navigate({ to: '/' })
  }
  const [queryInput, setQueryInput] = useState(formatNamespaceSearchInput(namespace, q))
  const previousPageRef = useRef(page)
  const loadingMoreRef = useRef(false)
  const [loadedPages, setLoadedPages] = useState<{ key: string; page: number; items: SkillSummary[] } | null>(null)
  const resultsKey = JSON.stringify([q, namespace, selectedLabel, category, sub, creator, sort, starredOnly])

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
    // Only one of the two is sent: the contract says `category` wins, and sending both would make
    // a stale `domain` look like a server-side precedence bug.
    domain: category ? undefined : domain,
    category,
    sub: sub.length > 0 ? sub : undefined,
    creator,
    sort,
    page,
    size: PAGE_SIZE,
    starredOnly,
  }, !starredOnly)
  const { data: categoryTree, isLoading: isLoadingCategories } = useCategories()
  // Second-level options always come from the currently selected first-level category, so the
  // picker can never offer a combination the API would reject.
  const activeSubOptions = categoryTree?.categories.find(node => node.slug === category)?.subCategories ?? []
  const { data: labels } = useVisibleLabels()
  const {
    data: starredSkills,
    isLoading: isLoadingStarred,
    isFetching: isFetchingStarred,
    isError: isErrorStarred,
    refetch: refetchStarred,
  } = useMyStars(starredOnly && isAuthenticated)
  const viewSearch = view === 'list' ? ('list' as const) : undefined

  /**
   * Single merge point for the URL state.
   *
   * Every filter change used to build its own search object, and those copies had already drifted
   * apart. Funnelling them through here means a newly added filter cannot be dropped by one
   * handler that forgot to copy it.
   */
  const go = (
    overrides: Partial<{
      q: string
      namespace: string
      label: string
      domain: SkillDomain | undefined
      category: string | undefined
      sub: string[]
      creator: string | undefined
      sort: string
      page: number
      starredOnly: boolean
      view: 'list' | undefined
    }> = {},
    options: { replace?: boolean } = {},
  ) => {
    navigate({
      to: '/search',
      replace: options.replace,
      search: {
        q,
        namespace,
        label: selectedLabel,
        domain,
        category,
        sub: sub.length > 0 ? sub : undefined,
        creator,
        sort,
        page,
        starredOnly,
        view: viewSearch,
        ...overrides,
      },
    })
  }

  const handleSearch = (query: string) => {
    const parsedInput = parseNamespaceSearchInput(query)
    setQueryInput(query)
    startTransition(() => {
      go({ q: parsedInput.query, namespace: parsedInput.namespace, page: 0 }, { replace: true })
    })
  }

  const handleSortChange = (newSort: string) => go({ sort: newSort, page: 0 })

  const handleViewChange = (nextView: 'grid' | 'list') => {
    if (nextView === view) {
      return
    }
    blurActiveElement()
    go({ view: nextView === 'list' ? 'list' : undefined })
  }

  const handleLabelToggle = (label: string) => {
    go({ label: selectedLabel === label ? '' : label, page: 0 })
  }

  const handleNamespaceClear = () => go({ namespace: '', page: 0 })

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

    go({ starredOnly: !starredOnly, page: 0 })
  }

  /**
   * Selecting a first-level category clears the second-level selection: a second-level slug only
   * means something inside its own parent, so carrying the old ones over would send the server a
   * cross-category combination it rejects with 400.
   */
  const handleCategoryChange = (nextSlug: string | undefined) => {
    go({ category: nextSlug, sub: [], page: 0 })
  }

  const handleToggleSub = (slug: string) => {
    const nextSub = sub.includes(slug) ? sub.filter(item => item !== slug) : [...sub, slug]
    go({ sub: nextSub, page: 0 })
  }

  const handleClearSubs = () => go({ sub: [], page: 0 })

  const handleCreatorSelect = (author: SkillAuthor | null) => {
    go({ creator: author?.userId, page: 0 })
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
    navigate({ to: '/search', search: { q, namespace, label: selectedLabel, domain, category, sub: sub.length > 0 ? sub : undefined, creator, sort, page: page + 1, starredOnly, view: viewSearch } })
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

  return (
    <div className={APP_SHELL_PAGE_CLASS_NAME}>
      {/* Page Heading */}
      <div className="space-y-2">
        <Button data-analytics-id="search.header.back"
          variant="ghost"
          size="sm"
          className="-ml-2 gap-1 px-2"
          onClick={handleBack}
        >
          <ArrowLeft className="h-4 w-4" />
          {t('search.back')}
        </Button>
        <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
          <h1 className="text-3xl font-bold tracking-tight" style={{ color: 'hsl(var(--foreground))' }}>
            {t('search.allSkillsTitle')}
          </h1>
          <p className="text-sm text-muted-foreground">
            {resultCount > 0 ? t('search.resultTotal', { count: resultCount }) : t('search.allSkillsHint')}
          </p>
        </div>
      </div>

      <div className="grid items-start gap-5 lg:grid-cols-[236px_minmax(0,1fr)]">
        <aside className="flex flex-wrap items-center gap-2 lg:flex-col lg:items-start lg:gap-1" aria-label={t('search.filters.label')}>
          <span className="w-full px-3 py-2 text-sm font-medium text-muted-foreground">{t('search.filters.label')}</span>
          <Button data-analytics-id="search.starred.filter"
            className="justify-start lg:w-full"
            variant={starredOnly ? 'default' : 'outline'}
            size="sm"
            onClick={handleStarredToggle}
          >
            {t('search.filterStarred')}
          </Button>
          {!starredOnly && labels?.map((label) => (
            <Button data-analytics-id="search.label.filter"
              className="justify-start lg:w-full"
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
              className="justify-start lg:w-full"
              variant="default"
              size="sm"
              onClick={handleNamespaceClear}
            >
              {t('search.namespaceFilter', { namespace })}
            </Button>
          ) : null}
          <div className="w-full border-t border-border/60 px-1 pt-4 lg:mt-3">
            <p className="px-2 text-sm font-medium text-foreground">{t('search.categoryTreeLabel')}</p>
            <CategoryTree
              categories={categoryTree?.categories ?? []}
              selectedCategory={category}
              selectedSubs={sub}
              onSelectCategory={handleCategoryChange}
              onToggleSub={handleToggleSub}
              isLoading={isLoadingCategories}
              className="mt-2"
            />
          </div>
        </aside>

      <div className="min-w-0 space-y-4">
      {/* Sort And Filters */}
      <div className="space-y-4">
        <div className="flex items-center justify-between flex-wrap gap-4">
          <div className="flex min-w-0 flex-wrap items-center gap-3">
            <div className="flex max-w-full flex-wrap gap-2">
              <Button data-analytics-id="search.sort.all"
                variant={sort === 'relevance' ? 'default' : 'outline'}
                size="sm"
                onClick={() => handleSortChange('relevance')}
              >
                {t('search.sort.all')}
              </Button>
              <Button data-analytics-id="search.sort.popular"
                variant={sort === 'popular' ? 'default' : 'outline'}
                size="sm"
                onClick={() => handleSortChange('popular')}
              >
                {t('search.sort.popular')}
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

          <div className="flex min-w-0 flex-wrap items-center gap-2">
          <SubCategoryFilter
            options={activeSubOptions}
            selected={sub}
            onToggle={handleToggleSub}
            onClear={handleClearSubs}
            enabled={Boolean(category)}
          />
          <CreatorFilter value={creator} onSelect={handleCreatorSelect} />
          <div className="w-64 max-w-full">
            <SearchBar analyticsId="search.query.submit"
              value={queryInput}
              isSearching={isUpdatingResults}
              onChange={setQueryInput}
              onSearch={handleSearch}
            />
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
            <div className="grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4" data-testid="skill-grid-view">
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
              <Button variant="ghost" onClick={handleLoadMore} disabled={isFetching || isFetchingStarred}>
                {isUpdatingResults && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
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
              : (category || domain) ? t('home.domainEmpty') : (q ? t('search.noResultsFor', { q }) : undefined)
          }
        />
      )}
      </div>
      </div>
    </div>
  )
}
