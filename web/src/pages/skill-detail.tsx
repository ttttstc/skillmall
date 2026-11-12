import { startTransition, useCallback, useEffect, useRef, useState, type MouseEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams, useNavigate, useRouterState, useSearch } from '@tanstack/react-router'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, ArrowUpCircle, Boxes, ChevronDown, ChevronUp, Clock, Download, FileArchive, Globe, Lock, Maximize2, RefreshCw, ShieldCheck, Star, User, Users } from 'lucide-react'
import { MarkdownRenderer } from '@/features/skill/markdown-renderer'
import { SkillVisibilityDialog } from '@/features/skill/skill-visibility-dialog'
import { SkillAvatar } from '@/features/skill/skill-avatar'
import { SkillAvatarTakedown } from '@/features/skill/skill-avatar-takedown'
import { resolvePackageRelativeLink } from '@/features/skill/package-relative-link'
import { FileTree } from '@/features/skill/file-tree'
import { FilePreviewDialog } from '@/features/skill/file-preview-dialog'
import { CodeRenderer } from '@/features/skill/code-renderer'
import { canPreviewFile, getFileTypeLabel, getLanguageForHighlight } from '@/features/skill/file-type-utils'
import type { FileTreeNode } from '@/features/skill/file-tree-builder'
import type { SkillFile, SkillVersion } from '@/api/types'
import { getVersionPresentation } from '@/shared/lib/version-presentation'
import { CopyButton } from '@/shared/components/copy-button'
import { InstallCommand } from '@/features/skill/install-command'
import { ShareButton } from '@/features/skill/share-button'
import { InstallForAgentButton } from '@/features/skill/install-for-agent-button'
import { SkillLabelPanel } from '@/features/skill/skill-label-panel'
import { GithubSkillSourceCard } from '@/features/skill/github-skill-source'
import { ComplianceSnapshotPanel } from '@/features/skill/compliance-snapshot-panel'
import {
  getOverviewCollapseMaxHeight,
  OVERVIEW_COLLAPSE_DESKTOP_MAX_HEIGHT,
  shouldCollapseOverview,
  shouldReleaseOverviewLayoutQuiet,
} from '@/features/skill/overview-collapse'
import { resolveSkillActionErrorTitle } from '@/features/skill/skill-action-error'
import { isPrecheckConfirmationMessage, extractPrecheckWarnings } from '@/features/publish/publish-error-utils'
import { clearDeletedSkillQueries, isDeleteSlugConfirmationValid, resolveDeletedSkillReturnTo } from '@/features/skill/skill-delete-flow'
import { isSkillDetailQueriesEnabled } from './skill-detail-query'
import { RatingInput } from '@/features/social/rating-input'
import { StarButton } from '@/features/social/star-button'
import { SubscribeButton } from '@/features/social/subscribe-button'
import { SkillReviews } from '@/features/social/skill-reviews'
import { useAuth } from '@/features/auth/use-auth'
import { useMyNamespaces } from '@/shared/hooks/use-namespace-queries'
import { adminApi, ApiError, buildApiUrl, WEB_API_PREFIX } from '@/api/client'
import { useSubmitSkillReport } from '@/features/report/use-skill-reports'
import { SecurityAuditSummary } from '@/features/security-audit/security-audit-summary'
import { SecurityEvaluationNotice } from '@/features/security-audit/security-evaluation-notice'
import { SecurityStatusCell } from '@/features/security-audit/security-status-cell'
import { LoadError } from '@/shared/components/load-error'
import { formatLocalDateTime } from '@/shared/lib/date-time'
import { incrementSkillDownloadCount } from '@/shared/lib/skill-download-cache'
import { getSkillLabelSearch, getSkillSquareSearch, normalizeSkillDetailReturnTo } from '@/shared/lib/skill-navigation'
import { formatCompactCount } from '@/shared/lib/number-format'
import { resolveDocumentationFilePath } from '@/shared/lib/skill-documentation'
import { getHeadlineVersion, getOwnerPreviewVersion, getPublishedVersion } from '@/shared/lib/skill-lifecycle'
import { navigateAfterOverlays } from '@/shared/lib/navigate-after-overlays'
import { NamespaceBadge } from '@/shared/components/namespace-badge'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/shared/ui/tabs'
import { Button } from '@/shared/ui/button'
import { Card } from '@/shared/ui/card'
import { ConfirmDialog } from '@/shared/components/confirm-dialog'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/shared/ui/dialog'
import { Input } from '@/shared/ui/input'
import { Textarea } from '@/shared/ui/textarea'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/shared/ui/select'
import { toast } from '@/shared/lib/toast'
import { cn } from '@/shared/lib/utils'
import {
  useSkillDetail,
  useSkillVersions,
  useSkillVersionDetail,
  useSkillFiles,
  useSkillReadme,
  useSkillFile,
  useArchiveSkill,
  useDeleteSkill,
  useDeleteSkillVersion,
  useRereleaseSkillVersion,
  useUnarchiveSkill,
  useWithdrawSkillReview,
  useSubmitForReview,
  useConfirmPublish,
} from '@/shared/hooks/use-skill-queries'
import { usePromotionSourceState, useSubmitPromotion } from '@/shared/hooks/use-user-queries'
import { useDirectPromotionRevocation, usePromotionRevocationHistory, useSubmitPromotionRevocation } from '@/features/promotion/use-promotion-revocations'

/**
 * Detail page for one skill and its version history.
 *
 * This page coordinates documentation rendering, file browsing, downloads, lifecycle actions,
 * promotion/report dialogs, and social interactions for the selected skill.
 */
function suggestNextVersion(version: string) {
  const semverMatch = version.match(/^(\d+)\.(\d+)\.(\d+)$/)
  if (semverMatch) {
    const [, major, minor, patch] = semverMatch
    return `${major}.${minor}.${Number.parseInt(patch, 10) + 1}`
  }
  return `${version}.1`
}

function parseMetadataJson(parsed?: string) {
  if (!parsed) {
    return {}
  }
  try {
    const value = JSON.parse(parsed)
    return typeof value === 'object' && value !== null ? value : {}
  } catch {
    return {}
  }
}

function createPackageFilePreviewNode(file: SkillFile): FileTreeNode {
  const pathParts = file.filePath.split('/').filter(Boolean)
  const name = pathParts[pathParts.length - 1] ?? file.filePath

  return {
    id: file.filePath,
    name,
    path: file.filePath,
    type: 'file',
    file,
    depth: Math.max(pathParts.length - 1, 0),
  }
}

/** Month/day label for the version timeline; falls back to a dash when the timestamp is missing. */
function formatVersionMonthDay(value: string | null | undefined, language: string): string {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return '—'
  }
  return new Intl.DateTimeFormat(language, { month: '2-digit', day: '2-digit' }).format(date)
}

/** Year bucket for the version timeline; unpublishable timestamps collapse into one bucket. */
function resolveVersionYear(value: string | null | undefined): string {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : String(date.getFullYear())
}

/** A changelog is free-form text; the timeline renders it as the bullet list the design shows. */
function toChangelogBullets(changelog?: string | null): string[] {
  return (changelog ?? '')
    .split(/\r?\n/)
    .map((line) => line.replace(/^\s*[-*•]\s*/, '').trim())
    .filter(Boolean)
}

/**
 * Picks the file the inline browser opens first: the documented entry point when it exists,
 * otherwise the first previewable file so the pane is not empty on arrival.
 */
function pickInitialPreviewFile(files: SkillFile[] | undefined, documentationPath: string | null): SkillFile | null {
  if (!files || files.length === 0) {
    return null
  }
  if (documentationPath) {
    const documented = files.find((file) => file.filePath === documentationPath)
    if (documented) {
      return documented
    }
  }
  return files.find((file) => canPreviewFile(file.filePath, file.fileSize).canPreview) ?? files[0]
}

function getPromotionConflictKey(error: ApiError): 'promotion.duplicate_pending' | 'promotion.already_promoted' | 'promotion.target_version_conflict' | null {
  if (error.serverMessageKey === 'promotion.duplicate_pending') {
    return 'promotion.duplicate_pending'
  }
  if (error.serverMessageKey === 'promotion.already_promoted') {
    return 'promotion.already_promoted'
  }
  if (error.serverMessageKey === 'promotion.target_version_conflict') {
    return 'promotion.target_version_conflict'
  }
  return null
}

/**
 * Reporting is deferred for the SkillMall first release (proposal Out of Scope). The implementation
 * below is kept so the capability can be re-enabled once reporting ships; until then the entry point
 * must not be exposed to any user.
 */
const REPORTING_ENTRY_AVAILABLE = false

export function SkillDetailPage() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const location = useRouterState({ select: (s) => s.location })
  const search = useSearch({ from: '/space/$namespace/$slug' })
  const queryClient = useQueryClient()
  const [reportDialogOpen, setReportDialogOpen] = useState(false)
  const [reportReason, setReportReason] = useState('')
  const [reportDetails, setReportDetails] = useState('')
  const [hasReported, setHasReported] = useState(false)
  const [archiveConfirmOpen, setArchiveConfirmOpen] = useState(false)
  const [unarchiveConfirmOpen, setUnarchiveConfirmOpen] = useState(false)
  const [promotionConfirmOpen, setPromotionConfirmOpen] = useState(false)
  const [promotionVersionId, setPromotionVersionId] = useState<string | null>(null)
  const [revocationMode, setRevocationMode] = useState<'request' | 'direct' | null>(null)
  const [revocationReason, setRevocationReason] = useState('')
  const [deleteSkillConfirmOpen, setDeleteSkillConfirmOpen] = useState(false)
  const [deleteSkillInputOpen, setDeleteSkillInputOpen] = useState(false)
  const [deleteSkillInput, setDeleteSkillInput] = useState('')
  const [skillDeleted, setSkillDeleted] = useState(false)
  const [deleteVersionTarget, setDeleteVersionTarget] = useState<string | null>(null)
  const [withdrawVersionTarget, setWithdrawVersionTarget] = useState<string | null>(null)
  const [rereleaseTarget, setRereleaseTarget] = useState<string | null>(null)
  const [targetVersionInput, setTargetVersionInput] = useState('')
  const [rereleaseWarnings, setRereleaseWarnings] = useState<string[]>([])
  const [rereleaseWarningDialogOpen, setRereleaseWarningDialogOpen] = useState(false)
  const [diffSourceVersion, setDiffSourceVersion] = useState<string | null>(null)
  const [confirmPublishTarget, setConfirmPublishTarget] = useState<string | null>(null)
  const [submitReviewTarget, setSubmitReviewTarget] = useState<string | null>(null)
  const [diffCompareVersion, setDiffCompareVersion] = useState<string | null>(null)
  const [isOverviewExpanded, setIsOverviewExpanded] = useState(false)
  const [isOverviewCollapsible, setIsOverviewCollapsible] = useState(false)
  const [overviewMaxHeight, setOverviewMaxHeight] = useState(OVERVIEW_COLLAPSE_DESKTOP_MAX_HEIGHT)
  // File preview state
  const [previewNode, setPreviewNode] = useState<FileTreeNode | null>(null)
  const [previewDialogOpen, setPreviewDialogOpen] = useState(false)
  const [activeTab, setActiveTab] = useState('readme')
  const [installMethod, setInstallMethod] = useState<'command' | 'manual'>('command')
  const [selectedFilePath, setSelectedFilePath] = useState<string | null>(null)
  const [collapsedVersionYears, setCollapsedVersionYears] = useState<string[]>([])
  const overviewContentRef = useRef<HTMLDivElement | null>(null)
  const overviewSectionRef = useRef<HTMLDivElement | null>(null)
  const overviewLayoutQuietRef = useRef(false)
  const overviewQuietGenerationRef = useRef(0)
  const { namespace, slug } = useParams({ from: '/space/$namespace/$slug' })
  const { user, hasRole } = useAuth()
  const { data: myNamespaces } = useMyNamespaces(Boolean(user))
  const isPromotionAdmin = hasRole('SKILL_ADMIN') || hasRole('SUPER_ADMIN')
  const detailQueriesEnabled = isSkillDetailQueriesEnabled(skillDeleted)
  const qns = detailQueriesEnabled ? namespace : ''
  const qslug = detailQueriesEnabled ? slug : ''
  const { data: skill, isLoading: isLoadingSkill, isFetching: isFetchingSkill, error: skillError, refetch: refetchSkill } = useSkillDetail(qns, qslug, detailQueriesEnabled)
  const skillReady = detailQueriesEnabled && Boolean(skill) && !isLoadingSkill && !isFetchingSkill && !skillError
  const { data: versions } = useSkillVersions(qns, qslug, skillReady)
  const { data: promotionState, isLoading: isLoadingPromotionState, error: promotionStateError } = usePromotionSourceState(
    skill?.id ?? 0,
    skillReady && Boolean(user) && namespace !== 'global' && Boolean(skill?.canManageLifecycle || skill?.canSubmitPromotion || isPromotionAdmin),
  )
  const { data: revocationHistory, isLoading: isLoadingRevocationHistory, error: revocationHistoryError } = usePromotionRevocationHistory(
    skill?.id ?? 0,
    skillReady && Boolean(user) && namespace !== 'global' && Boolean(skill?.canManageLifecycle || skill?.canSubmitPromotion || isPromotionAdmin),
  )
  const pendingRevocation = revocationHistory?.find((request) => request.status === 'PENDING')
  const headlineVersion = skill ? getHeadlineVersion(skill) : null
  const publishedVersion = skill ? getPublishedVersion(skill) : null
  const publishedVersions = versions?.filter((version) => version.status === 'PUBLISHED') ?? []
  const promotionVersion = publishedVersions.find((version) => String(version.id) === promotionVersionId)
    ?? publishedVersions.find((version) => version.id === publishedVersion?.id)
    ?? publishedVersions[0]
  const ownerPreviewVersion = skill ? getOwnerPreviewVersion(skill) : null
  const requestedVersion = search.version
  const selectedVersion = versions?.some(version => version.version === requestedVersion)
    ? requestedVersion
    : headlineVersion?.version ?? versions?.[0]?.version
  const selectedVersionEntry = versions?.find((version) => version.version === selectedVersion) ?? versions?.[0]
  const versionGroups = (() => {
    // Version history is presented year by year, newest first, following the source order.
    const groups = new Map<string, SkillVersion[]>()
    for (const version of versions ?? []) {
      const year = resolveVersionYear(version.publishedAt)
      const bucket = groups.get(year)
      if (bucket) {
        bucket.push(version)
      } else {
        groups.set(year, [version])
      }
    }
    return Array.from(groups, ([year, entries]) => ({ year, versions: entries }))
  })()
  const toggleVersionYear = (year: string) => {
    setCollapsedVersionYears((current) => (current.includes(year)
      ? current.filter((entry) => entry !== year)
      : [...current, year]))
  }
  const selectedMetadata = selectedVersion === ownerPreviewVersion?.version ? ownerPreviewVersion
    : selectedVersion === publishedVersion?.version ? publishedVersion
      : selectedVersion === headlineVersion?.version ? headlineVersion : null
  const { data: selectedVersionDetail } = useSkillVersionDetail(qns, qslug, selectedVersion, skillReady && !selectedMetadata)
  const historicalMetadata = getVersionPresentation(selectedVersionDetail?.parsedMetadataJson)
  const selectedDisplayName = selectedMetadata?.displayName ?? historicalMetadata.displayName ?? skill?.displayName
  const selectedSummary = selectedMetadata?.summary ?? historicalMetadata.summary ?? skill?.summary
  const showingUnpublishedPreview = selectedVersionEntry != null && selectedVersionEntry.status !== 'PUBLISHED'

  const { data: files } = useSkillFiles(qns, qslug, selectedVersion, skillReady)
  const documentationPath = resolveDocumentationFilePath(files)
  const initialPreviewFile = pickInitialPreviewFile(files, documentationPath)
  const selectedFile = (selectedFilePath ? files?.find((file) => file.filePath === selectedFilePath) : null) ?? initialPreviewFile
  const inlinePreviewPath = activeTab === 'files' ? selectedFile?.filePath ?? null : null
  const { data: inlinePreviewContent, isLoading: isInlinePreviewLoading, error: inlinePreviewError } = useSkillFile(
    qns,
    qslug,
    selectedVersion,
    inlinePreviewPath,
    skillReady && Boolean(inlinePreviewPath),
  )
  const { data: readme, error: readmeError } = useSkillReadme(qns, qslug, selectedVersion, documentationPath, skillReady)
  const { data: previewContent, isLoading: isLoadingPreview, error: previewError } = useSkillFile(
    qns,
    qslug,
    selectedVersion,
    previewNode?.path || null,
    previewDialogOpen && !!previewNode && skillReady
  )
  const { data: diffSourceDetail } = useSkillVersionDetail(qns, qslug, diffSourceVersion ?? undefined, skillReady)
  const { data: diffCompareDetail } = useSkillVersionDetail(qns, qslug, diffCompareVersion ?? undefined, skillReady)
  const { data: diffSourceFiles } = useSkillFiles(qns, qslug, diffSourceVersion ?? undefined, skillReady)
  const { data: diffCompareFiles } = useSkillFiles(qns, qslug, diffCompareVersion ?? undefined, skillReady)
  const diffSourceDocumentationPath = resolveDocumentationFilePath(diffSourceFiles)
  const diffCompareDocumentationPath = resolveDocumentationFilePath(diffCompareFiles)
  const { data: diffSourceReadme } = useSkillReadme(qns, qslug, diffSourceVersion ?? undefined, diffSourceDocumentationPath, skillReady)
  const { data: diffCompareReadme } = useSkillReadme(qns, qslug, diffCompareVersion ?? undefined, diffCompareDocumentationPath, skillReady)
  const governanceVisible = hasRole('SKILL_ADMIN') || hasRole('SUPER_ADMIN')
  const canHideSkill = hasRole('SUPER_ADMIN')
  const isPendingPreview = skill?.resolutionMode === 'OWNER_PREVIEW' && headlineVersion?.status === 'PENDING_REVIEW'
  const isRejectedPreview = skill?.resolutionMode === 'OWNER_PREVIEW' && headlineVersion?.status === 'REJECTED'
  const isReviewFlowPending = skill?.resolutionMode === 'OWNER_PREVIEW'
    && ['PENDING_REVIEW', 'SCANNING', 'SCAN_FAILED'].includes(headlineVersion?.status ?? '')
  const hasPendingOwnerPreview = ownerPreviewVersion?.status === 'PENDING_REVIEW'
  const hasRejectedOwnerPreview = ownerPreviewVersion?.status === 'REJECTED'
  const hasPublishedPendingReview = Boolean(publishedVersion && hasPendingOwnerPreview)
  const canInteract = skill?.canInteract ?? true
  const canReport = skill?.canReport ?? true
  const canHardDeleteSkill = Boolean(skill && user && ((!skill.ownerReadOnly && skill.ownerId === user.userId) || hasRole('SUPER_ADMIN')))
  const canManageLabels = Boolean(skill && user && (skill.canManageLifecycle || hasRole('SUPER_ADMIN')))
  const canManageSecurityScan = Boolean(skill && user && (
    skill.canManageLifecycle || hasRole('SKILL_ADMIN') || hasRole('SUPER_ADMIN')
  ))
  const securityAuditVersion = ownerPreviewVersion?.status === 'SCAN_FAILED'
    ? ownerPreviewVersion
    : selectedVersionEntry
  const isVersionDownloadable = selectedVersionEntry?.downloadAvailable ?? false
  const isVersionInstallable = selectedVersionEntry?.status === 'PUBLISHED' && isVersionDownloadable
  const suiteMemberships = skill?.memberOfSuites?.items ?? skill?.entryForSuites ?? []
  const suiteMembershipTotal = skill?.memberOfSuites?.total ?? suiteMemberships.length

  useEffect(() => {
    // Recompute collapse rules whenever rendered documentation height changes so the page can keep
    // a readable summary section on different screen sizes.
    if (!readme || typeof window === 'undefined') {
      setIsOverviewCollapsible(false)
      setIsOverviewExpanded(false)
      return
    }

    const updateOverviewState = () => {
      if (!overviewContentRef.current || overviewLayoutQuietRef.current) {
        return
      }

      const nextMaxHeight = getOverviewCollapseMaxHeight(window.innerWidth, window.innerHeight)
      const nextCollapsible = shouldCollapseOverview(
        overviewContentRef.current.scrollHeight,
        window.innerWidth,
        window.innerHeight,
      )

      // Bail out when ResizeObserver/layout noise repeats the same values to avoid
      // re-render storms that race with body portals (Select, DropdownMenu, Dialog).
      setOverviewMaxHeight((current) => (current === nextMaxHeight ? current : nextMaxHeight))
      setIsOverviewCollapsible((current) => (current === nextCollapsible ? current : nextCollapsible))

      if (!nextCollapsible) {
        setIsOverviewExpanded((current) => (current ? false : current))
      }
    }

    updateOverviewState()

    window.addEventListener('resize', updateOverviewState)
    const resizeObserver = typeof ResizeObserver !== 'undefined'
      ? new ResizeObserver(() => updateOverviewState())
      : null

    if (resizeObserver && overviewContentRef.current) {
      resizeObserver.observe(overviewContentRef.current)
    }

    return () => {
      window.removeEventListener('resize', updateOverviewState)
      resizeObserver?.disconnect()
      overviewQuietGenerationRef.current += 1
      overviewLayoutQuietRef.current = false
    }
  }, [readme])

  const handleToggleOverview = () => {
    // Quiet ResizeObserver for the expand/collapse commit (no max-height transition).
    const quietGeneration = overviewQuietGenerationRef.current + 1
    overviewQuietGenerationRef.current = quietGeneration
    overviewLayoutQuietRef.current = true
    const expanding = !isOverviewExpanded
    startTransition(() => {
      setIsOverviewExpanded(expanding)
    })
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        if (shouldReleaseOverviewLayoutQuiet(overviewQuietGenerationRef.current, quietGeneration)) {
          overviewLayoutQuietRef.current = false
        }
      })
    })
    if (!expanding) {
      requestAnimationFrame(() => {
        overviewSectionRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
      })
    }
  }

  const refreshSkill = () => {
    // Several actions mutate derived lifecycle state; refresh the shared skill detail and common
    // list caches together to keep the rest of the app consistent.
    queryClient.invalidateQueries({ queryKey: ['skills', namespace, slug] })
    queryClient.invalidateQueries({ queryKey: ['skills', namespace, slug, 'versions'] })
    queryClient.invalidateQueries({ queryKey: ['skills'] })
  }

  const hideMutation = useMutation({
    mutationFn: () => adminApi.hideSkill(skill!.id),
    onSuccess: refreshSkill,
  })

  const unhideMutation = useMutation({
    mutationFn: () => adminApi.unhideSkill(skill!.id),
    onSuccess: refreshSkill,
  })

  const yankMutation = useMutation({
    mutationFn: () => adminApi.yankVersion(selectedVersionEntry!.id),
    onSuccess: refreshSkill,
  })
  const archiveMutation = useArchiveSkill()
  const unarchiveMutation = useUnarchiveSkill()
  const deleteSkillMutation = useDeleteSkill()
  const deleteVersionMutation = useDeleteSkillVersion()
  const withdrawReviewMutation = useWithdrawSkillReview()
  const rereleaseVersionMutation = useRereleaseSkillVersion()
  const submitPromotionMutation = useSubmitPromotion()
  const submitRevocationMutation = useSubmitPromotionRevocation()
  const directRevocationMutation = useDirectPromotionRevocation()
  const reportMutation = useSubmitSkillReport(namespace, slug)
  const submitForReviewMutation = useSubmitForReview()
  const confirmPublishMutation = useConfirmPublish()

  const triggerBrowserDownload = (url: string) => {
    const link = document.createElement('a')
    link.href = url
    document.body.appendChild(link)
    link.click()
    link.remove()
  }

  // File tree click handler: opens preview dialog for the selected file
  const handleFileClick = (node: FileTreeNode) => {
    setPreviewNode(node)
    setPreviewDialogOpen(true)
  }

  const handlePackageMarkdownLinkClick = useCallback((
    href: string,
    event: MouseEvent<HTMLAnchorElement>,
    currentFilePath: string | null | undefined,
  ) => {
    const resolution = resolvePackageRelativeLink(href, currentFilePath, files)

    if (resolution.status === 'ignored') {
      return
    }

    event.preventDefault()

    if (resolution.status === 'matched') {
      setPreviewNode(createPackageFilePreviewNode(resolution.file))
      setPreviewDialogOpen(true)
      return
    }

    toast.error(t('skillDetail.packageLinkMissingTitle'), t('skillDetail.packageLinkMissingDescription'))
  }, [files, t])

  const handleOverviewLinkClick = useCallback((href: string, event: MouseEvent<HTMLAnchorElement>) => {
    handlePackageMarkdownLinkClick(href, event, documentationPath)
  }, [documentationPath, handlePackageMarkdownLinkClick])

  const handlePreviewLinkClick = useCallback((href: string, event: MouseEvent<HTMLAnchorElement>) => {
    handlePackageMarkdownLinkClick(href, event, previewNode?.path)
  }, [handlePackageMarkdownLinkClick, previewNode?.path])

  // Download a single file from the skill version
  const downloadSingleFile = (filePath: string, fileName: string) => {
    const isAnonymousAllowed = namespace === 'global' && skill?.visibility === 'PUBLIC'
    if (!user && !isAnonymousAllowed) {
      requireLogin()
      return
    }
    if (!selectedVersion) return
    const cleanNamespace = namespace.startsWith('@') ? namespace.slice(1) : namespace
    const url = buildApiUrl(
      `${WEB_API_PREFIX}/skills/${cleanNamespace}/${encodeURIComponent(slug)}/versions/${encodeURIComponent(selectedVersion)}/file?path=${encodeURIComponent(filePath)}`
    )
    // Set download attribute to the original filename so the browser saves it correctly
    const link = document.createElement('a')
    link.href = url
    link.download = fileName
    document.body.appendChild(link)
    link.click()
    link.remove()
  }

  const handleDownloadFile = () => {
    if (!previewNode) return
    downloadSingleFile(previewNode.path, previewNode.name)
  }

  // The inline file browser has no dialog node, so it asks for the file it is currently showing.
  const handleDownloadPreviewFile = () => {
    if (!selectedFile) return
    const name = selectedFile.filePath.split('/').filter(Boolean).pop() ?? selectedFile.filePath
    downloadSingleFile(selectedFile.filePath, name)
  }

  const downloadVersion = (version: SkillVersion) => {
    const isAnonymousAllowed = namespace === 'global' && skill?.visibility === 'PUBLIC'
    if (!user && !isAnonymousAllowed) {
      requireLogin()
      return
    }
    if (!version.downloadAvailable) {
      return
    }

    try {
      const cleanNamespace = namespace.startsWith('@') ? namespace.slice(1) : namespace
      triggerBrowserDownload(
        buildApiUrl(`${WEB_API_PREFIX}/skills/${cleanNamespace}/${encodeURIComponent(slug)}/versions/${encodeURIComponent(version.version)}/download`),
      )
      incrementSkillDownloadCount(queryClient, { namespace, slug })
      queryClient.invalidateQueries({ queryKey: ['skills', namespace, slug] })
      queryClient.invalidateQueries({ queryKey: ['skills', 'my'] })
      queryClient.invalidateQueries({ queryKey: ['skills', 'stars'] })
      queryClient.invalidateQueries({ queryKey: ['skills', 'search'] })
    } catch (error) {
      toast.error(t(resolveSkillActionErrorTitle('download')), error instanceof Error ? error.message : '')
    }
  }

  const handleDownload = () => {
    if (!selectedVersionEntry || !isVersionDownloadable) {
      return
    }
    downloadVersion(selectedVersionEntry)
  }

  const handleFileSelect = (node: FileTreeNode) => {
    // The inline browser keeps the tree in place; the dialog stays available for a wider read.
    setSelectedFilePath(node.path)
  }

  const requireLogin = () => {
    navigate({
      to: '/login',
      search: {
        returnTo: `${location.pathname}${location.searchStr}${location.hash}`,
      },
    })
  }

  const handleOpenReport = () => {
    if (!user) {
      requireLogin()
      return
    }
    setReportDialogOpen(true)
  }

  const handleSubmitReport = async () => {
    if (!reportReason.trim()) {
      toast.error(t('skillDetail.reportReasonRequired'))
      return
    }

    try {
      await reportMutation.mutateAsync({
        reason: reportReason.trim(),
        details: reportDetails.trim() || undefined,
      })
      setReportDialogOpen(false)
      setReportReason('')
      setReportDetails('')
      setHasReported(true)
      toast.success(t('skillDetail.reportSuccessTitle'), t('skillDetail.reportSuccessDescription'))
    } catch (error) {
      toast.error(t(resolveSkillActionErrorTitle('report')), error instanceof Error ? error.message : '')
    }
  }

  const handleBack = () => {
    const returnTo = normalizeSkillDetailReturnTo(search.returnTo)
    if (returnTo) {
      navigate({ to: returnTo })
      return
    }
    navigate({ to: '/search', search: getSkillSquareSearch() })
  }

  const resolveSkillStatusLabel = (status?: string) => {
    if (status === 'ARCHIVED') {
      return t('skillDetail.statusArchived')
    }
    if (status === 'ACTIVE') {
      return t('skillDetail.statusActive')
    }
    if (status === 'HIDDEN') {
      return t('skillDetail.statusHidden')
    }
    return status ?? ''
  }

  const resolveVersionStatusLabel = (status?: string) => {
    const map: Record<string, string> = {
      DRAFT: t('skillDetail.versionStatusDraft'),
      SCANNING: t('skillDetail.versionStatusScanning'),
      SCAN_FAILED: t('skillDetail.versionStatusScanFailed'),
      UPLOADED: t('skillDetail.versionStatusUploaded'),
      PENDING_REVIEW: t('skillDetail.versionStatusPendingReview'),
      PUBLISHED: t('skillDetail.versionStatusPublished'),
      REJECTED: t('skillDetail.versionStatusRejected'),
      YANKED: t('skillDetail.versionStatusYanked'),
    }
    return status ? (map[status] ?? status) : ''
  }

  const canDeleteVersion = (status?: string) => status === 'DRAFT' || status === 'REJECTED' || status === 'SCAN_FAILED' || status === 'UPLOADED'
  const isLastVersion = versions?.length === 1
  const canWithdrawVersion = (status?: string) => status === 'PENDING_REVIEW'
  const canRereleaseVersion = (status?: string) => status === 'PUBLISHED'
  const isNotFoundError = skillError instanceof ApiError
    ? skillError.status === 400 || skillError.status === 404 || skillError.serverMessageKey === 'skill.not_found'
    : false

  useEffect(() => {
    if (!isNotFoundError) {
      return
    }
    clearDeletedSkillQueries(queryClient, namespace, slug, skill?.id)
  }, [isNotFoundError, namespace, queryClient, skill?.id, slug])

  const metadataDiffEntries = (() => {
    const source = parseMetadataJson(diffSourceDetail?.parsedMetadataJson)
    const compare = parseMetadataJson(diffCompareDetail?.parsedMetadataJson)
    const keys = Array.from(new Set([...Object.keys(source), ...Object.keys(compare)])).sort()
    return keys
      .filter((key) => JSON.stringify(source[key]) !== JSON.stringify(compare[key]))
      .map((key) => ({
        key,
        source: source[key],
        target: compare[key],
      }))
  })()

  const fileDiffSummary = (() => {
    const sourceMap = new Map((diffSourceFiles ?? []).map((file) => [file.filePath, file.sha256]))
    const compareMap = new Map((diffCompareFiles ?? []).map((file) => [file.filePath, file.sha256]))
    const added: string[] = []
    const removed: string[] = []
    const changed: string[] = []

    for (const [path, hash] of sourceMap.entries()) {
      if (!compareMap.has(path)) {
        removed.push(path)
      } else if (compareMap.get(path) !== hash) {
        changed.push(path)
      }
    }
    for (const path of compareMap.keys()) {
      if (!sourceMap.has(path)) {
        added.push(path)
      }
    }

    return { added, removed, changed }
  })()

  const handleArchive = async () => {
    try {
      await archiveMutation.mutateAsync({ namespace, slug })
      toast.success(
        t('skillDetail.archiveSuccessTitle'),
        t('skillDetail.archiveSuccessDescription', { skill: skill?.displayName ?? slug }),
      )
      setArchiveConfirmOpen(false)
    } catch (error) {
      toast.error(t('skillDetail.archiveErrorTitle'), error instanceof Error ? error.message : '')
      throw error
    }
  }

  const handleUnarchive = async () => {
    try {
      await unarchiveMutation.mutateAsync({ namespace, slug })
      toast.success(
        t('skillDetail.unarchiveSuccessTitle'),
        t('skillDetail.unarchiveSuccessDescription', { skill: skill?.displayName ?? slug }),
      )
      setUnarchiveConfirmOpen(false)
    } catch (error) {
      toast.error(t('skillDetail.unarchiveErrorTitle'), error instanceof Error ? error.message : '')
      throw error
    }
  }

  const handleOpenDeleteSkillInput = async () => {
    setDeleteSkillConfirmOpen(false)
    setDeleteSkillInput('')
    setDeleteSkillInputOpen(true)
  }

  const handleDeleteSkill = async () => {
    if (!skill || !isDeleteSlugConfirmationValid(skill.slug, deleteSkillInput)) {
      return
    }
    try {
      await deleteSkillMutation.mutateAsync({ namespace, slug, ownerId: skill.ownerId })
    } catch {
      // MutationCache already reports the error and handles expired sessions.
      // Consume the button handler rejection without running success side effects.
      return
    }
    setSkillDeleted(true)
    toast.success(
      t('skillDetail.deleteSkillSuccessTitle'),
      t('skillDetail.deleteSkillSuccessDescription', { skill: skill.displayName }),
    )
    setDeleteSkillInputOpen(false)
    navigateAfterOverlays(() => {
      navigate({ to: resolveDeletedSkillReturnTo(search.returnTo) })
    })
    queryClient.removeQueries({ queryKey: ['skills', namespace, slug] })
    queryClient.invalidateQueries({ queryKey: ['skills', 'my'] })
  }

  const handleDeleteVersion = async () => {
    if (!deleteVersionTarget) {
      return
    }
    try {
      await deleteVersionMutation.mutateAsync({ namespace, slug, version: deleteVersionTarget })
      toast.success(
        t('skillDetail.deleteVersionSuccessTitle'),
        t('skillDetail.deleteVersionSuccessDescription', { version: deleteVersionTarget }),
      )
      setDeleteVersionTarget(null)
    } catch (error) {
      toast.error(t('skillDetail.deleteVersionErrorTitle'), error instanceof Error ? error.message : '')
      throw error
    }
  }

  const handleWithdrawVersion = async () => {
    if (!withdrawVersionTarget) {
      return
    }
    try {
      await withdrawReviewMutation.mutateAsync({ namespace, slug, version: withdrawVersionTarget })
      toast.success(
        t('skillDetail.withdrawReviewSuccessTitle'),
        t('skillDetail.withdrawReviewSuccessDescription', { version: withdrawVersionTarget }),
      )
      setWithdrawVersionTarget(null)
      navigateAfterOverlays(() => {
        navigate({ to: '/dashboard/skills' })
      })
    } catch (error) {
      toast.error(t('skillDetail.withdrawReviewErrorTitle'), error instanceof Error ? error.message : '')
      throw error
    }
  }

  const handleConfirmPublish = async () => {
    if (!confirmPublishTarget) {
      return
    }
    try {
      await confirmPublishMutation.mutateAsync({ namespace, slug, version: confirmPublishTarget })
      toast.success(
        t('skillDetail.confirmPublishSuccessTitle'),
        t('skillDetail.confirmPublishSuccessDescription', { version: confirmPublishTarget }),
      )
      setConfirmPublishTarget(null)
    } catch (error) {
      toast.error(t('skillDetail.confirmPublishErrorTitle'), error instanceof Error ? error.message : '')
      throw error
    }
  }

  const handleSubmitForReview = async () => {
    if (!submitReviewTarget) {
      return
    }
    try {
      await submitForReviewMutation.mutateAsync({ namespace, slug, version: submitReviewTarget, targetVisibility: 'PUBLIC' })
      toast.success(
        t('skillDetail.submitReviewSuccessTitle'),
        t('skillDetail.submitReviewSuccessDescription', { version: submitReviewTarget }),
      )
      setSubmitReviewTarget(null)
    } catch (error) {
      toast.error(t('skillDetail.submitReviewErrorTitle'), error instanceof Error ? error.message : '')
      throw error
    }
  }

  const handleOpenRerelease = (version: string) => {
    setRereleaseTarget(version)
    setTargetVersionInput(suggestNextVersion(version))
  }

  const handleRereleaseVersion = async (confirmWarnings = false) => {
    if (!rereleaseTarget || !targetVersionInput.trim()) {
      return
    }
    try {
      await rereleaseVersionMutation.mutateAsync({
        namespace,
        slug,
        version: rereleaseTarget,
        targetVersion: targetVersionInput.trim(),
        confirmWarnings,
      })
      toast.success(
        t('skillDetail.rereleaseSuccessTitle'),
        t('skillDetail.rereleaseSuccessDescription', { source: rereleaseTarget, target: targetVersionInput.trim() }),
      )
      setRereleaseTarget(null)
      setTargetVersionInput('')
      setRereleaseWarnings([])
      setRereleaseWarningDialogOpen(false)
    } catch (error) {
      if (error instanceof ApiError && isPrecheckConfirmationMessage(error.serverMessage)) {
        const warnings = extractPrecheckWarnings(error.serverMessage)
        setRereleaseWarnings(warnings)
        setRereleaseWarningDialogOpen(true)
        return
      }
      toast.error(t('skillDetail.rereleaseErrorTitle'), error instanceof Error ? error.message : '')
      throw error
    }
  }

  const handleOpenDiff = (version: string) => {
    const publishedVersions = versions?.filter((item) => item.status === 'PUBLISHED') ?? []
    const compareVersion = publishedVersions.find((item) => item.version !== version)?.version ?? null
    if (!compareVersion) {
      toast.error(t('skillDetail.versionCompareUnavailableTitle'), t('skillDetail.versionCompareUnavailableDescription'))
      return
    }
    navigate({
      to: '/space/$namespace/$slug/compare',
      params: { namespace, slug },
      search: { from: version, to: compareVersion },
    })
  }

  const handleSubmitPromotion = async () => {
    if (!skill || !promotionVersion || !promotionState || promotionState.pendingPromotionId) {
      return
    }
    try {
      await submitPromotionMutation.mutateAsync({
        sourceSkillId: skill.id,
        sourceVersionId: promotionVersion.id,
      })
      toast.success(
        t('skillDetail.promotionSuccessTitle'),
        t('skillDetail.promotionSuccessDescription', { skill: skill.displayName, version: promotionVersion.version }),
      )
      setPromotionConfirmOpen(false)
    } catch (error) {
      if (error instanceof ApiError) {
        const conflictKey = getPromotionConflictKey(error)
        if (conflictKey === 'promotion.duplicate_pending') {
          toast.error(t('skillDetail.promotionDuplicateTitle'), t('skillDetail.promotionDuplicateDescription'))
          return
        }
        if (conflictKey === 'promotion.already_promoted') {
          toast.error(t('skillDetail.promotionAlreadyPromotedTitle'), t('skillDetail.promotionAlreadyPromotedDescription'))
          return
        }
        if (conflictKey === 'promotion.target_version_conflict') {
          toast.error(t('skillDetail.promotionVersionConflictTitle'), t('skillDetail.promotionVersionConflictDescription'))
          return
        }
      }
      toast.error(t('skillDetail.promotionErrorTitle'), error instanceof Error ? error.message : '')
      throw error
    }
  }

  const handleSubmitRevocation = async () => {
    if (!skill || !promotionState?.targetSkillId || !revocationMode) {
      return
    }
    try {
      const input = { sourceSkillId: skill.id, reason: revocationReason.trim() }
      if (revocationMode === 'direct') {
        await directRevocationMutation.mutateAsync(input)
      } else {
        await submitRevocationMutation.mutateAsync(input)
      }
      toast.success(t(revocationMode === 'direct' ? 'skillDetail.revocationDirectSuccess' : 'skillDetail.revocationRequestSuccess'))
      setRevocationMode(null)
      setRevocationReason('')
    } catch (error) {
      toast.error(t('skillDetail.revocationError'), error instanceof Error ? error.message : '')
    }
  }

  if (isLoadingSkill) {
    return (
      <div className="space-y-6 animate-fade-up">
        <div className="h-10 w-64 animate-shimmer rounded-lg" />
        <div className="h-5 w-96 animate-shimmer rounded-md" />
        <div className="h-64 animate-shimmer rounded-xl" />
      </div>
    )
  }

  if (skillError) {
    const isForbidden = skillError instanceof ApiError
      ? skillError.status === 403
      : skillError instanceof Error && skillError.message.includes('403')

    if (isForbidden) {
      if (!user) {
        return (
          <div className="text-center py-20 animate-fade-up">
            <h2 className="text-2xl font-bold font-heading mb-2">{t('skillDetail.loginRequired')}</h2>
            <p className="text-muted-foreground mb-6">{t('skillDetail.loginRequiredDesc')}</p>
            <Button data-analytics-id="auth.login.open" onClick={requireLogin}>{t('common.login')}</Button>
          </div>
        )
      }

      return (
        <div className="text-center py-20 animate-fade-up">
          <h2 className="text-2xl font-bold font-heading mb-2">{t('skillDetail.accessDenied')}</h2>
          <p className="text-muted-foreground">{t('skillDetail.accessDeniedDesc')}</p>
        </div>
      )
    }

    if (isNotFoundError) {
      return (
        <div className="text-center py-20 animate-fade-up">
          <h2 className="text-2xl font-bold font-heading mb-2">{t('skillDetail.notFound')}</h2>
          <p className="text-muted-foreground">{t('skillDetail.notFoundDesc')}</p>
        </div>
      )
    }

    // Server or network failure: report it as a load failure. Rendering it as an access denial
    // would claim a permission decision the server never made.
    return <LoadError onRetry={() => { void refetchSkill() }} />
  }

  if (!skill) {
    return (
      <div className="text-center py-20 animate-fade-up">
        <h2 className="text-2xl font-bold font-heading mb-2">{t('skillDetail.notFound')}</h2>
        <p className="text-muted-foreground">{t('skillDetail.notFoundDesc')}</p>
      </div>
    )
  }

  if (skillDeleted) {
    return null
  }

  return (
    <div data-analytics-id="skill.detail.render" className="max-w-[1704px] mx-auto flex flex-col lg:flex-row gap-8 animate-fade-up">
      {/* Main Content */}
      <div className="flex-1 min-w-0 space-y-5">
        {skill.ownerReadOnly && <p role="status" className="rounded-lg border bg-muted/20 p-3 text-sm text-muted-foreground">{t('mySkills.ownerReadOnly')}</p>}
        <div className="space-y-3">
          <Button data-analytics-id="skill.detail.back"
            variant="ghost"
            size="sm"
            className="gap-2 px-0 text-muted-foreground hover:text-foreground"
            onClick={handleBack}
          >
            <ArrowLeft className="h-4 w-4" />
            {t('skillDetail.back')}
          </Button>
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div className="flex min-w-0 items-start gap-4">
              <SkillAvatar name={selectedDisplayName ?? skill.displayName} url={skill.avatarUrl} size="h-16 w-16 text-2xl" />
              <div className="min-w-0 space-y-2">
                <div className="flex flex-wrap items-center gap-2">
                  <h1 className="text-balance break-words font-heading text-2xl font-bold leading-tight text-foreground [overflow-wrap:anywhere]">{selectedDisplayName}</h1>
                  {selectedVersionEntry && (
                    <span className="shrink-0 rounded-full border border-border/60 bg-secondary/40 px-2 py-0.5 font-mono text-xs leading-5 text-muted-foreground">
                      {selectedVersionEntry.syncRevision != null
                        ? t('review.githubRevisionValue', { count: selectedVersionEntry.syncRevision })
                        : `v${selectedVersionEntry.version}`}
                    </span>
                  )}
                </div>
                <div className="flex min-w-0 flex-wrap items-center gap-x-4 gap-y-1 text-xs text-muted-foreground">
                  {skill.ownerDisplayName && (
                    <span className="flex min-w-0 items-center gap-1.5">
                      <User className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                      <span className="min-w-0 truncate">{skill.ownerDisplayName}</span>
                    </span>
                  )}
                  <span className="flex min-w-0 items-center gap-1">
                    <code className="min-w-0 break-all font-mono">@{skill.namespace}/{skill.slug}</code>
                    <CopyButton analyticsId="skill.coordinate.copy" text={`@${skill.namespace}/${skill.slug}`} ariaLabel={t('skillDetail.copyCoordinate')} className="shrink-0" />
                  </span>
                </div>
              </div>
            </div>
            <div className="flex shrink-0 items-center gap-2 [&_button]:rounded-full">
              {canInteract && !isFetchingSkill && <StarButton skillId={skill.id} starCount={skill.starCount} onRequireLogin={requireLogin} />}
              <div className="[&>button]:w-auto [&>button]:rounded-full [&>button]:border [&>button]:border-border [&>button]:bg-background [&>button]:px-4 [&>button]:py-2 [&>button]:hover:bg-muted/60">
                <ShareButton namespace={namespace} slug={slug} description={skill.summary} />
              </div>
            </div>
          </div>
          {(skill.labels?.length ?? 0) > 0 && (
            <div className="flex flex-wrap gap-2">
              {skill.labels!.map((label) => (
                <Link data-analytics-id="search.label.open"
                  key={label.slug}
                  to="/search"
                  search={getSkillLabelSearch(label.slug)}
                  className={cn(
                    'inline-flex items-center rounded-full border px-3 py-1 text-xs font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/70 focus-visible:ring-offset-2',
                    label.type === 'PRIVILEGED'
                      ? 'border-amber-500/40 bg-amber-100 text-amber-900 hover:bg-amber-200/80 dark:bg-amber-950/60 dark:text-amber-300 dark:hover:bg-amber-900/70'
                      : 'border-border bg-secondary text-secondary-foreground hover:bg-secondary/80',
                  )}
                >
                  {label.displayName}
                </Link>
              ))}
            </div>
          )}
          <div className="flex flex-wrap items-center gap-2">
            <NamespaceBadge type="GLOBAL" name={namespace} />
            {skill.status && (
              <span className={cn(
                'badge-soft',
                skill.status === 'ACTIVE' && 'badge-soft-green',
                skill.status === 'ARCHIVED' && 'bg-secondary text-muted-foreground',
                skill.status === 'HIDDEN' && 'bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-400',
                !['ACTIVE', 'ARCHIVED', 'HIDDEN'].includes(skill.status) && 'badge-soft-blue',
              )}>
                {resolveSkillStatusLabel(skill.status)}
              </span>
            )}
            {skill.visibility && (
              <span className={cn(
                'badge-soft inline-flex items-center gap-1',
                skill.visibility === 'PUBLIC' && 'badge-soft-green',
                skill.visibility === 'PRIVATE' && 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300',
                skill.visibility === 'NAMESPACE_ONLY' && 'badge-soft-blue',
              )}>
                {skill.visibility === 'PUBLIC' && <Globe className="h-3 w-3" />}
                {skill.visibility === 'PRIVATE' && <Lock className="h-3 w-3" />}
                {skill.visibility === 'NAMESPACE_ONLY' && <Users className="h-3 w-3" />}
                {skill.visibility === 'PUBLIC' && t('publish.visibilityOptions.public')}
                {skill.visibility === 'PRIVATE' && t('publish.visibilityOptions.private')}
                {skill.visibility === 'NAMESPACE_ONLY' && t('publish.visibilityOptions.namespaceOnly')}
              </span>
            )}
            {isReviewFlowPending && (
              <span className="badge-soft bg-amber-100 text-amber-900 dark:bg-amber-950/60 dark:text-amber-300">
                {t('skillDetail.versionStatusPendingReview')}
              </span>
            )}
            {!isPendingPreview && (isRejectedPreview || hasRejectedOwnerPreview) && skill.canManageLifecycle && (
              <span className="badge-soft bg-red-100 text-red-900 dark:bg-red-950/60 dark:text-red-300">
                {t('skillDetail.rejectedBadge')}
              </span>
            )}
          </div>
          {skill.avatarUrl && user?.userId !== skill.ownerId && (namespace === 'global'
            ? isPromotionAdmin
            : myNamespaces?.some((item) => item.slug === namespace && (item.currentUserRole === 'OWNER' || item.currentUserRole === 'ADMIN')))
            && <SkillAvatarTakedown namespace={namespace} slug={slug} />}
          {selectedSummary && (
            <p className="text-sm leading-relaxed text-foreground">{selectedSummary}</p>
          )}
          {showingUnpublishedPreview && (
            <Card className="border-amber-500/30 bg-amber-500/5 p-4 text-sm text-muted-foreground">
              <div className="font-medium text-foreground">{t('skillDetail.unpublishedPreviewTitle')}</div>
              <p className="mt-1">{t('skillDetail.unpublishedPreviewDescription')}</p>
            </Card>
          )}
          {hasRejectedOwnerPreview && (
            <Card className="border-red-500/30 bg-red-500/5 p-4 text-sm text-muted-foreground">
              <div className="font-medium text-foreground">{t('skillDetail.rejectedFeedbackTitle')}</div>
              <p className="mt-1">{t('skillDetail.rejectedPreviewDescription')}</p>
              <div className="mt-3 rounded-xl border border-red-500/20 bg-background/80 p-3">
                <div className="text-xs uppercase tracking-[0.18em] text-muted-foreground">
                  {t('skillDetail.rejectedFeedbackLabel')}
                </div>
                <p className="mt-2 whitespace-pre-wrap text-sm leading-6 text-foreground">
                  {skill.ownerPreviewReviewComment || t('skillDetail.rejectedFeedbackFallback')}
                </p>
              </div>
            </Card>
          )}
        </div>

        <div className="grid grid-cols-1 overflow-hidden rounded-xl border border-border/60 bg-card sm:grid-cols-3">
          <div className="flex min-h-[92px] flex-col items-center justify-center gap-1.5 px-3 py-4 text-center">
            <span className="flex items-center gap-1.5 text-xs text-muted-foreground"><Download className="h-4 w-4" aria-hidden="true" />{t('skillDetail.downloads')}</span>
            <span className="text-xl font-semibold text-blue-600 dark:text-blue-400">{formatCompactCount(skill.downloadCount)}</span>
          </div>
          <div className="flex min-h-[92px] flex-col items-center justify-center gap-1.5 border-t border-border/60 px-3 py-4 text-center sm:border-l sm:border-t-0">
            <span className="flex items-center gap-1.5 text-xs text-muted-foreground"><Star className="h-4 w-4" aria-hidden="true" />{t('starButton.star')}</span>
            <span className="text-xl font-semibold text-amber-600 dark:text-amber-400">{formatCompactCount(skill.starCount)}</span>
          </div>
          <SecurityStatusCell
            skillId={skill.id}
            versionId={securityAuditVersion?.id}
            versionStatus={securityAuditVersion?.status}
            canViewAudit={canManageSecurityScan && Boolean(securityAuditVersion)}
            className="border-t border-border/60 sm:border-t-0"
          />
        </div>

        <Tabs value={activeTab} onValueChange={setActiveTab}>
          <TabsList className="flex w-full gap-8">
            <TabsTrigger data-analytics-id="skill.overview.tab" value="readme">{t('skillDetail.tabReadme')}</TabsTrigger>
            <TabsTrigger value="install">{t('skillDetail.tabInstall')}</TabsTrigger>
            <TabsTrigger data-analytics-id="skill.files.tab" value="files">{t('skillDetail.tabFiles')}</TabsTrigger>
            <TabsTrigger data-analytics-id="skill.versions.tab" value="versions">{t('skillDetail.tabVersionHistory')}</TabsTrigger>
          </TabsList>

          <TabsContent value="readme" className="mt-6">
            {readme ? (
              <Card className="space-y-4 border-0 bg-transparent p-0 shadow-none hover:border-transparent hover:shadow-none">
                {documentationPath ? (
                  <div className="text-xs uppercase tracking-[0.2em] text-muted-foreground">
                    {t('skillDetail.documentationSource', { path: documentationPath })}
                  </div>
                ) : null}
                <div ref={overviewSectionRef} className="space-y-4">
                  <div
                    className={cn(
                      'relative overflow-hidden',
                      !isOverviewExpanded && isOverviewCollapsible && 'rounded-2xl',
                    )}
                    style={!isOverviewExpanded && isOverviewCollapsible ? { maxHeight: `${overviewMaxHeight}px` } : undefined}
                  >
                    <div ref={overviewContentRef}>
                      <MarkdownRenderer content={readme} onLinkClick={handleOverviewLinkClick} />
                    </div>
                    {!isOverviewExpanded && isOverviewCollapsible ? (
                      <div className="pointer-events-none absolute inset-x-0 bottom-0 h-28 bg-gradient-to-t from-card via-card/95 to-transparent" />
                    ) : null}
                  </div>
                  {isOverviewCollapsible ? (
                    <div className="flex justify-center">
                      <Button data-analytics-id="skill.overview.toggle"
                        type="button"
                        variant="outline"
                        size="sm"
                        className="gap-2 rounded-full border-border/70 bg-background/90 px-5 shadow-sm backdrop-blur-sm"
                        aria-expanded={isOverviewExpanded}
                        onClick={handleToggleOverview}
                      >
                        {isOverviewExpanded ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
                        {isOverviewExpanded ? t('skillDetail.collapseOverview') : t('skillDetail.expandOverview')}
                      </Button>
                    </div>
                  ) : null}
                </div>
              </Card>
            ) : readmeError ? (
              <Card className="p-8 text-center">
                <div className="text-base font-semibold text-foreground">{t('skillDetail.documentationUnavailableTitle')}</div>
                <p className="mt-2 text-sm text-muted-foreground">{t('skillDetail.documentationUnavailable')}</p>
              </Card>
            ) : (
              <Card className="p-8 space-y-4">
                <div>
                  <div className="text-base font-semibold text-foreground">{t('skillDetail.noDocumentationTitle')}</div>
                  <p className="mt-2 text-sm text-muted-foreground">{t('skillDetail.noDocumentationDescription')}</p>
                </div>
                {skill.summary ? (
                  <div className="rounded-xl border border-border/60 bg-secondary/20 p-4">
                    <div className="text-xs uppercase tracking-[0.2em] text-muted-foreground">
                      {t('skillDetail.summaryLabel')}
                    </div>
                    <p className="mt-2 text-sm leading-6 text-foreground">{selectedSummary}</p>
                  </div>
                ) : null}
                <div className="text-sm text-muted-foreground">
                  {t('skillDetail.noDocumentationHint')}
                </div>
              </Card>
            )}
          </TabsContent>

          <TabsContent value="install" className="mt-5">
            <Tabs value={installMethod} onValueChange={(value) => setInstallMethod(value as 'command' | 'manual')} className="space-y-4">
              <TabsList className="inline-flex w-auto items-center gap-1 rounded-lg border border-transparent bg-muted p-1">
                <TabsTrigger
                  value="command"
                  className="rounded-md border-b-0 px-3 py-1.5 text-xs data-[state=active]:bg-background data-[state=active]:text-foreground data-[state=active]:shadow-sm"
                >
                  {t('skillDetail.installTabCommand', { defaultValue: '命令行安装' })}
                </TabsTrigger>
                <TabsTrigger
                  value="manual"
                  className="rounded-md border-b-0 px-3 py-1.5 text-xs data-[state=active]:bg-background data-[state=active]:text-foreground data-[state=active]:shadow-sm"
                >
                  {t('skillDetail.installTabManual', { defaultValue: '手动安装' })}
                </TabsTrigger>
              </TabsList>

              <TabsContent value="command" className="space-y-3 pt-1">
                {skill.status === 'ARCHIVED' && (
                  <p className="text-sm text-muted-foreground">{t('skillDetail.archivedInstallHint')}</p>
                )}
                <p role="status" className="text-sm text-muted-foreground">{t('skillDetail.installUnavailable')}</p>
                {publishedVersion ? (
                  <fieldset disabled className="opacity-60" aria-label={t('skillDetail.installUnavailable')}>
                    <InstallCommand analyticsId="skill.command.copy"
                      namespace={namespace}
                      slug={slug}
                      version={publishedVersion.version} skillhubAnalyticsId="skill.command.skillhub.select" clawhubAnalyticsId="skill.command.clawhub.select"
                    />
                  </fieldset>
                ) : null}
              </TabsContent>

              <TabsContent value="manual" className="pt-1">
                <div className="space-y-5">
                  <div className="relative flex gap-3">
                    <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-blue-500/10 text-xs font-semibold text-blue-600 dark:text-blue-400">1</span>
                    <span className="absolute left-[13px] top-8 h-[calc(100%-0.75rem)] w-px bg-border" aria-hidden="true" />
                    <div className="min-w-0 flex-1 space-y-2.5">
                      <div className="text-sm font-semibold text-foreground">{t('skillDetail.download')}</div>
                      <div className="inline-flex flex-wrap items-center gap-3 rounded-xl border border-amber-500/30 bg-amber-500/5 px-4 py-3">
                        <FileArchive className="h-5 w-5 shrink-0 text-amber-600 dark:text-amber-400" aria-hidden="true" />
                        <span className="text-sm font-semibold text-foreground">{skill.slug}.zip</span>
                        <Button variant="ghost"
                          size="sm"
                          className="gap-1.5 px-2"
                          onClick={handleDownload}
                          disabled={!selectedVersionEntry || skill.status === 'ARCHIVED' || !isVersionDownloadable}
                        >
                          <Download className="h-4 w-4" aria-hidden="true" />
                          {t('skillDetail.download')}
                        </Button>
                      </div>
                    </div>
                  </div>

                  <div className="relative flex gap-3">
                    <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-blue-500/10 text-xs font-semibold text-blue-600 dark:text-blue-400">2</span>
                    <span className="absolute left-[13px] top-8 h-[calc(100%-0.75rem)] w-px bg-border" aria-hidden="true" />
                    <div className="min-w-0 flex-1 space-y-1.5">
                      <div className="text-sm font-semibold text-foreground">
                        {t('skillDetail.manualInstallExtractTitle', { defaultValue: '解压' })}
                      </div>
                      <p className="text-sm leading-6 text-muted-foreground">
                        {t('skillDetail.manualInstallExtractDescription', { defaultValue: '将下载的压缩包解压到本地文件目录中' })}
                      </p>
                    </div>
                  </div>

                  <div className="flex gap-3">
                    <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-blue-500/10 text-xs font-semibold text-blue-600 dark:text-blue-400">3</span>
                    <div className="min-w-0 flex-1 space-y-1.5">
                      <div className="text-sm font-semibold text-foreground">
                        {t('skillDetail.manualInstallPlaceTitle', { defaultValue: '运行' })}
                      </div>
                      <p className="text-sm leading-6 text-muted-foreground">
                        {t('skillDetail.manualInstallPlaceDescription', { defaultValue: '将解压后的技能文件夹放到你所使用 Agent 的技能目录下' })}
                      </p>
                    </div>
                  </div>
                </div>
              </TabsContent>
            </Tabs>
          </TabsContent>

          <TabsContent value="files" className="mt-5">
            {files && files.length > 0 ? (
              <div className="grid gap-4 lg:grid-cols-[16rem_minmax(0,1fr)]">
                <Card className="max-h-[32rem] overflow-y-auto p-2">
                  <FileTree analyticsId="skill.file.open" files={files} onFileClick={handleFileSelect} bare />
                </Card>
                <Card className="flex min-h-[20rem] flex-col overflow-hidden p-0">
                  {selectedFile ? (
                    <>
                      <div className="flex items-center gap-2 border-b border-border/50 px-4 py-2.5">
                        <span className="min-w-0 flex-1 truncate font-mono text-xs text-foreground">{selectedFile.filePath}</span>
                        <span className="shrink-0 rounded border border-border/60 bg-background/60 px-1.5 py-0.5 text-[11px] text-muted-foreground">
                          {getFileTypeLabel(selectedFile.filePath)}
                        </span>
                        <Button
                          variant="ghost"
                          size="icon"
                          className="h-7 w-7 shrink-0"
                          title={t('skillDetail.openFileFullscreen', { defaultValue: '全屏查看' })}
                          aria-label={t('skillDetail.openFileFullscreen', { defaultValue: '全屏查看' })}
                          onClick={() => handleFileClick(createPackageFilePreviewNode(selectedFile))}
                        >
                          <Maximize2 className="h-4 w-4" aria-hidden="true" />
                        </Button>
                      </div>
                      <div className="min-h-0 flex-1 overflow-auto px-5 py-4">
                        {isInlinePreviewLoading ? (
                          <div className="flex items-center justify-center py-12 text-sm text-muted-foreground">{t('common.loading')}</div>
                        ) : inlinePreviewError ? (
                          <div className="space-y-2 py-8 text-center">
                            <p className="text-sm font-medium text-foreground">{t('filePreview.loadError')}</p>
                            <p className="text-xs text-muted-foreground">{inlinePreviewError.message}</p>
                          </div>
                        ) : !canPreviewFile(selectedFile.filePath, selectedFile.fileSize).canPreview ? (
                          <div className="space-y-3 py-8 text-center">
                            <p className="text-sm font-medium text-foreground">
                              {canPreviewFile(selectedFile.filePath, selectedFile.fileSize).reason === 'too-large'
                                ? t('filePreview.tooLarge')
                                : canPreviewFile(selectedFile.filePath, selectedFile.fileSize).reason === 'binary'
                                  ? t('filePreview.binaryFile')
                                  : t('filePreview.unsupported')}
                            </p>
                            <Button variant="outline" size="sm" onClick={handleDownloadPreviewFile}>{t('filePreview.downloadFile')}</Button>
                          </div>
                        ) : inlinePreviewContent ? (
                          getFileTypeLabel(selectedFile.filePath) === 'markdown' ? (
                            <MarkdownRenderer
                              content={inlinePreviewContent}
                              onLinkClick={handlePreviewLinkClick}
                              className="[&_h1]:mb-4 [&_h1]:border-0 [&_h1]:pb-0 [&_h1]:text-xl [&_h2]:mt-6 [&_h2]:mb-3 [&_h2]:border-0 [&_h2]:pb-0 [&_h2]:text-lg [&_h3]:mt-5 [&_h3]:text-base [&_li]:text-[13px] [&_p]:my-3 [&_p]:text-[13px] [&_p]:leading-7"
                            />
                          ) : getLanguageForHighlight(selectedFile.filePath) ? (
                            <CodeRenderer code={inlinePreviewContent} language={getLanguageForHighlight(selectedFile.filePath) ?? null} />
                          ) : (
                            <pre className="whitespace-pre-wrap break-words text-[13px] leading-6">
                              <code>{inlinePreviewContent}</code>
                            </pre>
                          )
                        ) : null}
                      </div>
                    </>
                  ) : (
                    <div className="flex flex-1 items-center justify-center p-8 text-center text-sm text-muted-foreground">
                      {t('skillDetail.filesPreviewEmpty', { defaultValue: '从左侧选择一个文件查看内容' })}
                    </div>
                  )}
                </Card>
              </div>
            ) : (
              <Card className="p-8 text-center text-muted-foreground">
                {t('skillDetail.noFiles')}
              </Card>
            )}
          </TabsContent>

          <TabsContent value="versions" className="mt-5">
            {versions && versions.length > 0 ? (
              <div className="space-y-8">
                {versionGroups.map((group) => {
                  const collapsed = collapsedVersionYears.includes(group.year)
                  return (
                    <section key={group.year} className="space-y-3">
                      <div className="flex items-center gap-3">
                        <span className="text-xs font-medium text-muted-foreground">{group.year}</span>
                        <span className="h-px flex-1 bg-border/60" aria-hidden="true" />
                        <button
                          type="button"
                          aria-expanded={!collapsed}
                          aria-label={t(collapsed ? 'common.expand' : 'common.collapse')}
                          onClick={() => toggleVersionYear(group.year)}
                          className="rounded-full p-1 text-muted-foreground transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/70"
                        >
                          {collapsed ? <ChevronDown className="h-4 w-4" aria-hidden="true" /> : <ChevronUp className="h-4 w-4" aria-hidden="true" />}
                        </button>
                      </div>
                      {!collapsed && (
                        <div>
                          {group.versions.map((version, index) => {
                            const changelogBullets = toChangelogBullets(version.changelog)
                            return (
                              <div key={version.id} className="flex gap-4 pb-6 last:pb-0">
                                <div className="w-11 shrink-0 pt-0.5 text-right text-xs font-medium leading-5 text-foreground/70">
                                  <span title={formatLocalDateTime(version.publishedAt, i18n.language)}>
                                    {formatVersionMonthDay(version.publishedAt, i18n.language)}
                                  </span>
                                </div>
                                <div className="relative flex w-3 shrink-0 justify-center">
                                  <span
                                    className={cn(
                                      'mt-1 h-2.5 w-2.5 rounded-full ring-4',
                                      headlineVersion?.version === version.version
                                        ? 'bg-primary ring-primary/15'
                                        : 'bg-muted-foreground/40 ring-muted/60',
                                    )}
                                    aria-hidden="true"
                                  />
                                  {index < group.versions.length - 1 && (
                                    <span className="absolute bottom-[-1.5rem] left-1/2 top-4 w-px -translate-x-1/2 bg-border/70" aria-hidden="true" />
                                  )}
                                </div>
                                <div className="min-w-0 flex-1 space-y-2">
                                  <div className="flex flex-wrap items-center gap-2">
                                    <span className="font-heading text-base font-semibold text-foreground">
                                      {version.syncRevision ? t('review.githubRevisionValue', { count: version.syncRevision }) : `v${version.version}`}
                                    </span>
                                    {version.syncRevision && <span className="max-w-full break-all font-mono text-xs font-normal text-muted-foreground">{version.version}</span>}
                                    {version.status && (
                                      <span className="rounded-full border border-border/60 bg-secondary/40 px-2.5 py-0.5 text-xs text-muted-foreground">
                                        {resolveVersionStatusLabel(version.status)}
                                      </span>
                                    )}
                                    {headlineVersion?.version === version.version && (
                                      <span className="rounded-full bg-primary px-2.5 py-0.5 text-xs text-primary-foreground">
                                        {t(hasPublishedPendingReview && publishedVersion?.version === version.version
                                          ? 'skillDetail.currentPublicVersion'
                                          : 'skillDetail.currentVersion')}
                                      </span>
                                    )}
                                    <div className="ml-auto flex flex-wrap items-center justify-end gap-2">
                                      {skill.canManageLifecycle && canRereleaseVersion(version.status) && (
                                        <>
                                          <Button data-analytics-id="skill.comparison.open"
                                            size="sm"
                                            variant="outline"
                                            onClick={() => handleOpenDiff(version.version)}
                                          >
                                            {t('skillDetail.compareVersions')}
                                          </Button>
                                          <Button data-analytics-id="skill.rerelease.open"
                                            size="sm"
                                            variant="outline"
                                            onClick={() => handleOpenRerelease(version.version)}
                                          >
                                            {t('skillDetail.rereleaseVersion')}
                                          </Button>
                                        </>
                                      )}
                                      {skill.canManageLifecycle && canDeleteVersion(version.status) && !isLastVersion && (
                                        <Button data-analytics-id="skill.version.delete"
                                          size="sm"
                                          variant="outline"
                                          onClick={() => setDeleteVersionTarget(version.version)}
                                        >
                                          {t('skillDetail.deleteVersion')}
                                        </Button>
                                      )}
                                      {skill.canManageLifecycle && canWithdrawVersion(version.status) && (
                                        <Button data-analytics-id="skill.review.withdraw"
                                          size="sm"
                                          variant="outline"
                                          onClick={() => setWithdrawVersionTarget(version.version)}
                                        >
                                          {t('skillDetail.withdrawReview')}
                                        </Button>
                                      )}
                                      {skill.canManageLifecycle && version.status === 'UPLOADED' && skill.visibility === 'PRIVATE' && (
                                        <Button data-analytics-id="skill.version.publish"
                                          size="sm"
                                          variant="outline"
                                          onClick={() => setConfirmPublishTarget(version.version)}
                                        >
                                          {t('skillDetail.confirmPublish')}
                                        </Button>
                                      )}
                                      {skill.canManageLifecycle && version.status === 'UPLOADED' && skill.visibility === 'PRIVATE' && (
                                        <Button data-analytics-id="skill.review.submit"
                                          size="sm"
                                          variant="outline"
                                          onClick={() => setSubmitReviewTarget(version.version)}
                                        >
                                          {t('skillDetail.submitReview')}
                                        </Button>
                                      )}
                                      {version.status === 'PUBLISHED' && version.downloadAvailable && (
                                        <Button
                                          variant="ghost"
                                          size="icon"
                                          className="h-8 w-8"
                                          title={t('skillDetail.download')}
                                          aria-label={t('skillDetail.download')}
                                          onClick={() => downloadVersion(version)}
                                        >
                                          <Download className="h-4 w-4" aria-hidden="true" />
                                        </Button>
                                      )}
                                    </div>
                                  </div>
                                  {changelogBullets.length > 0 && (
                                    <ul className="list-disc space-y-1 pl-5 text-xs leading-6 text-muted-foreground marker:text-muted-foreground/50">
                                      {changelogBullets.map((entry, entryIndex) => (
                                        <li key={`${version.id}-${entryIndex}`}>{entry}</li>
                                      ))}
                                    </ul>
                                  )}
                                  <ComplianceSnapshotPanel snapshot={version.complianceSnapshot} />
                                  <div className="flex items-center gap-3 text-xs text-muted-foreground">
                                    <span>{t('skillDetail.fileCount', { count: version.fileCount })}</span>
                                    <span className="h-1 w-1 rounded-full bg-muted-foreground/40" aria-hidden="true" />
                                    <span>{(version.totalSize / 1024).toFixed(1)} KB</span>
                                  </div>
                                </div>
                              </div>
                            )
                          })}
                        </div>
                      )}
                    </section>
                  )
                })}
              </div>
            ) : (
              <Card className="p-8 text-center text-muted-foreground">{t('skillDetail.noVersions')}</Card>
            )}
          </TabsContent>
        </Tabs>

        <SkillReviews skillId={skill.id} canInteract={canInteract} onRequireLogin={requireLogin} />
      </div>

      {/* Sidebar */}
      <aside className="w-full lg:w-[290px] flex-shrink-0 space-y-3">
        <Card className="space-y-4 p-5">
          <p className="text-center text-sm font-semibold leading-6 text-foreground">
            {t('skillDetail.installHelperText', { defaultValue: '复制安装指引到你的 Agent，即可让它安装当前 Skill' })}
          </p>
          <div className="rounded-full bg-gradient-to-r from-blue-300/70 via-amber-300/70 to-purple-300/70 p-px">
            <div className="[&>button]:rounded-full [&>button]:border-0 [&>button]:bg-card [&>button]:px-4 [&>button]:py-3">
              <InstallForAgentButton analyticsId="skill.prompt.copy"
                namespace={namespace}
                slug={slug}
                version={selectedVersionEntry?.version ?? publishedVersion?.version ?? ''}
                disabled={!selectedVersionEntry || skill.status === 'ARCHIVED' || !isVersionInstallable}
              />
            </div>
          </div>
        </Card>

        <Button data-analytics-id="skill.download.request"
          className="w-full justify-center gap-2 rounded-xl"
          variant="outline"
          size="lg"
          title={t('skillDetail.downloadZip', { defaultValue: '下载 zip 包' })}
          onClick={handleDownload}
          disabled={!selectedVersionEntry || skill.status === 'ARCHIVED' || !isVersionDownloadable}
        >
          <Download className="h-4 w-4" aria-hidden="true" />
          {t('skillDetail.download')}
        </Button>

        <Card className="p-5 space-y-5">
          <div className="flex items-center justify-between">
            <div className="text-sm text-muted-foreground">{t('skillDetail.version')}</div>
            <div className="max-w-[11rem] break-all text-right font-mono font-semibold leading-snug text-foreground">
              {selectedVersionEntry ? selectedVersionEntry.syncRevision != null
                ? t('review.githubRevisionValue', { count: selectedVersionEntry.syncRevision })
                : `v${selectedVersionEntry.version}` : '—'}
            </div>
          </div>

          <div className="h-px bg-border/40" />

          <div className="flex items-center justify-between">
            <div className="text-sm text-muted-foreground">{t('skillDetail.rating')}</div>
            <div className="font-semibold text-foreground">
              {skill.ratingCount > 0 && skill.ratingAvg !== undefined ? `${skill.ratingAvg.toFixed(1)} / 5` : t('skillDetail.ratingNone')}
            </div>
          </div>

          <div className="h-px bg-border/40" />

          <div className="flex items-center justify-between">
            <div className="text-sm text-muted-foreground">{t('skillDetail.namespaceLabel')}</div>
            <NamespaceBadge type="GLOBAL" name={namespace} />
          </div>

          <div className="h-px bg-border/40" />

          <div className="space-y-3">
            {canInteract ? (
              <>
                {!isFetchingSkill ? (
                  <>
                    <SubscribeButton skillId={skill.id} subscriptionCount={(skill as { subscriptionCount?: number }).subscriptionCount ?? 0} onRequireLogin={requireLogin} />
                    <RatingInput skillId={skill.id} onRequireLogin={requireLogin} />
                  </>
                ) : null}
                {REPORTING_ENTRY_AVAILABLE && canReport ? (
                  <Button data-analytics-id="report.create.open" variant="outline" className="w-full" onClick={handleOpenReport} disabled={hasReported || reportMutation.isPending}>
                    {hasReported ? t('skillDetail.reportedSkill') : reportMutation.isPending ? t('skillDetail.processing') : t('skillDetail.reportSkill')}
                  </Button>
                ) : null}
              </>
            ) : (
              <p className="text-sm text-muted-foreground">
                {isRejectedPreview
                  ? t('skillDetail.rejectedPreviewInteractionHint')
                  : t('skillDetail.pendingPreviewInteractionHint')}
              </p>
            )}
            {!user && canInteract && (
              <p className="text-xs text-muted-foreground">{t('skillDetail.loginToRate')}</p>
            )}
          </div>
        </Card>

        <Card className="p-5 space-y-4">
          <div className="flex items-center gap-2">
            <Boxes className="h-4 w-4 text-primary" aria-hidden="true" />
            <span className="text-sm font-semibold font-heading text-foreground">
              {t('skillDetail.suiteMembershipTitle')}
            </span>
          </div>
          <p className="text-sm leading-6 text-muted-foreground">
            {t('skillDetail.suiteMembershipDescription')}
          </p>

          {suiteMemberships.length === 0 ? (
            <p className="rounded-xl bg-muted/50 px-3 py-2.5 text-sm text-muted-foreground">
              {t('skillDetail.suiteMembershipEmpty')}
            </p>
          ) : (
            <div className="divide-y divide-border/60 border-y border-border/60">
              {suiteMemberships.map((suite) => (
                <div key={suite.suiteId} className="min-w-0 py-4 first:pt-3 last:pb-3">
                  <div className="flex min-w-0 items-start justify-between gap-3">
                    <Link data-analytics-id="suite.detail.open"
                      to="/suite/$namespace/$slug"
                      params={{ namespace: suite.namespace, slug: suite.slug }}
                      search={{ version: suite.version }}
                      className="min-w-0 rounded-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/70 focus-visible:ring-offset-2"
                    >
                      <span className="block break-words text-sm font-semibold text-foreground [overflow-wrap:anywhere]">
                        {suite.displayName}
                      </span>
                      <span className="mt-1 block break-all font-mono text-xs text-muted-foreground">
                        @{suite.namespace}/{suite.slug}@{suite.version}
                      </span>
                    </Link>
                    <span className="shrink-0 rounded-full bg-primary/10 px-2 py-1 text-xs font-medium text-primary">
                      {t((suite.currentSkillEntry ?? !skill.memberOfSuites)
                        ? 'skillDetail.suiteMembershipEntryRole'
                        : 'skillDetail.suiteMembershipMemberRole')}
                    </span>
                  </div>

                  {(suite.visibleSiblingMembers?.length ?? 0) > 0 && (
                    <div className="mt-3 space-y-2">
                      <p className="text-xs font-medium text-muted-foreground">
                        {t('skillDetail.suiteMembershipSiblingTitle')}
                      </p>
                      <div className="flex flex-wrap gap-1.5">
                        {(suite.visibleSiblingMembers ?? []).map((member) => member.available ? (
                          <Link data-analytics-id="skill.related.open"
                            key={member.skillId}
                            to="/space/$namespace/$slug"
                            params={{ namespace: member.namespace, slug: member.slug }}
                            search={{ returnTo: undefined }}
                            className="max-w-full rounded-full bg-muted px-2.5 py-1 text-xs text-foreground transition-colors hover:bg-muted/70 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/70 focus-visible:ring-offset-2"
                          >
                            <span className="block max-w-[13rem] truncate">{member.displayName}</span>
                          </Link>
                        ) : (
                          <span
                            key={member.skillId}
                            className="max-w-full rounded-full bg-muted px-2.5 py-1 text-xs text-muted-foreground"
                            title={t('skillDetail.suiteMembershipUnavailable')}
                          >
                            <span className="block max-w-[13rem] truncate">{member.displayName}</span>
                          </span>
                        ))}
                      </div>
                    </div>
                  )}

                  <div className="mt-3 flex flex-wrap gap-x-3 gap-y-1 text-xs text-muted-foreground">
                    <span>{t('skillDetail.suiteEntryMemberCount', { count: suite.memberCount })}</span>
                    {(suite.restrictedMemberCount ?? 0) > 0 && (
                      <span>{t('skillDetail.suiteMembershipRestricted', { count: suite.restrictedMemberCount ?? 0 })}</span>
                    )}
                    {(suite.omittedVisibleMemberCount ?? 0) > 0 && (
                      <span>{t('skillDetail.suiteMembershipOmitted', { count: suite.omittedVisibleMemberCount ?? 0 })}</span>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}

          {suiteMembershipTotal > suiteMemberships.length && (
            <p className="text-xs leading-5 text-muted-foreground">
              {t('skillDetail.suiteMembershipMore', {
                shown: suiteMemberships.length,
                total: suiteMembershipTotal,
              })}
            </p>
          )}
        </Card>

        {hasPublishedPendingReview && ownerPreviewVersion && (
          <Card className="border-amber-500/30 bg-amber-500/5 p-5 space-y-4">
            <div className="space-y-1">
              <div className="flex items-center gap-2">
                <Clock className="w-4 h-4 text-amber-600" />
                <span className="text-sm font-semibold font-heading text-foreground">{t('skillDetail.pendingReviewSectionTitle')}</span>
              </div>
              <p className="text-sm text-muted-foreground">
                {t('skillDetail.pendingReviewSectionDescription', {
                  pendingVersion: ownerPreviewVersion.version,
                  publishedVersion: publishedVersion?.version ?? '',
                })}
              </p>
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="rounded-xl border border-amber-500/20 bg-background/70 p-3">
                <div className="text-xs uppercase tracking-[0.18em] text-muted-foreground">
                  {t('skillDetail.pendingReviewVersionLabel')}
                </div>
                <div className="mt-2 break-all font-mono text-sm font-semibold leading-snug text-foreground">
                  v{ownerPreviewVersion.version}
                </div>
              </div>
              <div className="rounded-xl border border-amber-500/20 bg-background/70 p-3">
                <div className="text-xs uppercase tracking-[0.18em] text-muted-foreground">
                  {t('skillDetail.pendingReviewStatusLabel')}
                </div>
                <div className="mt-2 text-sm font-semibold text-amber-700">
                  {t('skillDetail.pendingReviewStatusValue')}
                </div>
              </div>
            </div>
            <Button data-analytics-id="skill.review.withdraw"
              variant="outline"
              onClick={() => setWithdrawVersionTarget(ownerPreviewVersion.version)}
              disabled={!skill.canManageLifecycle || withdrawReviewMutation.isPending}
            >
              {withdrawReviewMutation.isPending ? t('skillDetail.processing') : t('skillDetail.withdrawReview')}
            </Button>
          </Card>
        )}

        {canManageSecurityScan && securityAuditVersion && (
          <SecurityAuditSummary
            skillId={skill.id}
            versionId={securityAuditVersion.id}
            versionStatus={securityAuditVersion.status}
            canRetry
          />
        )}

        {!(canManageSecurityScan && securityAuditVersion) && <SecurityEvaluationNotice />}

        <SkillLabelPanel
          namespace={namespace}
          slug={slug}
          initialLabels={skill.labels ?? []}
          canManage={canManageLabels}
          isSuperAdmin={hasRole('SUPER_ADMIN')}
        />

        {skill.githubSource && publishedVersion && (
          <GithubSkillSourceCard source={skill.githubSource} version={publishedVersion.version} />
        )}

        {skill.canManageLifecycle && (
          <Card className="p-5 space-y-3">
            <div className="flex items-center gap-2">
              <RefreshCw className="w-4 h-4 text-muted-foreground" />
              <span className="text-sm font-semibold font-heading text-foreground">{t('skillDetail.lifecycle')}</span>
            </div>
            <p className="text-sm text-muted-foreground">
              {skill.status === 'ARCHIVED'
                ? t('skillDetail.archivedPublishHint')
                : t('skillDetail.lifecycleHint')}
            </p>
            <div className="space-y-3">
              <div className="rounded-xl border border-border/60 bg-secondary/20 p-3">
                <div className="text-xs uppercase tracking-[0.18em] text-muted-foreground">
                  {t('skillDetail.lifecyclePublicVersionLabel')}
                </div>
                <div className="mt-2 text-sm font-semibold text-foreground">
                  {publishedVersion ? `v${publishedVersion.version}` : t('skillDetail.lifecycleNoPublishedVersion')}
                </div>
              </div>
              <div className="rounded-xl border border-border/60 bg-secondary/20 p-3">
                <div className="text-xs uppercase tracking-[0.18em] text-muted-foreground">
                  {t('skillDetail.lifecyclePendingVersionLabel')}
                </div>
                <div className="mt-2 text-sm font-semibold text-foreground">
                  {hasPendingOwnerPreview && ownerPreviewVersion
                    ? t('skillDetail.lifecyclePendingVersionValue', { version: ownerPreviewVersion.version })
                    : t('skillDetail.lifecycleNoPendingVersion')}
                </div>
              </div>
              <div className="rounded-xl border border-border/60 bg-secondary/20 p-3">
                <div className="text-xs uppercase tracking-[0.18em] text-muted-foreground">
                  {t('skillDetail.lifecycleContainerStateLabel')}
                </div>
                <div className="mt-2 text-sm font-semibold text-foreground">
                  {resolveSkillStatusLabel(skill.status)}
                </div>
              </div>
            </div>
            <div className="flex flex-col gap-3 pt-3 border-t border-border/40">
              {skill.status === 'ACTIVE' && !skill.hidden && publishedVersion && <SkillVisibilityDialog
                namespace={namespace} slug={slug} visibility={skill.visibility} disabled={hasPendingOwnerPreview} />}

              {skill.status === 'ARCHIVED' ? (
                <Button data-analytics-id="skill.unarchive.request" variant="outline" onClick={() => setUnarchiveConfirmOpen(true)} disabled={unarchiveMutation.isPending}>
                  {unarchiveMutation.isPending ? t('skillDetail.processing') : t('skillDetail.unarchiveSkill')}
                </Button>
              ) : publishedVersion ? (
                <Button data-analytics-id="skill.archive.request" variant="outline" onClick={() => setArchiveConfirmOpen(true)} disabled={archiveMutation.isPending}>
                  {archiveMutation.isPending ? t('skillDetail.processing') : t('skillDetail.archiveSkill')}
                </Button>
              ) : null}
              {canHardDeleteSkill && (
                <Button data-analytics-id="skill.delete.request"
                  variant="destructive"
                  onClick={() => setDeleteSkillConfirmOpen(true)}
                  disabled={deleteSkillMutation.isPending}
                >
                  {deleteSkillMutation.isPending ? t('skillDetail.processing') : t('skillDetail.deleteSkill')}
                </Button>
              )}
            </div>
          </Card>
        )}

        {namespace !== 'global' && (skill.canManageLifecycle || skill.canSubmitPromotion || isPromotionAdmin)
          && (publishedVersions.length > 0 || Boolean(promotionState?.targetSkillId) || Boolean(revocationHistory?.length)) && (
          <Card className="p-5 space-y-3">
            <div className="flex items-center gap-2">
              <ArrowUpCircle className="w-4 h-4 text-muted-foreground" />
              <span className="text-sm font-semibold font-heading text-foreground">{t('skillDetail.promotionSectionTitle')}</span>
            </div>
            {isLoadingPromotionState && <p className="text-sm text-muted-foreground">{t('skillDetail.promotionStateLoading')}</p>}
            {promotionStateError && <p className="text-sm text-destructive">{t('skillDetail.promotionStateError')}</p>}
            {promotionState && (
              <>
                <p className="text-sm text-muted-foreground">
                  {promotionState.targetSkillId
                    ? t('skillDetail.promotionGlobalCurrent', { version: promotionState.targetCurrentVersion ?? t('skillDetail.promotionNoGlobalVersion') })
                    : t('skillDetail.promotionNoGlobalSkill')}
                </p>
                {publishedVersions.length > 0 && (
                  <>
                    <label htmlFor="promotion-source-version" className="block text-sm font-medium text-foreground">{t('skillDetail.promotionSourceVersion')}</label>
                    <Select value={String(promotionVersion?.id ?? '')} onValueChange={setPromotionVersionId}>
                      <SelectTrigger data-analytics-id="skill.promotion.version.interact" id="promotion-source-version" aria-label={t('skillDetail.promotionSourceVersion')}>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        {publishedVersions.map((version) => (
                          <SelectItem data-analytics-id="skill.promotion.version" key={version.id} value={String(version.id)}>v{version.version}</SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </>
                )}
                {promotionState.pendingPromotionId ? (
                  <p className="text-sm text-muted-foreground">{t('skillDetail.promotionPending')}</p>
                ) : promotionVersion ? (
                  <Button data-analytics-id={promotionState.requestKind === 'UPDATE' ? 'skill.promotion.update.request' : 'skill.promotion.create.request'} variant="outline" onClick={() => setPromotionConfirmOpen(true)} disabled={!(skill.canSubmitPromotion || isPromotionAdmin) || Boolean(pendingRevocation) || submitPromotionMutation.isPending || !promotionVersion}>
                    {submitPromotionMutation.isPending ? t('skillDetail.processing') : t(promotionState.requestKind === 'UPDATE' ? 'skillDetail.submitGlobalUpdate' : 'skillDetail.promoteToGlobal')}
                  </Button>
                ) : null}
                {promotionState.targetSkillId && (
                  <div className="space-y-2 border-t border-border/60 pt-3">
                    <p className="text-sm text-muted-foreground">{t('skillDetail.revocationDescription')}</p>
                    {isLoadingRevocationHistory && <p className="text-sm text-muted-foreground">{t('skillDetail.revocationHistoryLoading')}</p>}
                    {revocationHistoryError && <p className="text-sm text-destructive">{t('skillDetail.revocationHistoryError')}</p>}
                    {pendingRevocation ? (
                      <p className="text-sm text-muted-foreground">{t('skillDetail.revocationPending')}</p>
                    ) : !isLoadingRevocationHistory && !revocationHistoryError && (
                      <div className="flex flex-wrap gap-2">
                        <Button data-analytics-id="skill.revocation.request" variant="outline" onClick={() => setRevocationMode('request')}>
                          {t('skillDetail.requestRevocation')}
                        </Button>
                        {isPromotionAdmin && (
                          <Button data-analytics-id="skill.revocation.direct" variant="destructive" onClick={() => setRevocationMode('direct')}>
                            {t('skillDetail.directRevocation')}
                          </Button>
                        )}
                      </div>
                    )}
                  </div>
                )}
              </>
            )}
            {revocationHistory && revocationHistory.length > 0 && (
              <div className="space-y-1 border-t border-border/60 pt-3 text-sm text-muted-foreground">
                <p className="font-medium text-foreground">{t('skillDetail.revocationHistoryTitle')}</p>
                {revocationHistory.map((request) => (
                  <p key={request.id}>
                    {t('skillDetail.revocationHistoryItem', {
                      status: t(`skillDetail.revocationStatus.${request.status}`),
                      date: formatLocalDateTime(request.submittedAt, i18n.language),
                    })}
                  </p>
                ))}
              </div>
            )}
          </Card>
        )}

        {governanceVisible && (
          <Card className="p-5 space-y-3">
            <div className="flex items-center gap-2">
              <ShieldCheck className="w-4 h-4 text-muted-foreground" />
              <span className="text-sm font-semibold font-heading text-foreground">{t('skillDetail.governance')}</span>
            </div>
            <div className="flex flex-col gap-3">
              {canHideSkill ? (
                !skill.hidden ? (
                  <Button data-analytics-id="skill.hide.request" variant="outline" onClick={() => hideMutation.mutate()} disabled={hideMutation.isPending}>
                    {hideMutation.isPending ? t('skillDetail.processing') : t('skillDetail.hideSkill')}
                  </Button>
                ) : (
                  <Button data-analytics-id="skill.unhide.request" variant="outline" onClick={() => unhideMutation.mutate()} disabled={unhideMutation.isPending}>
                    {unhideMutation.isPending ? t('skillDetail.processing') : t('skillDetail.unhideSkill')}
                  </Button>
                )
              ) : null}
              {selectedVersionEntry && (
                <Button data-analytics-id="skill.version.yank" variant="destructive" onClick={() => yankMutation.mutate()} disabled={yankMutation.isPending}>
                  {yankMutation.isPending ? t('skillDetail.processing') : t('skillDetail.yankVersion')}
                </Button>
              )}
            </div>
          </Card>
        )}
      </aside>

      <Dialog open={reportDialogOpen} onOpenChange={setReportDialogOpen}>
        <DialogContent closeAnalyticsId="report.create.cancel">
          <DialogHeader>
            <DialogTitle>{t('skillDetail.reportDialogTitle')}</DialogTitle>
            <DialogDescription>{t('skillDetail.reportDialogDescription')}</DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            <Input
              value={reportReason}
              onChange={(event) => setReportReason(event.target.value)}
              placeholder={t('skillDetail.reportReasonPlaceholder')}
              maxLength={200}
            />
            <Textarea
              value={reportDetails}
              onChange={(event) => setReportDetails(event.target.value)}
              placeholder={t('skillDetail.reportDetailsPlaceholder')}
              rows={5}
            />
          </div>
          <DialogFooter>
            <Button data-analytics-id="report.create.cancel" variant="outline" onClick={() => setReportDialogOpen(false)}>
              {t('dialog.cancel')}
            </Button>
            <Button data-analytics-id="report.create.request" onClick={handleSubmitReport} disabled={reportMutation.isPending}>
              {reportMutation.isPending ? t('skillDetail.processing') : t('skillDetail.submitReport')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <ConfirmDialog confirmAnalyticsId={promotionState?.requestKind === 'UPDATE' ? 'skill.promotion.update.confirm' : 'skill.promotion.create.confirm'} cancelAnalyticsId={promotionState?.requestKind === 'UPDATE' ? 'skill.promotion.update.cancel' : 'skill.promotion.create.cancel'}
        open={promotionConfirmOpen}
        onOpenChange={setPromotionConfirmOpen}
        title={t(promotionState?.requestKind === 'UPDATE' ? 'skillDetail.globalUpdateConfirmTitle' : 'skillDetail.promotionConfirmTitle')}
        description={promotionState?.requestKind === 'UPDATE'
          ? t('skillDetail.globalUpdateConfirmDescription', {
            skill: skill.displayName,
            source: promotionVersion?.version ?? '',
            target: promotionState.targetCurrentVersion ?? t('skillDetail.promotionNoGlobalVersion'),
          })
          : t('skillDetail.promotionConfirmDescription', {
            skill: skill.displayName,
            version: promotionVersion?.version ?? '',
          })}
        confirmText={t(promotionState?.requestKind === 'UPDATE' ? 'skillDetail.submitGlobalUpdate' : 'skillDetail.promoteToGlobal')}
        onConfirm={handleSubmitPromotion}
      />

      <Dialog open={revocationMode !== null} onOpenChange={(open) => { if (!open) setRevocationMode(null) }}>
        <DialogContent closeAnalyticsId={revocationMode === 'direct' ? 'skill.revocation.direct.cancel' : 'skill.revocation.request.cancel'} aria-label={t('skillDetail.revocationConfirmTitle')}>
          <DialogHeader>
            <DialogTitle>{t('skillDetail.revocationConfirmTitle')}</DialogTitle>
            <DialogDescription>{t('skillDetail.revocationConfirmDescription', { skill: skill.displayName, slug: skill.slug })}</DialogDescription>
          </DialogHeader>
          <Textarea
            aria-label={t('skillDetail.revocationReasonLabel')}
            placeholder={t('skillDetail.revocationReasonPlaceholder')}
            value={revocationReason}
            onChange={(event) => setRevocationReason(event.target.value)}
            rows={3}
          />
          <DialogFooter>
            <Button data-analytics-id={revocationMode === 'direct' ? 'skill.revocation.direct.cancel' : 'skill.revocation.request.cancel'} variant="outline" onClick={() => setRevocationMode(null)}>{t('dialog.cancel')}</Button>
            <Button data-analytics-id={revocationMode === 'direct' ? 'skill.revocation.direct.submit' : 'skill.revocation.request.submit'} variant="destructive" onClick={handleSubmitRevocation} disabled={submitRevocationMutation.isPending || directRevocationMutation.isPending}>
              {t(revocationMode === 'direct' ? 'skillDetail.directRevocation' : 'skillDetail.requestRevocation')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <ConfirmDialog confirmAnalyticsId="skill.archive.confirm" cancelAnalyticsId="skill.archive.cancel"
        open={archiveConfirmOpen}
        onOpenChange={setArchiveConfirmOpen}
        title={t('skillDetail.archiveConfirmTitle')}
        description={t('skillDetail.archiveConfirmDescription', { skill: skill.displayName })}
        confirmText={t('skillDetail.archiveSkill')}
        onConfirm={handleArchive}
      />

      <ConfirmDialog confirmAnalyticsId="skill.unarchive.confirm" cancelAnalyticsId="skill.unarchive.cancel"
        open={unarchiveConfirmOpen}
        onOpenChange={setUnarchiveConfirmOpen}
        title={t('skillDetail.unarchiveConfirmTitle')}
        description={t('skillDetail.unarchiveConfirmDescription', { skill: skill.displayName })}
        confirmText={t('skillDetail.unarchiveSkill')}
        onConfirm={handleUnarchive}
      />

      <ConfirmDialog confirmAnalyticsId="skill.delete.continue" cancelAnalyticsId="skill.delete.continue.cancel"
        open={deleteSkillConfirmOpen}
        onOpenChange={setDeleteSkillConfirmOpen}
        title={t('skillDetail.deleteSkillConfirmTitle')}
        description={t('skillDetail.deleteSkillConfirmDescription', { skill: skill.displayName })}
        confirmText={t('skillDetail.deleteSkillContinue')}
        variant="destructive"
        onConfirm={handleOpenDeleteSkillInput}
      />

      <Dialog
        open={deleteSkillInputOpen}
        onOpenChange={(open) => {
          setDeleteSkillInputOpen(open)
          if (!open) {
            setDeleteSkillInput('')
          }
        }}
      >
        <DialogContent closeAnalyticsId="skill.delete.cancel">
          <DialogHeader>
            <DialogTitle>{t('skillDetail.deleteSkillInputTitle')}</DialogTitle>
            <DialogDescription>{t('skillDetail.deleteSkillInputDescription', { slug: skill.slug })}</DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <div className="rounded-lg border border-destructive/20 bg-destructive/5 p-3 text-sm text-muted-foreground">
              {t('skillDetail.deleteSkillWarning')}
            </div>
            <Input
              value={deleteSkillInput}
              onChange={(event) => setDeleteSkillInput(event.target.value)}
              placeholder={t('skillDetail.deleteSkillInputPlaceholder')}
            />
          </div>
          <DialogFooter>
            <Button data-analytics-id="skill.delete.cancel" variant="outline" onClick={() => setDeleteSkillInputOpen(false)}>
              {t('dialog.cancel')}
            </Button>
            <Button data-analytics-id="skill.delete.confirm"
              variant="destructive"
              onClick={handleDeleteSkill}
              disabled={!isDeleteSlugConfirmationValid(skill.slug, deleteSkillInput) || deleteSkillMutation.isPending}
            >
              {deleteSkillMutation.isPending ? t('skillDetail.processing') : t('skillDetail.deleteSkillFinal')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <ConfirmDialog confirmAnalyticsId="skill.version.delete.confirm" cancelAnalyticsId="skill.version.delete.cancel"
        open={!!deleteVersionTarget}
        onOpenChange={(open) => {
          if (!open) {
            setDeleteVersionTarget(null)
          }
        }}
        title={t('skillDetail.deleteVersionConfirmTitle')}
        description={deleteVersionTarget ? t('skillDetail.deleteVersionConfirmDescription', { version: deleteVersionTarget }) : ''}
        confirmText={t('skillDetail.deleteVersion')}
        variant="destructive"
        onConfirm={handleDeleteVersion}
      />

      <ConfirmDialog confirmAnalyticsId="skill.review.withdraw.confirm" cancelAnalyticsId="skill.review.withdraw.cancel"
        open={!!withdrawVersionTarget}
        onOpenChange={(open) => {
          if (!open) {
            setWithdrawVersionTarget(null)
          }
        }}
        title={t('skillDetail.withdrawReviewConfirmTitle')}
        description={withdrawVersionTarget ? t('skillDetail.withdrawReviewConfirmDescription', { version: withdrawVersionTarget }) : ''}
        confirmText={t('skillDetail.withdrawReview')}
        onConfirm={handleWithdrawVersion}
      />

      <Dialog
        open={!!rereleaseTarget}
        onOpenChange={(open) => {
          if (!open) {
            setRereleaseTarget(null)
            setTargetVersionInput('')
            setRereleaseWarnings([])
            setRereleaseWarningDialogOpen(false)
          }
        }}
      >
        <DialogContent closeAnalyticsId="skill.rerelease.cancel">
          <DialogHeader>
            <DialogTitle>{t('skillDetail.rereleaseDialogTitle')}</DialogTitle>
            <DialogDescription>
              {rereleaseTarget ? t('skillDetail.rereleaseDialogDescription', { version: rereleaseTarget }) : ''}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            <div className="space-y-2">
              <div className="text-sm text-muted-foreground">{t('skillDetail.rereleaseSourceVersion')}</div>
              <div className="rounded-lg border border-border/60 bg-secondary/30 px-3 py-2 font-mono text-sm text-foreground">
                {rereleaseTarget ? `v${rereleaseTarget}` : '—'}
              </div>
            </div>
            <div className="space-y-2">
              <div className="text-sm text-muted-foreground">{t('skillDetail.rereleaseTargetVersion')}</div>
              <Input value={targetVersionInput} onChange={(event) => setTargetVersionInput(event.target.value)} />
            </div>
          </div>
          <DialogFooter>
            <Button data-analytics-id="skill.rerelease.cancel" variant="outline" onClick={() => setRereleaseTarget(null)}>
              {t('dialog.cancel')}
            </Button>
            <Button data-analytics-id="skill.rerelease.request" onClick={() => handleRereleaseVersion()} disabled={rereleaseVersionMutation.isPending || !targetVersionInput.trim()}>
              {rereleaseVersionMutation.isPending ? t('skillDetail.processing') : t('skillDetail.rereleaseVersion')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <ConfirmDialog confirmAnalyticsId="skill.rerelease.confirm" cancelAnalyticsId="skill.rerelease.cancel"
        open={rereleaseWarningDialogOpen}
        onOpenChange={setRereleaseWarningDialogOpen}
        title={t('skillDetail.rereleaseWarningTitle')}
        description={
          <div className="space-y-2">
            <p>{t('skillDetail.rereleaseWarningDescription')}</p>
            <ul className="list-disc space-y-1 pl-5 text-sm">
              {rereleaseWarnings.map((warning, index) => (
                <li key={index}>{warning}</li>
              ))}
            </ul>
          </div>
        }
        confirmText={t('skillDetail.rereleaseWarningConfirm')}
        onConfirm={() => {
          setRereleaseWarningDialogOpen(false)
          handleRereleaseVersion(true)
        }}
      />

      <ConfirmDialog confirmAnalyticsId="skill.version.publish.confirm" cancelAnalyticsId="skill.version.publish.cancel"
        open={!!confirmPublishTarget}
        onOpenChange={(open) => {
          if (!open) {
            setConfirmPublishTarget(null)
          }
        }}
        title={t('skillDetail.confirmPublishDialogTitle')}
        description={confirmPublishTarget ? t('skillDetail.confirmPublishDialogDescription', { version: confirmPublishTarget }) : ''}
        confirmText={t('skillDetail.confirmPublish')}
        onConfirm={handleConfirmPublish}
      />

      <ConfirmDialog confirmAnalyticsId="skill.review.submit.confirm" cancelAnalyticsId="skill.review.submit.cancel"
        open={!!submitReviewTarget}
        onOpenChange={(open) => {
          if (!open) {
            setSubmitReviewTarget(null)
          }
        }}
        title={t('skillDetail.submitReviewDialogTitle')}
        description={submitReviewTarget ? t('skillDetail.submitReviewDialogDescription', { version: submitReviewTarget }) : ''}
        confirmText={t('skillDetail.submitReview')}
        onConfirm={handleSubmitForReview}
      />

      <Dialog
        open={!!diffSourceVersion && !!diffCompareVersion}
        onOpenChange={(open) => {
          if (!open) {
            setDiffSourceVersion(null)
            setDiffCompareVersion(null)
          }
        }}
      >
        <DialogContent closeAnalyticsId="skill.comparison.close">
          <DialogHeader>
            <DialogTitle>{t('skillDetail.compareDialogTitle')}</DialogTitle>
            <DialogDescription>
              {diffSourceVersion && diffCompareVersion
                ? t('skillDetail.compareDialogDescription', { source: diffSourceVersion, target: diffCompareVersion })
                : ''}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-5">
            <div className="grid grid-cols-2 gap-4 text-sm">
              <div className="rounded-lg border border-border/60 p-3">
                <div className="text-muted-foreground">{t('skillDetail.compareSourceLabel')}</div>
                <div className="mt-1 font-mono text-foreground">v{diffSourceVersion}</div>
              </div>
              <div className="rounded-lg border border-border/60 p-3">
                <div className="text-muted-foreground">{t('skillDetail.compareTargetLabel')}</div>
                <div className="mt-1 font-mono text-foreground">v{diffCompareVersion}</div>
              </div>
            </div>

            <div className="space-y-2">
              <div className="text-sm font-semibold text-foreground">{t('skillDetail.metadataChanges')}</div>
              {metadataDiffEntries.length > 0 ? (
                <div className="space-y-2">
                  {metadataDiffEntries.map((entry) => (
                    <div key={entry.key} className="rounded-lg border border-border/60 p-3 text-sm">
                      <div className="font-medium text-foreground">{entry.key}</div>
                      <div className="mt-1 text-muted-foreground">
                        {String(entry.source ?? '—')} → {String(entry.target ?? '—')}
                      </div>
                    </div>
                  ))}
                </div>
              ) : (
                <div className="text-sm text-muted-foreground">{t('skillDetail.noMetadataChanges')}</div>
              )}
            </div>

            <div className="space-y-2">
              <div className="text-sm font-semibold text-foreground">{t('skillDetail.readmeChange')}</div>
              <div className="text-sm text-muted-foreground">
                {diffSourceReadme !== diffCompareReadme ? t('skillDetail.readmeChanged') : t('skillDetail.readmeUnchanged')}
              </div>
            </div>

            <div className="space-y-2">
              <div className="text-sm font-semibold text-foreground">{t('skillDetail.fileChanges')}</div>
              <div className="grid grid-cols-3 gap-3 text-sm">
                <div className="rounded-lg border border-border/60 p-3">
                  <div className="text-muted-foreground">{t('skillDetail.filesAdded')}</div>
                  <div className="mt-1 font-semibold text-foreground">{fileDiffSummary.added.length}</div>
                </div>
                <div className="rounded-lg border border-border/60 p-3">
                  <div className="text-muted-foreground">{t('skillDetail.filesRemoved')}</div>
                  <div className="mt-1 font-semibold text-foreground">{fileDiffSummary.removed.length}</div>
                </div>
                <div className="rounded-lg border border-border/60 p-3">
                  <div className="text-muted-foreground">{t('skillDetail.filesChanged')}</div>
                  <div className="mt-1 font-semibold text-foreground">{fileDiffSummary.changed.length}</div>
                </div>
              </div>
            </div>
          </div>
          <DialogFooter>
            <Button data-analytics-id="skill.comparison.close"
              variant="outline"
              onClick={() => {
                setDiffSourceVersion(null)
                setDiffCompareVersion(null)
              }}
            >
              {t('dialog.close')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* File preview dialog */}
      <FilePreviewDialog closeAnalyticsId="skill.file.close" downloadAnalyticsId="skill.file.download"
        open={previewDialogOpen}
        onOpenChange={setPreviewDialogOpen}
        node={previewNode}
        content={previewContent || null}
        isLoading={isLoadingPreview}
        error={previewError}
        onDownload={handleDownloadFile}
        onLinkClick={handlePreviewLinkClick} copyAnalyticsId="skill.file.copy"
      />
    </div>
  )
}
