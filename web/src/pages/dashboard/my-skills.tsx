import { useCallback, useEffect, useState } from 'react'
import { useLocation, useNavigate, useSearch } from '@tanstack/react-router'
import { useTranslation } from 'react-i18next'
import { Search, Send } from 'lucide-react'
import { useAuth } from '@/features/auth/use-auth'
import { Button } from '@/shared/ui/button'
import { Card } from '@/shared/ui/card'
import { Input } from '@/shared/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/shared/ui/select'
import { EmptyState } from '@/shared/components/empty-state'
import { ConfirmDialog } from '@/shared/components/confirm-dialog'
import { DashboardPageHeader } from '@/shared/components/dashboard-page-header'
import { Pagination } from '@/shared/components/pagination'
import { useArchiveSkill, useUnarchiveSkill, useWithdrawSkillReview } from '@/shared/hooks/use-skill-queries'
import { useMyNamespaces } from '@/shared/hooks/use-namespace-queries'
import { useMySkills, useSubmitPromotion } from '@/shared/hooks/use-user-queries'
import { useRestoreHiddenSkill } from '@/features/admin/use-admin-skills'
import { getHeadlineVersion, getPublishedVersion, getOwnerPreviewVersion, hasPendingOwnerPreview } from '@/shared/lib/skill-lifecycle'
import { formatCompactCount } from '@/shared/lib/number-format'
import { formatLocalDateTime } from '@/shared/lib/date-time'
import { toast } from '@/shared/lib/toast'
import { buildReturnTo } from '@/shared/lib/auth-route'
import { SkillVisibilityDialog } from '@/features/skill/skill-visibility-dialog'
import { SkillAvatar } from '@/features/skill/skill-avatar'
import { SkillAvatarManager } from '@/features/skill/skill-avatar-manager'
import { ApiError } from '@/api/client'
import { getMySkillEmptyStateKey, getMySkillFilters, type MySkillFilter } from './my-skill-filters'

const PAGE_SIZE = 10
const ALL_NAMESPACES_VALUE = '__all_namespaces__'

/**
 * Dashboard page for skills owned by the current user.
 *
 * It combines lifecycle display, archive and unarchive actions, review withdrawal, and promotion
 * submission into one management surface.
 */
function getPromotionConflictKey(error: ApiError): 'promotion.duplicate_pending' | 'promotion.already_promoted' | null {
  if (error.serverMessageKey === 'promotion.duplicate_pending') {
    return 'promotion.duplicate_pending'
  }
  if (error.serverMessageKey === 'promotion.already_promoted') {
    return 'promotion.already_promoted'
  }
  return null
}

export function MySkillsPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const search = useSearch({ from: '/dashboard/skills' })
  const { t, i18n } = useTranslation()
  const { hasRole } = useAuth()

  // The URL is the source of truth for page / filter / namespace / keyword so the
  // search context survives navigating into a skill and back via the returnTo link.
  const page = search.page ?? 0
  const filter = (search.filter as MySkillFilter) ?? 'ALL'
  const namespaceFilter = search.namespace ?? ''
  const keyword = search.q ?? ''

  // Input remains local until the user explicitly submits the search.
  const [keywordInput, setKeywordInput] = useState(keyword)

  const [archiveTarget, setArchiveTarget] = useState<{ namespace: string; slug: string; name: string } | null>(null)
  const [unarchiveTarget, setUnarchiveTarget] = useState<{ namespace: string; slug: string; name: string } | null>(null)
  const [withdrawTarget, setWithdrawTarget] = useState<{ namespace: string; slug: string; name: string; version: string } | null>(null)
  const [promotionTarget, setPromotionTarget] = useState<{ skillId: number; versionId: number; name: string; version: string } | null>(null)
  const [restoreHiddenTarget, setRestoreHiddenTarget] = useState<{ skillId: number; name: string } | null>(null)

  const updateSearch = useCallback((next: Partial<typeof search>, options?: { replace?: boolean }) => {
    navigate({
      to: '/dashboard/skills',
      search: (prev) => ({ ...prev, ...next }),
      replace: options?.replace,
    })
  }, [navigate])

  // Sync keywordInput when navigating back via returnTo
  useEffect(() => {
    setKeywordInput(keyword)
  }, [keyword])

  const { data: skillPage, isLoading } = useMySkills({
    page,
    size: PAGE_SIZE,
    filter: filter === 'ALL' ? undefined : filter,
    q: keyword || undefined,
    namespace: namespaceFilter || undefined,
  })
  const { data: namespaceOptions } = useMyNamespaces()

  const skills = skillPage?.items ?? []
  const totalPages = skillPage ? Math.max(Math.ceil(skillPage.total / skillPage.size), 1) : 1
  const availableFilters = getMySkillFilters(hasRole('SUPER_ADMIN'))
  const canRestoreHidden = filter === 'HIDDEN' && hasRole('SUPER_ADMIN')
  const hasActiveSearch = keyword.trim() !== '' || namespaceFilter !== ''
  const emptyStateKey = getMySkillEmptyStateKey(filter)
  // UCD: a genuinely empty collection shows only the illustration + CTA, no search toolbar.
  // Keep the toolbar whenever there is something to search, a query to clear, or a filter the
  // user must be able to switch back from — otherwise an empty filtered view traps them.
  const showToolbar = (skillPage?.total ?? 0) > 0 || hasActiveSearch || filter !== 'ALL'
  const archiveMutation = useArchiveSkill()
  const unarchiveMutation = useUnarchiveSkill()
  const withdrawMutation = useWithdrawSkillReview()
  const submitPromotionMutation = useSubmitPromotion()
  const restoreHiddenMutation = useRestoreHiddenSkill()

  const handleSkillClick = (namespace: string, slug: string, version?: string) => {
    navigate({
      to: `/space/${namespace}/${encodeURIComponent(slug)}`,
      search: { returnTo: buildReturnTo(location), version },
    })
  }

  const handleClearSearch = () => {
    setKeywordInput('')
    updateSearch({ q: undefined, namespace: undefined, page: 0 })
  }

  const handleUpdateSkill = (namespace: string, slug: string, name: string, visibility?: string) => {
    navigate({
      to: '/dashboard/publish',
      search: { namespace, updateSkill: slug, updateName: name, visibility: visibility || 'PUBLIC' },
    })
  }

  const resolveStatusLabel = (status?: string) => {
    if (status === 'HIDDEN') {
      return t('mySkills.statusHidden')
    }
    if (status === 'ARCHIVED') {
      return t('mySkills.statusArchived')
    }
    if (status === 'PENDING_REVIEW') {
      return t('mySkills.statusPendingReview')
    }
    if (status === 'PUBLISHED') {
      return t('mySkills.statusPublished')
    }
    if (status === 'REJECTED') {
      return t('mySkills.statusRejected')
    }
    if (status === 'SCANNING') {
      return t('mySkills.statusScanning')
    }
    if (status === 'SCAN_FAILED') {
      return t('mySkills.statusScanFailed')
    }
    if (status === 'UPLOADED') {
      return t('skillDetail.versionStatusUploaded')
    }
    return status
  }

  const resolveStatusClassName = (status?: string) => {
    if (status === 'HIDDEN') {
      return 'status-pill status-pill--archived'
    }
    if (status === 'ARCHIVED') {
      return 'status-pill status-pill--archived'
    }
    if (status === 'PENDING_REVIEW') {
      return 'status-pill status-pill--review'
    }
    if (status === 'PUBLISHED') {
      return 'status-pill status-pill--published'
    }
    if (status === 'REJECTED') {
      return 'status-pill status-pill--rejected'
    }
    if (status === 'SCANNING') {
      return 'status-pill status-pill--review'
    }
    if (status === 'SCAN_FAILED') {
      return 'status-pill status-pill--rejected'
    }
    if (status === 'UPLOADED') {
      return 'status-pill status-pill--review'
    }
    return 'status-pill'
  }

  /**
   * UCD renders the status as a soft tint pill (light fill + saturated text) instead of the
   * legacy solid pill defined in index.css. The shared `.status-pill` rule is emitted after the
   * Tailwind utilities layer, so the tint is applied with the `!` important modifier to win the
   * cascade without touching the shared stylesheet.
   */
  const resolveStatusToneClassName = (status?: string) => {
    if (status === 'PUBLISHED') {
      return '!bg-[#E8F7EC] !text-[#1B8A45]'
    }
    if (status === 'PENDING_REVIEW' || status === 'SCANNING' || status === 'UPLOADED') {
      return '!bg-[#FFF3E0] !text-[#C2410C]'
    }
    if (status === 'REJECTED' || status === 'SCAN_FAILED') {
      return '!bg-[#FDECEC] !text-[#D13B3B]'
    }
    return '!bg-[#F1F2F4] !text-[#5B6472]'
  }

  const handleArchiveSkill = async () => {
    if (!archiveTarget) {
      return
    }
    try {
      await archiveMutation.mutateAsync({
        namespace: archiveTarget.namespace,
        slug: archiveTarget.slug,
      })
      toast.success(
        t('mySkills.archiveSuccessTitle'),
        t('mySkills.archiveSuccessDescription', { skill: archiveTarget.name }),
      )
      setArchiveTarget(null)
    } catch (error) {
      toast.error(t('mySkills.archiveErrorTitle'), error instanceof Error ? error.message : '')
      throw error
    }
  }

  const handleUnarchiveSkill = async () => {
    if (!unarchiveTarget) {
      return
    }
    try {
      await unarchiveMutation.mutateAsync({
        namespace: unarchiveTarget.namespace,
        slug: unarchiveTarget.slug,
      })
      toast.success(
        t('mySkills.unarchiveSuccessTitle'),
        t('mySkills.unarchiveSuccessDescription', { skill: unarchiveTarget.name }),
      )
      setUnarchiveTarget(null)
    } catch (error) {
      toast.error(t('mySkills.unarchiveErrorTitle'), error instanceof Error ? error.message : '')
      throw error
    }
  }

  const handleWithdrawSkill = async () => {
    if (!withdrawTarget) {
      return
    }
    try {
      await withdrawMutation.mutateAsync({
        namespace: withdrawTarget.namespace,
        slug: withdrawTarget.slug,
        version: withdrawTarget.version,
      })
      toast.success(
        t('mySkills.withdrawSuccessTitle'),
        t('mySkills.withdrawSuccessDescription', { skill: withdrawTarget.name }),
      )
      setWithdrawTarget(null)
    } catch (error) {
      toast.error(t('mySkills.withdrawErrorTitle'), error instanceof Error ? error.message : '')
      throw error
    }
  }

  const handleSubmitPromotion = async () => {
    if (!promotionTarget) {
      return
    }
    try {
      await submitPromotionMutation.mutateAsync({
        sourceSkillId: promotionTarget.skillId,
        sourceVersionId: promotionTarget.versionId,
      })
      toast.success(
        t('mySkills.promotionSuccessTitle'),
        t('mySkills.promotionSuccessDescription', { skill: promotionTarget.name, version: promotionTarget.version }),
      )
      setPromotionTarget(null)
    } catch (error) {
      if (error instanceof ApiError) {
        const conflictKey = getPromotionConflictKey(error)
        if (conflictKey === 'promotion.duplicate_pending') {
          toast.error(t('mySkills.promotionDuplicateTitle'), t('mySkills.promotionDuplicateDescription'))
          return
        }
        if (conflictKey === 'promotion.already_promoted') {
          toast.error(t('mySkills.promotionAlreadyPromotedTitle'), t('mySkills.promotionAlreadyPromotedDescription'))
          return
        }
      }
      toast.error(t('mySkills.promotionErrorTitle'), error instanceof Error ? error.message : '')
      throw error
    }
  }

  const handleRestoreHiddenSkill = async () => {
    if (!restoreHiddenTarget) {
      return
    }
    try {
      await restoreHiddenMutation.mutateAsync(restoreHiddenTarget.skillId)
      toast.success(
        t('mySkills.restoreHiddenSuccessTitle'),
        t('mySkills.restoreHiddenSuccessDescription', { skill: restoreHiddenTarget.name }),
      )
      setRestoreHiddenTarget(null)
    } catch (error) {
      toast.error(t('mySkills.restoreHiddenErrorTitle'), error instanceof Error ? error.message : '')
      throw error
    }
  }

  if (isLoading) {
    return (
      <div className="space-y-4 animate-fade-up">
        {Array.from({ length: 3 }).map((_, i) => (
          <div key={i} className="h-24 animate-shimmer rounded-xl" />
        ))}
      </div>
    )
  }

  return (
    <div className="space-y-6 animate-fade-up">
      <DashboardPageHeader
        title={t('mySkills.title')}
        subtitle={t('mySkills.subtitle')}
      />

      {showToolbar ? (
      <div className="flex flex-col gap-3">
        <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
          <form data-analytics-id="skill.owned.search" className="relative w-full sm:w-80" onSubmit={event => {
            event.preventDefault()
            updateSearch({ q: keywordInput.trim() || undefined, page: 0 }, { replace: true })
          }}>
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
          <Input
            type="search"
            value={keywordInput}
            onChange={(event) => setKeywordInput(event.target.value)}
            placeholder={t('mySkills.searchPlaceholder')}
            aria-label={t('mySkills.searchPlaceholder')}
            className="h-9 pl-9 text-sm"
          />
          </form>
          {filter === 'HIDDEN' ? null : (
            <Select
              value={namespaceFilter || ALL_NAMESPACES_VALUE}
              onValueChange={(value) => {
                updateSearch({ namespace: value === ALL_NAMESPACES_VALUE ? undefined : value, page: 0 })
              }}
            >
              <SelectTrigger data-analytics-id="skill.owned.namespace.interact" aria-label={t('mySkills.namespaceFilterLabel')} className="h-9 w-full text-sm sm:w-48">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem data-analytics-id="skill.owned.namespace" value={ALL_NAMESPACES_VALUE}>{t('mySkills.namespaceFilterAll')}</SelectItem>
                {(namespaceOptions ?? []).map((ns: { id: number; slug: string }) => (
                  <SelectItem data-analytics-id="skill.owned.namespace" key={ns.id} value={ns.slug}>
                    @{ns.slug}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          )}
          {hasActiveSearch ? (
            <Button data-analytics-id="skill.owned.clear"
              type="button"
              size="sm"
              variant="ghost"
              className="h-9 text-xs text-muted-foreground hover:text-foreground"
              onClick={handleClearSearch}
            >
              {t('mySkills.clearSearch')}
            </Button>
          ) : null}
        </div>

        <div className="flex flex-wrap gap-2">
          {availableFilters.map((option) => (
            <Button data-analytics-id="skill.owned.filter"
              key={option}
              type="button"
              size="sm"
              variant={filter === option ? 'default' : 'outline'}
              className="h-7 rounded-full px-3 text-xs"
              onClick={() => {
                updateSearch({ filter: option === 'ALL' ? undefined : option, page: 0 })
              }}
            >
              {t(`mySkills.filters.${option}`)}
            </Button>
          ))}
        </div>
      </div>
      ) : null}

      {skillPage && skillPage.total > 0 ? (
        <>
          <div className="overflow-hidden rounded-xl border border-border/70 bg-card">
            <div className="hidden border-b border-border/60 bg-secondary/60 xl:grid xl:grid-cols-[3.25rem_minmax(0,22rem)_6rem_7.5rem_11rem_11rem_minmax(0,1fr)] xl:items-center xl:gap-x-2 xl:divide-x xl:divide-border/50">
              <span className="px-2 py-2.5 text-center text-[13px] font-semibold text-foreground/80">#</span>
              <span className="px-2 py-2.5 text-[13px] font-semibold text-foreground/80">{t('mySkills.table.name')}</span>
              <span className="px-2 py-2.5 text-[13px] font-semibold text-foreground/80">{t('mySkills.table.version')}</span>
              <span className="px-2 py-2.5 text-[13px] font-semibold text-foreground/80">{t('mySkills.table.status')}</span>
              <span className="px-2 py-2.5 text-[13px] font-semibold text-foreground/80">{t('suite.createdBy')}</span>
              <span className="px-2 py-2.5 text-[13px] font-semibold text-foreground/80">{t('mySkills.table.updated')}</span>
              <span className="px-2 py-2.5 text-right text-[13px] font-semibold text-foreground/80">{t('mySkills.table.actions')}</span>
            </div>
            {skills.map((skill, idx) => (
              (() => {
                const ownerReadOnly = skill.managementState?.ownerReadOnly === true
                const relatedNamespace = skill.managementState?.relatedNamespace
                const relatedSlug = skill.managementState?.relatedSlug
                const headlineVersion = getHeadlineVersion(skill)
                const publishedVersion = getPublishedVersion(skill)
                const ownerPreviewVersion = getOwnerPreviewVersion(skill)
                const hasPendingPreview = hasPendingOwnerPreview(skill)
                const preview = ownerPreviewVersion && ownerPreviewVersion.id !== publishedVersion?.id && ownerPreviewVersion.status !== 'YANKED'
                  ? ownerPreviewVersion : null
                const displayName = preview?.displayName ?? skill.displayName
                const summary = preview?.summary ?? skill.summary
                const mainStatus = filter === 'HIDDEN' ? 'HIDDEN' : skill.status === 'ARCHIVED' || skill.status === 'HIDDEN'
                  ? skill.status : preview?.status ?? headlineVersion?.status ?? skill.status


                return (
                  <Card data-analytics-id={filter === "HIDDEN" ? undefined : "skill.owned.open"}
                    key={skill.id}
                    className={`group flex flex-col gap-3 rounded-none border-0 border-b border-border/60 px-4 py-3 shadow-none transition-colors duration-150 last:border-b-0 xl:grid xl:grid-cols-[3.25rem_minmax(0,22rem)_6rem_7.5rem_11rem_11rem_minmax(0,1fr)] xl:items-center xl:gap-x-2 xl:py-3 hover:bg-secondary/40 hover:shadow-none animate-fade-up delay-${Math.min(idx + 1, 6)} ${filter === 'HIDDEN' ? '' : 'cursor-pointer'}`}
                    onClick={filter === 'HIDDEN' ? undefined : () => handleSkillClick(skill.namespace, skill.slug, preview?.version)}
                  >
                    <span className="hidden text-[13px] tabular-nums text-muted-foreground xl:block xl:px-2">{page * PAGE_SIZE + idx + 1}</span>
                    <div className="min-w-0 xl:px-2">
                      <div className="flex items-center gap-2.5">
                        <span className="xl:hidden"><SkillAvatar name={displayName} url={skill.avatarUrl} size="h-8 w-8" /></span>
                        <h3 className="min-w-0 break-words font-heading text-sm font-medium text-blue-600 transition-colors group-hover:text-blue-700 dark:text-blue-400 xl:truncate xl:text-[15px]">
                          {displayName}
                        </h3>
                      </div>
                      {ownerReadOnly && <p role="status" className="mt-1 text-xs text-muted-foreground">{t('mySkills.ownerReadOnly')}</p>}
                      {skill.managementState?.promotionRole && (
                        <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                          <span>{t(`mySkills.promotionRoles.${skill.managementState.promotionRole}`)}</span>
                          {relatedNamespace && relatedSlug && (
                            <button data-analytics-id="skill.related.open" type="button" className="text-primary underline underline-offset-4"
                              onClick={event => {
                                event.stopPropagation()
                                handleSkillClick(relatedNamespace, relatedSlug)
                              }}>
                              {t(skill.managementState.promotionRole === 'TEAM_SOURCE' ? 'mySkills.viewGlobalCopy' : 'mySkills.viewTeamSource')}
                            </button>
                          )}
                        </div>
                      )}
                      {skill.status === 'ACTIVE' && filter !== 'HIDDEN' && <SkillAvatarManager namespace={skill.namespace} slug={skill.slug} previewOnly />}
                      {summary && (
                        <p className="mt-1 line-clamp-1 text-xs leading-relaxed text-muted-foreground xl:sr-only" title={summary}>{summary}</p>
                      )}
                      <div className="mt-1.5 flex flex-wrap items-center gap-2 text-xs text-muted-foreground xl:hidden">
                        <span className="handle-tag max-w-full break-all">@{skill.namespace}</span>
                        <span className="flex items-center gap-1">
                          <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M9 19l3 3m0 0l3-3m-3 3V10" />
                          </svg>
                          {formatCompactCount(skill.downloadCount)}
                        </span>
                      </div>
                      {skill.managementState?.requestedVisibility && (
                        <p role="status" className="mt-1.5 text-xs text-muted-foreground">
                          {t('skillVisibility.pendingList', { visibility: t(`skillVisibility.${skill.managementState.requestedVisibility}`) })}
                        </p>
                      )}
                    </div>
                    <div className="grid min-w-0 grid-cols-2 items-center gap-x-4 gap-y-1.5 text-xs sm:grid-cols-4 xl:contents">
                      <div className="flex min-w-0 items-center gap-1.5 xl:block xl:px-2">
                        <span className="shrink-0 text-[11px] text-muted-foreground xl:hidden">{t('mySkills.table.version')}</span>
                        <span className="block truncate text-[13px] text-foreground/85">
                          {preview && publishedVersion ? (
                            t('mySkills.versionDifference', { version: preview.version, published: publishedVersion.version })
                          ) : headlineVersion ? `v${headlineVersion.version}` : '—'}
                        </span>
                      </div>
                      <div className="flex min-w-0 flex-col gap-1 xl:block xl:px-2">
                        <span className="text-[11px] text-muted-foreground xl:hidden">{t('mySkills.table.status')}</span>
                        <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                          {mainStatus && <span className={`${resolveStatusClassName(mainStatus)} !ml-0 !rounded-md !px-2.5 !py-[3px] !text-xs !font-medium ${resolveStatusToneClassName(mainStatus)}`}>
                            {hasPendingPreview && publishedVersion && mainStatus === 'PENDING_REVIEW'
                              ? t('mySkills.statusUpdatePendingReview') : resolveStatusLabel(mainStatus)}
                          </span>}
                          <p className="text-[11px] text-muted-foreground xl:sr-only">{t('skillVisibility.current', { visibility: t(`skillVisibility.${skill.visibility ?? 'PUBLIC'}`) })}</p>
                        </div>
                      </div>
                      <div className="flex min-w-0 items-center gap-1.5 xl:block xl:px-2">
                        <span className="shrink-0 text-[11px] text-muted-foreground xl:hidden">{t('suite.createdBy')}</span>
                        <span className="block truncate text-[13px] text-foreground/85" title={skill.ownerDisplayName ?? undefined}>
                          {skill.ownerDisplayName ?? '—'}
                        </span>
                      </div>
                      <div className="flex min-w-0 items-center gap-1.5 xl:block xl:px-2">
                        <span className="shrink-0 text-[11px] text-muted-foreground xl:hidden">{t('mySkills.table.updated')}</span>
                        <span className="block truncate text-[13px] text-muted-foreground">{formatLocalDateTime(skill.updatedAt, i18n.language)}</span>
                      </div>
                    </div>
                      <div className="flex flex-wrap items-center gap-2 xl:justify-end xl:px-2">
                        {canRestoreHidden ? (
                          <Button data-analytics-id="skill.restore.request"
                            size="sm"
                            variant="outline"
                            onClick={(event) => {
                              event.stopPropagation()
                              setRestoreHiddenTarget({ skillId: skill.id, name: skill.displayName })
                            }}
                          >
                            {t('mySkills.restoreHidden')}
                          </Button>
                        ) : filter === 'HIDDEN' || ownerReadOnly ? null : skill.status !== 'ARCHIVED' && (
                          <Button data-analytics-id="skill.update.open"
                            size="sm"
                            variant="outline"
                            onClick={(event) => {
                              event.stopPropagation()
                              handleUpdateSkill(skill.namespace, skill.slug, displayName, skill.visibility ?? 'PUBLIC')
                            }}
                          >
                            {t('mySkills.update')}
                          </Button>
                        )}
                        {!ownerReadOnly && filter !== 'HIDDEN' && skill.status === 'ACTIVE' && publishedVersion && (
                          <SkillVisibilityDialog namespace={skill.namespace} slug={skill.slug}
                            visibility={skill.visibility ?? 'PUBLIC'} disabled={hasPendingPreview} />
                        )}
                        {filter === 'HIDDEN' || ownerReadOnly ? null : hasPendingPreview && ownerPreviewVersion ? (
                          <Button data-analytics-id="skill.review.withdraw"
                            size="sm"
                            variant="outline"
                            onClick={(event) => {
                              event.stopPropagation()
                              setWithdrawTarget({
                                namespace: skill.namespace,
                                slug: skill.slug,
                                name: skill.displayName,
                                version: ownerPreviewVersion.version,
                              })
                            }}
                          >
                            {t('mySkills.withdrawReview')}
                          </Button>
                        ) : skill.canSubmitPromotion && publishedVersion ? (
                          <Button data-analytics-id={skill.managementState?.promotionRole === 'TEAM_SOURCE' ? 'skill.promotion.update.request' : 'skill.promotion.create.request'}
                            size="sm"
                            variant="outline"
                            onClick={(event) => {
                              event.stopPropagation()
                              setPromotionTarget({
                                skillId: skill.id,
                                versionId: publishedVersion.id,
                                name: skill.displayName,
                                version: publishedVersion.version,
                              })
                            }}
                          >
                            {t(skill.managementState?.promotionRole === 'TEAM_SOURCE' ? 'mySkills.updateGlobalVersion' : 'mySkills.promoteToGlobal')}
                          </Button>
                        ) : skill.status === 'ARCHIVED' ? (
                          <Button data-analytics-id="skill.unarchive.request"
                            size="sm"
                            variant="outline"
                            onClick={(event) => {
                              event.stopPropagation()
                              setUnarchiveTarget({
                                namespace: skill.namespace,
                                slug: skill.slug,
                                name: skill.displayName,
                              })
                            }}
                          >
                            {t('mySkills.unarchive')}
                          </Button>
                        ) : publishedVersion ? (
                          <Button data-analytics-id="skill.archive.request"
                            size="sm"
                            variant="outline"
                            onClick={(event) => {
                              event.stopPropagation()
                              setArchiveTarget({
                                namespace: skill.namespace,
                                slug: skill.slug,
                                name: skill.displayName,
                              })
                            }}
                          >
                            {t('mySkills.archive')}
                          </Button>
                        ) : null}
                      </div>
                  </Card>
                )
              })()
            ))}
          </div>

          {skillPage.total > PAGE_SIZE ? (
            <Pagination analyticsId="skill.owned.paginate" page={page} totalPages={totalPages} onPageChange={(next) => updateSearch({ page: next })} />
          ) : null}
        </>
      ) : (
        <EmptyState
          title={hasActiveSearch ? t('mySkills.emptySearchTitle') : t(emptyStateKey.title)}
          description={hasActiveSearch ? t('mySkills.emptySearchDescription') : t(emptyStateKey.description)}
          action={
            hasActiveSearch ? (
              <Button data-analytics-id="skill.owned.clear" size="lg" variant="outline" onClick={handleClearSearch}>
                {t('mySkills.clearSearch')}
              </Button>
            ) : (
              <Button size="lg"
                className="h-9 rounded-full bg-foreground px-4 text-xs font-medium text-background shadow-none hover:bg-foreground/90 active:scale-100"
                onClick={() => navigate({ to: '/dashboard/publish', search: {} })}
              >
                <Send className="h-3.5 w-3.5" aria-hidden="true" />
                {t('mySkills.publishSkill')}
              </Button>
            )
          }
        />
      )}

      <ConfirmDialog confirmAnalyticsId="skill.restore.confirm" cancelAnalyticsId="skill.restore.cancel"
        open={!!restoreHiddenTarget}
        onOpenChange={(open) => {
          if (!open) {
            setRestoreHiddenTarget(null)
          }
        }}
        title={t('mySkills.restoreHiddenConfirmTitle')}
        description={restoreHiddenTarget ? t('mySkills.restoreHiddenConfirmDescription', { skill: restoreHiddenTarget.name }) : ''}
        confirmText={t('mySkills.restoreHidden')}
        onConfirm={handleRestoreHiddenSkill}
      />

      <ConfirmDialog
        confirmAnalyticsId={skillPage?.items.find((skill) => skill.id === promotionTarget?.skillId)?.managementState?.promotionRole === 'TEAM_SOURCE' ? 'skill.promotion.update.confirm' : 'skill.promotion.create.confirm'}
        cancelAnalyticsId={skillPage?.items.find((skill) => skill.id === promotionTarget?.skillId)?.managementState?.promotionRole === 'TEAM_SOURCE' ? 'skill.promotion.update.cancel' : 'skill.promotion.create.cancel'}
        open={!!promotionTarget}
        onOpenChange={(open) => {
          if (!open) {
            setPromotionTarget(null)
          }
        }}
        title={t('mySkills.promotionConfirmTitle')}
        description={promotionTarget ? t('mySkills.promotionConfirmDescription', { skill: promotionTarget.name, version: promotionTarget.version }) : ''}
        confirmText={t('mySkills.submitVersionToGlobal')}
        onConfirm={handleSubmitPromotion}
      />

      <ConfirmDialog confirmAnalyticsId="skill.archive.confirm" cancelAnalyticsId="skill.archive.cancel"
        open={!!archiveTarget}
        onOpenChange={(open) => {
          if (!open) {
            setArchiveTarget(null)
          }
        }}
        title={t('mySkills.archiveConfirmTitle')}
        description={archiveTarget ? t('mySkills.archiveConfirmDescription', { skill: archiveTarget.name }) : ''}
        confirmText={t('mySkills.archive')}
        onConfirm={handleArchiveSkill}
      />

      <ConfirmDialog confirmAnalyticsId="skill.unarchive.confirm" cancelAnalyticsId="skill.unarchive.cancel"
        open={!!unarchiveTarget}
        onOpenChange={(open) => {
          if (!open) {
            setUnarchiveTarget(null)
          }
        }}
        title={t('mySkills.unarchiveConfirmTitle')}
        description={unarchiveTarget ? t('mySkills.unarchiveConfirmDescription', { skill: unarchiveTarget.name }) : ''}
        confirmText={t('mySkills.unarchive')}
        onConfirm={handleUnarchiveSkill}
      />

      <ConfirmDialog confirmAnalyticsId="skill.review.withdraw.confirm" cancelAnalyticsId="skill.review.withdraw.cancel"
        open={!!withdrawTarget}
        onOpenChange={(open) => {
          if (!open) {
            setWithdrawTarget(null)
          }
        }}
        title={t('mySkills.withdrawConfirmTitle')}
        description={withdrawTarget ? t('mySkills.withdrawConfirmDescription', { skill: withdrawTarget.name }) : ''}
        confirmText={t('mySkills.withdrawReview')}
        onConfirm={handleWithdrawSkill}
      />
    </div>
  )
}
