import { useEffect, useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { useTranslation } from 'react-i18next'
import { Boxes, ChevronRight, Plus, Search, X } from 'lucide-react'
import type { MySkillSuiteWorkspaceItem } from '@/api/types'
import { useMySuiteWorkspace } from '@/shared/hooks/use-suite-queries'
import { DashboardPageHeader } from '@/shared/components/dashboard-page-header'
import { suiteStatusLabel } from '@/features/suite/suite-labels'
import { suiteBundleProblemKind } from '@/features/suite/suite-bundle-problem'
import { EmptyState } from '@/shared/components/empty-state'
import { Pagination } from '@/shared/components/pagination'
import { formatLocalDateTime } from '@/shared/lib/date-time'
import { cn } from '@/shared/lib/utils'
import { Button } from '@/shared/ui/button'
import { Input } from '@/shared/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/shared/ui/select'

const PAGE_SIZE = 12
const FILTERS = ['ALL', 'ATTENTION', 'DRAFT', 'PENDING_REVIEW', 'PUBLISHED', 'OTHER'] as const

export function MySuitesPage() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [filters, setFilters] = useState({ query: '', state: 'ALL', page: 0 })
  const [queryInput, setQueryInput] = useState('')
  const workspace = useMySuiteWorkspace(filters.query, filters.state, filters.page, PAGE_SIZE)
  const data = workspace.data
  const stale = workspace.isPlaceholderData
  const update = (next: Partial<typeof filters>) => setFilters(current => ({ ...current, page: 0, ...next }))

  useEffect(() => {
    if (!data || stale) return
    const lastPage = Math.max(0, Math.ceil(data.total / PAGE_SIZE) - 1)
    if (filters.page > lastPage) setFilters(current => ({ ...current, page: lastPage }))
  }, [data, stale, filters.page])

  function viewSuite(item: MySkillSuiteWorkspaceItem, tab?: 'publishing') {
    void navigate({
      to: `/dashboard/suites/${item.namespace}/${encodeURIComponent(item.slug)}`,
      search: tab ? { version: item.suiteVersion, tab } : { version: item.suiteVersion },
    })
  }
  function open(item: MySkillSuiteWorkspaceItem) {
    if (item.suiteId) {
      viewSuite(item, item.operationId ? 'publishing' : undefined)
      return
    }
    if (item.operationId) void navigate({ to: `/dashboard/suites/publishing/${encodeURIComponent(item.operationId)}` })
    else viewSuite(item)
  }

  return (
    <div className="space-y-6 animate-fade-up">
      <DashboardPageHeader title={t('suite.myTitle')} subtitle={t('suite.workspace.description')}
        actions={<Button data-analytics-id="suite.create.open" size="sm" className="h-9 rounded-full px-4 text-xs" onClick={() => navigate({ to: '/dashboard/suites/new' })}>{t('suite.create')}</Button>} />
      <div className="flex flex-wrap items-center gap-2">
        <form data-analytics-id="suite.owned.search" className="flex w-full items-center gap-2 sm:w-auto" onSubmit={event => { event.preventDefault(); update({ query: queryInput.trim() }) }}>
        <div className="relative min-w-0 flex-1 sm:w-80">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
          <Input className="h-9 pl-9 pr-8 text-sm" value={queryInput} maxLength={200}
            aria-label={t('suite.workspace.searchLabel')} placeholder={t('suite.workspace.searchPlaceholder')}
            onChange={event => setQueryInput(event.target.value)} />
          {queryInput && <button data-analytics-id="suite.query.clear" type="button" aria-label={t('suite.workspace.clearSearch')}
            className="absolute right-1.5 top-1/2 flex h-6 w-6 -translate-y-1/2 items-center justify-center rounded-sm text-muted-foreground hover:bg-secondary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            onClick={() => setQueryInput('')}><X className="h-3.5 w-3.5" aria-hidden="true" /></button>}
        </div>
        </form>
        <Select value={filters.state} onValueChange={state => update({ state })}>
          <SelectTrigger data-analytics-id="suite.owned.interact" className="h-9 w-36 text-sm" aria-label={t('suite.workspace.stateFilter')}><SelectValue /></SelectTrigger>
          <SelectContent>{FILTERS.map(state => <SelectItem data-analytics-id="suite.owned.filter" key={state} value={state}>{t(`suite.workspace.filter.${state}`)}</SelectItem>)}</SelectContent>
        </Select>
        {data && <button data-analytics-id="suite.attention.filter" type="button" aria-pressed={filters.state === 'ATTENTION'}
          onClick={() => update({ state: filters.state === 'ATTENTION' ? 'ALL' : 'ATTENTION' })}
          className={cn('inline-flex h-9 items-center gap-1.5 rounded-full px-3 text-xs focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
            data.attentionCount ? 'bg-amber-500/10 text-amber-800 dark:text-amber-300' : 'text-muted-foreground', filters.state === 'ATTENTION' && 'ring-1 ring-amber-500/30')}>
          {t('suite.workspace.attention')}{' '}<span className="font-medium tabular-nums">{data.attentionCount}</span>
        </button>}
      </div>
      {workspace.isLoading ? <div className="h-64 animate-shimmer rounded-lg" />
        : workspace.isError ? <div role="alert" className="rounded-lg border p-4 text-xs text-destructive">
          {t('suite.workspace.error')}<Button data-analytics-id="suite.owned.refresh" variant="ghost" size="sm" onClick={() => workspace.refetch()}>{t('suite.workspace.reload')}</Button>
        </div> : data?.items.length ? <>
          <div className="overflow-hidden rounded-xl border border-border/70 bg-card" aria-busy={stale || workspace.isFetching}>
            <div className="hidden border-b border-border/60 bg-secondary/60 lg:grid lg:grid-cols-[3.25rem_minmax(0,1.6fr)_5.5rem_8rem_9rem_6rem] lg:items-center lg:gap-x-2 lg:divide-x lg:divide-border/50">
              <span className="px-2 py-2.5 text-center text-[13px] font-semibold text-foreground/80">#</span>
              {['suite', 'version', 'state', 'updated'].map(column => (
                <span key={column} className="px-2 py-2.5 text-[13px] font-semibold text-foreground/80">{t(`suite.workspace.columns.${column}`)}</span>
              ))}
              <span className="px-2 py-2.5 text-right text-[13px] font-semibold text-foreground/80">{t('suite.workspace.columns.action')}</span>
            </div>
            <div className={cn('divide-y divide-border/50', stale && 'opacity-60')}>
              {data.items.map((item, index) => {
                const problem = !item.suiteId && item.operationStatus === 'SUITE_DRAFT_CREATED'
                  ? 'draftMissing'
                  : suiteBundleProblemKind(item.operationStatus, item.failureCode)
                return <div key={`${item.namespace}/${item.slug}`}
                  className={cn('flex flex-col gap-2 px-4 py-3 transition-colors duration-150 hover:bg-secondary/40 lg:grid lg:grid-cols-[3.25rem_minmax(0,1.6fr)_5.5rem_8rem_9rem_6rem] lg:items-center lg:gap-x-2 lg:py-3', !item.suiteId && 'bg-blue-500/[0.025]')}>
                  <span className="hidden text-[13px] tabular-nums text-muted-foreground lg:block lg:px-2">{filters.page * PAGE_SIZE + index + 1}</span>
                  <div className="min-w-0 lg:px-2">
                    <button data-analytics-id={item.suiteId ? 'suite.management.open' : 'suite.operation.open'} type="button" disabled={stale} onClick={() => item.suiteId ? viewSuite(item) : open(item)}
                      className="flex max-w-full items-center gap-2 text-left text-sm font-medium transition-colors hover:text-blue-600 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring dark:hover:text-blue-400">
                      <Boxes className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" /><span className="truncate">{item.displayName}</span>
                    </button>
                    <p className="mt-0.5 truncate pl-5 font-mono text-[11px] text-muted-foreground">@{item.namespace}/{item.slug}</p>
                    {item.summary && <p className="mt-0.5 truncate pl-5 text-[11px] text-muted-foreground">{item.summary}</p>}
                  </div>
                  <span className="pl-5 font-mono text-[11px] text-foreground/85 sm:pl-0 lg:px-2 lg:text-[13px]">v{item.version}</span>
                  <div className="min-w-0 pl-5 sm:pl-0 lg:px-2">
                    <span className={cn('inline-flex rounded-md px-2.5 py-[3px] text-xs font-medium',
                      item.state === 'ATTENTION' || item.state === 'REJECTED' ? 'bg-[#FFF3E0] text-[#C2410C] dark:bg-amber-500/15 dark:text-amber-300'
                        : item.state === 'PUBLISHED' ? 'bg-[#E8F7EC] text-[#1B8A45] dark:bg-emerald-500/15 dark:text-emerald-300'
                          : item.state === 'PREPARING' || item.state === 'PENDING_REVIEW' ? 'bg-[#EEF2FF] text-[#3B5BDB] dark:bg-blue-500/15 dark:text-blue-300' : 'bg-[#F1F2F4] text-[#5B6472] dark:bg-secondary dark:text-muted-foreground')}>
                      {item.operationStatus ? t(`suite.bundle.statusLabel.${item.operationStatus}`) : suiteStatusLabel(t, item.state)}
                    </span>
                    {problem && <p className="mt-1 truncate text-[11px] text-foreground/75">{t(`suite.bundle.problem.${problem}.title`)}</p>}
                    {item.operationStatus && <p className="mt-0.5 truncate text-[11px] text-muted-foreground">{t(`suite.bundle.taskHint.${item.operationStatus}`)}</p>}
                  </div>
                  <span className="pl-5 text-[11px] text-muted-foreground sm:pl-0 lg:px-2 lg:text-[13px]">{formatLocalDateTime(item.updatedAt, i18n.language)}</span>
                  <Button data-analytics-id={item.suiteId ? 'suite.management.open' : 'suite.operation.open'} variant="ghost" size="sm" disabled={stale} onClick={() => open(item)} className="h-8 self-end px-2 text-xs lg:justify-self-end">
                    {item.operationStatus && !item.suiteId ? t(`suite.bundle.taskAction.${item.operationStatus}`) : t('suite.view')}
                    <ChevronRight className="ml-1 h-3 w-3" aria-hidden="true" />
                  </Button>
                </div>
              })}
            </div>
          </div>
          <div className="flex flex-wrap items-center justify-end gap-4">
            <span className="text-[13px] text-muted-foreground">{t('suite.workspace.total', { count: data.total, size: PAGE_SIZE })}</span>
            <Pagination analyticsId="suite.owned.paginate" page={filters.page} totalPages={Math.max(1, Math.ceil(data.total / PAGE_SIZE))} onPageChange={page => update({ page })} />
          </div>
        </> : <EmptyState
          title={t(filters.query || filters.state !== 'ALL' ? 'suite.workspace.noMatches' : 'suite.myEmpty')}
          action={filters.query || filters.state !== 'ALL' ? undefined : (
            <Button size="sm" className="h-9 rounded-full bg-foreground px-4 text-xs font-medium text-background shadow-none hover:bg-foreground/90 active:scale-100"
              onClick={() => navigate({ to: '/dashboard/suites/new' })}>
              <Plus className="h-3.5 w-3.5" aria-hidden="true" />
              {t('suite.create')}
            </Button>
          )}
        />}
    </div>
  )
}
