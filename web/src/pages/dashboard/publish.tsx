import { useEffect, useId, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useSearch } from '@tanstack/react-router'
import { useTranslation } from 'react-i18next'
import { UploadZone } from '@/features/publish/upload-zone'
import { packageFolderAsZip } from '@/features/publish/folder-zip'
import {
  extractPrecheckWarnings,
  isFrontmatterFailureMessage,
  isPrecheckConfirmationMessage,
  isPrecheckFailureMessage,
  isVersionExistsMessage,
} from '@/features/publish/publish-error-utils'
import { normalizePublishPrefill } from '@/features/publish/publish-prefill'
import { publishNoticeCopyKeys, publishResultCopyKeys } from '@/features/publish/publish-result'
import { FieldLabel } from '@/features/publish/field-label'
import { PublishSourceSwitch, type PublishSource } from '@/features/publish/source-switch'
import { GitcodeImportWizard } from '@/features/gitcode/gitcode-import-wizard'
import { Button } from '@/shared/ui/button'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  normalizeSelectValue,
} from '@/shared/ui/select'
import { Input } from '@/shared/ui/input'
import { Textarea } from '@/shared/ui/textarea'
import { SkillAvatarEditor, KEEP_AVATAR, type AvatarEdit } from '@/features/skill/skill-avatar-editor'
import { SkillAvatarManager } from '@/features/skill/skill-avatar-manager'
import { previewSkillPackage, usePublishSkill, useSkillDetail } from '@/shared/hooks/use-skill-queries'
import { getSkillDetailQueryKey } from '@/shared/hooks/query-keys'
import { useMyNamespaces } from '@/shared/hooks/use-namespace-queries'
import { ConfirmDialog } from '@/shared/components/confirm-dialog'
import { DashboardPageHeader } from '@/shared/components/dashboard-page-header'
import { navigateAfterOverlays } from '@/shared/lib/navigate-after-overlays'
import { toast } from '@/shared/lib/toast'
import { refreshSkillPermissionsAfterForbidden } from '@/features/skill/refresh-skill-permissions'
import { ApiError, skillAvatarApi } from '@/api/client'

const EMPTY_NAMESPACE_VALUE = '__select_namespace__'

/** Hairline used by the design to separate the upload / basic-info / avatar blocks. */
function SectionDivider() {
  return <hr className="border-0 border-t border-border/60" />
}

export function PublishPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const search = useSearch({ from: '/dashboard/publish' })
  const prefill = normalizePublishPrefill(search)
  const updateSlug = typeof search.updateSkill === 'string' ? search.updateSkill : prefill.resubmitSkill
  const isUpdate = !!search.updateSkill && !prefill.resubmitSkill
  const queryClient = useQueryClient()
  const submitting = useRef(false)
  const [isSavingAvatar, setIsSavingAvatar] = useState(false)
  const [avatarEdit, setAvatarEdit] = useState<AvatarEdit>(KEEP_AVATAR)
  const previewId = useId()
  const [fileRevision, setFileRevision] = useState(0)
  const [displayName, setDisplayName] = useState('')
  const [changelog, setChangelog] = useState('')
  const displayNameEdited = useRef(false)
  const [selectedFile, setSelectedFile] = useState<File | null>(null)
  const [namespaceSlug, setNamespaceSlug] = useState<string>(prefill.namespace)
  const [visibility, setVisibility] = useState<string>(prefill.visibility)
  const [warningDialogOpen, setWarningDialogOpen] = useState(false)
  const [precheckWarnings, setPrecheckWarnings] = useState<string[]>([])
  const [isPackaging, setIsPackaging] = useState(false)
  // Publishing source: the local upload keeps its own state, the wizard keeps its own. Switching
  // only changes the visible panel, so neither flow is reset by the other.
  const [source, setSource] = useState<PublishSource>('local')

  const { data: namespaces, isLoading: isLoadingNamespaces } = useMyNamespaces()
  const publishMutation = usePublishSkill()
  const { data: updateTarget } = useSkillDetail(namespaceSlug, updateSlug ?? '', !!namespaceSlug && !!updateSlug)
  const ownerReadOnly = updateTarget?.ownerReadOnly === true
  const packagePreview = useQuery({
    queryKey: ['skill-package-preview', previewId, fileRevision],
    queryFn: () => previewSkillPackage(selectedFile!),
    enabled: selectedFile !== null,
    retry: false,
    gcTime: 0,
  })
  useEffect(() => {
    if (packagePreview.data && !displayNameEdited.current) {
      setDisplayName(packagePreview.data.displayName ?? packagePreview.data.name ?? '')
    }
  }, [packagePreview.data])
  const avatarTarget = useQuery({ queryKey: ['skill-avatar', namespaceSlug, updateSlug], queryFn: () => skillAvatarApi.status(namespaceSlug, updateSlug), enabled: !!namespaceSlug && !!updateSlug })
  const avatarUnavailable = !!updateSlug && (avatarTarget.isLoading || avatarTarget.isError)
  const isBusy = publishMutation.isPending || isSavingAvatar
  const avatarOnly = isUpdate && !selectedFile
  const canSubmit = !ownerReadOnly && (!selectedFile || packagePreview.isSuccess) && displayName.trim().length <= 200 && !!namespaceSlug && avatarEdit.ready && !avatarUnavailable && !isBusy && (selectedFile !== null || (isUpdate && avatarEdit.action !== 'KEEP'))
  const selectedNamespace = namespaces?.find((ns) => ns.slug === namespaceSlug)
  const namespaceOnlyLabel = selectedNamespace?.type === 'GLOBAL'
    ? t('publish.visibilityOptions.loggedInUsersOnly')
    : t('publish.visibilityOptions.namespaceOnly')

  useEffect(() => {
    setNamespaceSlug(prefill.namespace)
    setVisibility(prefill.visibility)
    setAvatarEdit(KEEP_AVATAR)
    setChangelog('')
  }, [prefill.namespace, prefill.visibility, updateSlug])

  const handleRemoveSelectedFile = () => {
    setSelectedFile(null)
    setPrecheckWarnings([])
    setWarningDialogOpen(false)
  }

  const handleFileSelect = (file: File | null) => {
    setSelectedFile(file)
    setFileRevision(value => value + 1)
    setPrecheckWarnings([])
    setWarningDialogOpen(false)
  }

  const handleFolderSelect = async (files: File[]) => {
    setIsPackaging(true)
    try {
      const zip = await packageFolderAsZip(files)
      handleFileSelect(zip)
    } catch {
      toast.error(t('publish.folderPackagingFailed'))
    } finally {
      setIsPackaging(false)
    }
  }

  const publishSkill = async (confirmWarnings = false) => {
    if (submitting.current) return
    if (!canSubmit) {
      toast.error(t(ownerReadOnly ? 'mySkills.ownerReadOnly' : 'publish.selectRequired'))
      return
    }

    submitting.current = true
    try {
      if (avatarOnly) {
        setIsSavingAvatar(true)
        if (avatarEdit.action === 'REMOVE') await skillAvatarApi.delete(namespaceSlug, updateSlug)
        else await skillAvatarApi.submit(namespaceSlug, updateSlug, avatarEdit.uploadId!)
        await Promise.all([
          queryClient.invalidateQueries({ queryKey: ['skills'], refetchType: avatarEdit.action === 'REMOVE' ? 'all' : 'active' }),
          queryClient.invalidateQueries({ queryKey: ['skill-avatar', namespaceSlug, updateSlug] }),
          queryClient.invalidateQueries({ queryKey: ['reviews'] }),
        ])
        toast.success(t('publish.avatarSaved'), t(avatarEdit.action === 'REMOVE' ? 'skillAvatar.removedNotice' : 'skillAvatar.submittedNotice'))
        navigateAfterOverlays(() => { navigate({ to: '/dashboard/skills' }) })
        return
      }
      const result = await publishMutation.mutateAsync({
        namespace: namespaceSlug,
        file: selectedFile!,
        visibility,
        confirmWarnings,
        avatarAction: avatarEdit.action,
        avatarUploadId: avatarEdit.uploadId,
        expectedSlug: updateSlug || undefined,
        displayName,
        changelog,
      })
      setPrecheckWarnings([])
      setWarningDialogOpen(false)
      const avatarNotice = avatarEdit.action === 'REPLACE' ? t('skillAvatar.submittedNotice') : avatarEdit.action === 'REMOVE' ? t('skillAvatar.removedNotice') : ''
      const skillLabel = `${result.namespace}/${result.slug}@${result.version}`
      const resultCopy = publishResultCopyKeys(result.status)
      toast.success(
        t(resultCopy.title),
        [t(resultCopy.description, { skill: skillLabel }), avatarNotice].filter(Boolean).join(' ')
      )
      navigateAfterOverlays(() => {
        navigate({ to: '/dashboard/skills' })
      })
    } catch (error) {
      // A package rejection may also reflect changed membership. Re-read the
      // server state instead of inferring permissions from its localized message.
      if (updateSlug && !avatarOnly && error instanceof ApiError && error.status === 400) {
        await queryClient.invalidateQueries({ queryKey: getSkillDetailQueryKey(namespaceSlug, updateSlug ?? '') })
      }
      // Avatar-only updates call the API directly rather than a mutation.
      if (avatarOnly) await refreshSkillPermissionsAfterForbidden(queryClient, error)
      if (error instanceof ApiError && error.status === 408) {
        toast.error(t('publish.timeoutTitle'), t('publish.timeoutDescription'))
        return
      }

      if (error instanceof ApiError && isVersionExistsMessage(error.serverMessage || error.message)) {
        toast.error(
          t('publish.versionExistsTitle'),
          t('publish.versionExistsDescription'),
        )
        return
      }

      if (error instanceof ApiError && isPrecheckConfirmationMessage(error.serverMessage || error.message)) {
        setPrecheckWarnings(extractPrecheckWarnings(error.serverMessage || error.message))
        setWarningDialogOpen(true)
        return
      }

      if (error instanceof ApiError && isPrecheckFailureMessage(error.serverMessage || error.message)) {
        toast.error(
          t('publish.precheckFailedTitle'),
          error.serverMessage || t('publish.precheckFailedDescription'),
        )
        return
      }

      if (error instanceof ApiError && isFrontmatterFailureMessage(error.serverMessage || error.message)) {
        toast.error(
          t('publish.frontmatterFailedTitle'),
          error.serverMessage || t('publish.frontmatterFailedDescription'),
        )
        return
      }

      toast.error(t('publish.error'), error instanceof Error ? error.message : '')
    } finally {
      submitting.current = false
      setIsSavingAvatar(false)
    }
  }

  const handlePublish = async () => {
    await publishSkill(false)
  }

  // The design pairs a secondary "取消" with the primary action in the bottom bar.
  // It only leaves the form: no draft, upload or lifecycle state is touched.
  const handleCancel = () => {
    navigate({ to: '/dashboard/skills' })
  }

  return (
    <div className="mx-auto max-w-[1704px] animate-fade-up">
      <DashboardPageHeader title={t(isUpdate ? 'publish.updateTitle' : 'publish.title')} subtitle={t(isUpdate ? 'publish.updateSubtitle' : 'publish.subtitle')} />

      {/* 来源切换：本地上传 | 代码仓导入。更新/重提场景只能上传包，因此不提供切换。 */}
      {!updateSlug ? (
        <div className="mt-6">
          <PublishSourceSwitch value={source} onChange={setSource} disabled={isBusy || isPackaging} />
        </div>
      ) : null}

      <div hidden={source !== 'local'}>
      {ownerReadOnly && <p role="status" className="mt-6 rounded-md border border-border/60 bg-muted/30 p-3 text-sm text-muted-foreground">{t('mySkills.ownerReadOnly')}</p>}

      <div className="mt-8 space-y-8">
        {prefill.resubmitSkill && prefill.resubmitVersion ? (
          <div className="rounded-md border border-amber-500/25 bg-amber-500/5 p-4">
            <h2 className="text-sm font-semibold text-foreground">
              {t('publish.resubmitNotice.title', {
                skill: `@${prefill.namespace}/${prefill.resubmitSkill}`,
                version: prefill.resubmitVersion,
              })}
            </h2>
            <p className="mt-1 text-xs text-muted-foreground">
              {t('publish.resubmitNotice.description')}
            </p>
          </div>
        ) : null}

        <div className="flex items-start gap-3 rounded-md border border-border/60 bg-muted/30 p-4">
          <svg className="mt-0.5 h-4 w-4 flex-shrink-0 text-muted-foreground" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden="true">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
          </svg>
          <div className="flex-1">
            <h3 className="text-sm font-semibold text-foreground">{t(publishNoticeCopyKeys(visibility).title)}</h3>
            <p className="mt-1 text-xs text-muted-foreground">{t(publishNoticeCopyKeys(visibility).description)}</p>
          </div>
        </div>

        {/* ── 技能包文件 ───────────────────────────────────────── */}
        <section className="space-y-3">
          <FieldLabel required={!isUpdate}>{t('publish.file')}</FieldLabel>
          {isUpdate && <p className="text-xs text-muted-foreground">{t('publish.optionalPackage')}</p>}
          <UploadZone analyticsId="skill.package.select" folderAnalyticsId="skill.package.folder.select" pickerAnalyticsId="skill.package.folder.open"
            className="md:min-h-[520px]"
            onFileSelect={handleFileSelect}
            onFolderSelect={handleFolderSelect}
            disabled={ownerReadOnly || isBusy || isPackaging}
          />
          {selectedFile && (
            <div className="flex items-center justify-between gap-4 rounded-md bg-amber-500/10 px-4 py-2.5">
              <div className="flex min-w-0 items-center gap-2 text-sm text-foreground">
                <svg className="h-4 w-4 flex-shrink-0 text-amber-600 dark:text-amber-400" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden="true">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.8} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                </svg>
                <span className="truncate font-medium">{selectedFile.name}</span>
                <span className="flex-shrink-0 text-xs text-muted-foreground tabular-nums">{(selectedFile.size / 1024).toFixed(1)} KB</span>
              </div>
              <Button data-analytics-id="skill.package.remove"
                type="button"
                variant="ghost"
                size="sm"
                className="h-7 rounded-full px-2.5 text-xs text-muted-foreground hover:text-foreground"
                onClick={handleRemoveSelectedFile}
                disabled={ownerReadOnly || isBusy}
              >
                <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden="true">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.8} d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                </svg>
                {t('publish.removeSelectedFile')}
              </Button>
            </div>
          )}

          {selectedFile && (packagePreview.isPending || packagePreview.isError || packagePreview.data) && (
            <div className="space-y-2 rounded-md border border-border/60 bg-secondary/30 px-4 py-3">
              {packagePreview.isPending && (
                <p role="status" className="text-xs text-muted-foreground">{t('publish.readingPackage')}</p>
              )}
              {packagePreview.isError && (
                <div role="alert" className="space-y-2 text-xs text-destructive">
                  <p>{t('publish.packagePreviewFailed')}</p>
                  <Button data-analytics-id="skill.package.preview.retry" type="button" size="sm" variant="outline" className="h-7 rounded-full px-3 text-xs" onClick={() => packagePreview.refetch()}>{t('publish.retryPackagePreview')}</Button>
                </div>
              )}
              {packagePreview.data && (
                <>
                  <dl className="grid gap-x-6 gap-y-1.5 sm:grid-cols-[7rem_minmax(0,1fr)]">
                    <dt className="text-xs text-muted-foreground">{t('publish.packageName')}</dt>
                    <dd className="break-all text-sm text-foreground"><code className="font-mono">{packagePreview.data.name}</code></dd>
                  </dl>
                  {packagePreview.data.examplesStatus === 'ready' && (
                    <p role="status" className="text-xs text-muted-foreground">{t('publish.examplesReady', { count: packagePreview.data.examplesCount })}</p>
                  )}
                  {packagePreview.data.examplesStatus === 'none' && (
                    <p className="text-xs text-muted-foreground">{t('publish.examplesNone')}</p>
                  )}
                  {packagePreview.data.examplesStatus === 'invalid' && (
                    <p role="status" className="text-xs text-muted-foreground">{t('publish.examplesInvalid')}</p>
                  )}
                </>
              )}
            </div>
          )}
        </section>

        <SectionDivider />

        {/* ── 基础信息 ─────────────────────────────────────────── */}
        <section className="space-y-6">
          <div className="grid gap-x-4 gap-y-6 md:grid-cols-2">
            <div className="space-y-2">
              <FieldLabel htmlFor="namespace" required>{t('publish.namespace')}</FieldLabel>
              {isLoadingNamespaces ? (
                <div className="h-[var(--control-height)] animate-shimmer rounded-md" />
              ) : (
                <Select
                  disabled={!!updateSlug || isBusy}
                  value={normalizeSelectValue(namespaceSlug) ?? EMPTY_NAMESPACE_VALUE}
                  onValueChange={(value) => {
                    setNamespaceSlug(value === EMPTY_NAMESPACE_VALUE ? '' : value)
                    setAvatarEdit(KEEP_AVATAR)
                  }}
                >
                  <SelectTrigger data-analytics-id="skill.publish.namespace.interact" id="namespace">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem data-analytics-id="skill.publish.namespace.clear" value={EMPTY_NAMESPACE_VALUE}>{t('publish.selectNamespace')}</SelectItem>
                    {namespaces?.map((ns) => (
                      <SelectItem data-analytics-id="skill.publish.namespace.select" key={ns.id} value={ns.slug}>
                        {ns.displayName} (@{ns.slug})
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              )}
            </div>

            <div className="space-y-2">
              <FieldLabel htmlFor="visibility" required>{t('publish.visibility')}</FieldLabel>
              <Select value={visibility} onValueChange={setVisibility} disabled={ownerReadOnly || isBusy || avatarOnly}>
                <SelectTrigger data-analytics-id="skill.publish.visibility.interact" id="visibility">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem data-analytics-id="skill.publish.visibility.public" value="PUBLIC">{t('publish.visibilityOptions.public')}</SelectItem>
                  <SelectItem data-analytics-id="skill.publish.visibility.namespace" value="NAMESPACE_ONLY">{namespaceOnlyLabel}</SelectItem>
                  <SelectItem data-analytics-id="skill.publish.visibility.private" value="PRIVATE">{t('publish.visibilityOptions.private')}</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>

          <div className="space-y-2">
            <FieldLabel htmlFor="skill-display-name">{t('publish.displayName')}</FieldLabel>
            <Input
              id="skill-display-name"
              value={displayName}
              maxLength={200}
              disabled={ownerReadOnly || isBusy || !selectedFile}
              placeholder={isUpdate ? search.updateName : t('publish.displayNamePlaceholder')}
              aria-describedby="skill-display-name-help"
              onChange={event => {
                displayNameEdited.current = true
                setDisplayName(event.target.value)
              }}
            />
            <p id="skill-display-name-help" className="text-xs text-muted-foreground">{t('publish.displayNameHelp')}</p>
          </div>

          <div className="space-y-2">
            <FieldLabel htmlFor="skill-changelog">{t('publish.changelog')}</FieldLabel>
            <Textarea
              id="skill-changelog"
              data-analytics-id="skill.changelog.input"
              className="min-h-[104px] rounded-md border-border/60 bg-background px-3 py-2"
              value={changelog}
              maxLength={4000}
              rows={3}
              disabled={ownerReadOnly || isBusy || !selectedFile}
              placeholder={t('publish.changelogPlaceholder')}
              aria-describedby="skill-changelog-help"
              onChange={event => setChangelog(event.target.value)}
            />
            <div className="flex items-start justify-between gap-4">
              <p id="skill-changelog-help" className="text-xs text-muted-foreground">{t('publish.changelogHelp')}</p>
              <span className="flex-shrink-0 text-xs text-muted-foreground tabular-nums">{changelog.length}/4000</span>
            </div>
          </div>
        </section>

        <SectionDivider />

        {/* ── 技能头像 ─────────────────────────────────────────── */}
        <section className="space-y-4">
          <SkillAvatarEditor key={`${namespaceSlug}/${updateSlug}`} name={search.updateName ?? updateSlug} currentUrl={avatarTarget.data?.avatarUrl} canRemove={!!updateSlug} disabled={ownerReadOnly || isBusy || avatarUnavailable} onChange={setAvatarEdit} />
          {updateSlug && <SkillAvatarManager namespace={namespaceSlug} slug={updateSlug} disabled={ownerReadOnly || isBusy} />}
          {avatarTarget.isError && <p role="alert" className="text-sm text-destructive">{t('skillAvatar.failed')}</p>}
        </section>
      </div>

      {/* ── 底部操作条：取消（次要）/ 提交（主要） ─────────────── */}
      <div className="sticky bottom-0 z-10 mt-10 border-t border-border/60 bg-background/90 py-3 backdrop-blur">
        <div className="flex items-center justify-center gap-3">
          <Button type="button" variant="outline" className="rounded-full px-7" onClick={handleCancel} disabled={isBusy}>
            {t('dialog.cancel')}
          </Button>
          <Button data-analytics-id={avatarOnly ? "skill.avatar.save" : "skill.publish.request"}
            type="button"
            className="rounded-full px-7 text-primary-foreground disabled:text-primary-foreground"
            onClick={handlePublish}
            disabled={!canSubmit}
          >
            {isBusy ? t('publish.publishing') : t(avatarOnly ? 'publish.saveAvatar' : 'publish.confirm')}
          </Button>
        </div>
      </div>

      <ConfirmDialog confirmAnalyticsId={avatarOnly ? undefined : "skill.publish.confirm"} cancelAnalyticsId={avatarOnly ? undefined : "skill.publish.cancel"}
        open={warningDialogOpen}
        onOpenChange={setWarningDialogOpen}
        title={t('publish.warningConfirmTitle')}
        description={(
          <div className="space-y-3 text-left">
            <p>{t('publish.warningConfirmDescription')}</p>
            {precheckWarnings.length > 0 && (
              <ul className="list-disc space-y-1 pl-5">
                {precheckWarnings.map((warning) => (
                  <li key={warning}>{warning}</li>
                ))}
              </ul>
            )}
          </div>
        )}
        confirmText={t('publish.warningConfirmContinue')}
        cancelText={t('publish.warningConfirmCancel')}
        onConfirm={() => publishSkill(true)}
      />
      </div>

      {/* 代码仓导入：三步向导，自己的状态独立于上面的本地上传。 */}
      <div hidden={source !== 'gitcode'}>
        <GitcodeImportWizard
          prefillNamespace={prefill.namespace}
          prefillVisibility={prefill.visibility}
          onCancel={handleCancel}
        />
      </div>
    </div>
  )
}
