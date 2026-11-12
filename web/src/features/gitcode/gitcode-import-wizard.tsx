import { useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { useTranslation } from 'react-i18next'
import { ApiError, skillAvatarApi } from '@/api/client'
import { KEEP_AVATAR, type AvatarEdit } from '@/features/skill/skill-avatar-editor'
import {
  extractPrecheckWarnings,
  isFrontmatterFailureMessage,
  isPrecheckConfirmationMessage,
  isPrecheckFailureMessage,
  isVersionExistsMessage,
} from '@/features/publish/publish-error-utils'
import { publishResultCopyKeys } from '@/features/publish/publish-result'
import { ConfirmDialog } from '@/shared/components/confirm-dialog'
import { navigateAfterOverlays } from '@/shared/lib/navigate-after-overlays'
import { toast } from '@/shared/lib/toast'
import { useMyNamespaces } from '@/shared/hooks/use-namespace-queries'
import { Button } from '@/shared/ui/button'
import { GITCODE_ADDRESS_FORM_ID, RepoAddressStep } from './repo-address-step'
import { RepoPreviewStep } from './repo-preview-step'
import { ImportBasicInfoStep } from './import-basic-info-step'
import { StepRail } from './step-rail'
import { gitcodeErrorKeys } from './gitcode-errors'
import { gitcodeUrlIssueKeys, parseGitcodeRepoUrl } from './gitcode-url'
import { useGitcodeSkillPreview, useImportGitcodeSkill, useInspectGitcodeRepository } from './use-gitcode-queries'
import type { GitcodeInspectResult, GitcodeSkillSummary } from './types'

interface GitcodeImportWizardProps {
  /** Namespace preselected by the publish page query string. */
  prefillNamespace: string
  prefillVisibility: string
  /** Leaves the wizard without touching any lifecycle state (UCD 取消). */
  onCancel: () => void
}

/** Directory hinted by a `/tree/{ref}/{path}` deep link wins, otherwise the repository root does. */
function pickSkill(skills: readonly GitcodeSkillSummary[], hint?: string): GitcodeSkillSummary | undefined {
  if (skills.length === 0) return undefined
  if (hint) {
    const hinted = skills.find((skill) => hint === skill.path || hint.startsWith(`${skill.path}/`))
    if (hinted) return hinted
  }
  return skills.find((skill) => skill.path === '') ?? skills[0]
}

/**
 * 代码仓导入 wizard: ① 填写地址 → ② 文件预览 → ③ 基础信息.
 *
 * State lives here and the three steps stay mounted while the user walks them, so 上一步 keeps the
 * repository, the selected directory and the typed basic info. The local upload form is a separate
 * branch of the publish page and is never touched by this wizard.
 */
export function GitcodeImportWizard({ prefillNamespace, prefillVisibility, onCancel }: GitcodeImportWizardProps) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [stepIndex, setStepIndex] = useState(0)
  const [repoUrl, setRepoUrl] = useState('')
  const [addressError, setAddressError] = useState('')
  const [inspect, setInspect] = useState<GitcodeInspectResult | null>(null)
  /** `null` = no directory chosen yet, `''` = the repository root is the skill directory. */
  const [selectedPath, setSelectedPath] = useState<string | null>(null)
  const [namespaceSlug, setNamespaceSlug] = useState(prefillNamespace)
  const [visibility, setVisibility] = useState(prefillVisibility)
  const [displayName, setDisplayName] = useState('')
  const [changelog, setChangelog] = useState('')
  // Real repositories usually carry neither a tag nor a frontmatter version, so step 3 collects it.
  const [version, setVersion] = useState('')
  const [avatarEdit, setAvatarEdit] = useState<AvatarEdit>(KEEP_AVATAR)
  const [precheckWarnings, setPrecheckWarnings] = useState<string[]>([])
  const [warningDialogOpen, setWarningDialogOpen] = useState(false)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const displayNameEdited = useRef(false)
  const submitting = useRef(false)

  const { data: namespaces, isLoading: isLoadingNamespaces } = useMyNamespaces()
  const inspectMutation = useInspectGitcodeRepository()
  const importMutation = useImportGitcodeSkill()
  const previewQuery = useGitcodeSkillPreview({
    repoUrl: repoUrl.trim(),
    ref: inspect?.ref,
    path: selectedPath,
    enabled: stepIndex === 1,
  })

  const skills = inspect?.skills ?? []
  const selectedSkill = skills.find((skill) => skill.path === selectedPath)
  const previewFiles = previewQuery.data?.files ?? []
  const previewFilePath = previewQuery.data?.skillMdPath
    ?? (previewFiles.find((file) => file.path === 'SKILL.md')?.path
      ?? previewFiles.find((file) => file.path.endsWith('/SKILL.md'))?.path
      ?? null)

  const errorCopy = (error: unknown): string => {
    const copy = gitcodeErrorKeys(error)
    return 'message' in copy ? copy.message : t(copy.key, { defaultValue: t(copy.fallbackKey) })
  }

  const handleAddressSubmit = async () => {
    if (inspectMutation.isPending) return

    const parsed = parseGitcodeRepoUrl(repoUrl)
    if (!parsed.ok) {
      const copy = gitcodeUrlIssueKeys(parsed.issue ?? 'format')
      setAddressError(t(copy.key, { defaultValue: t(copy.fallbackKey) }))
      return
    }

    setAddressError('')
    try {
      const result = await inspectMutation.mutateAsync({ repoUrl: repoUrl.trim(), ref: parsed.ref })
      const chosen = pickSkill(result.skills, parsed.path)
      setInspect(result)
      setSelectedPath(chosen?.path ?? null)
      if (!displayNameEdited.current && chosen?.name) setDisplayName(chosen.name)
      setStepIndex(1)
    } catch (error) {
      setAddressError(errorCopy(error))
    }
  }

  const canLeavePreview = !!inspect && selectedPath !== null && (previewQuery.isSuccess || previewQuery.isError)
  const versionRequired = !selectedSkill?.version && inspect?.refType !== 'TAG'
  const canSubmit = !!namespaceSlug && !!inspect && selectedPath !== null && avatarEdit.ready && !isSubmitting
    && displayName.trim().length <= 200
    && (!versionRequired || version.trim().length > 0)

  const submitImport = async (confirmWarnings = false) => {
    if (submitting.current || !inspect || selectedPath === null) return
    if (!canSubmit) {
      toast.error(t('publish.selectRequired'))
      return
    }

    submitting.current = true
    setIsSubmitting(true)
    try {
      const result = await importMutation.mutateAsync({
        namespace: namespaceSlug,
        request: {
          repoUrl: repoUrl.trim(),
          ref: inspect.ref,
          path: selectedPath,
          visibility,
          confirmWarnings,
          displayName: displayName.trim() || undefined,
          changelog: changelog.trim() || undefined,
          version: version.trim() || undefined,
        },
      })
      setWarningDialogOpen(false)
      setPrecheckWarnings([])

      // The import contract has no avatar field; a staged avatar is attached to the skill that the
      // import just created. A failure here must not turn a successful import into an error.
      if (avatarEdit.action === 'REPLACE' && avatarEdit.uploadId) {
        try {
          await skillAvatarApi.submit(result.namespace, result.slug, avatarEdit.uploadId)
          await queryClient.invalidateQueries({ queryKey: ['skill-avatar', result.namespace, result.slug] })
        } catch (error) {
          console.error('Failed to submit the staged avatar for the imported skill', error)
        }
      }

      const resultCopy = publishResultCopyKeys(result.status)
      toast.success(
        t(resultCopy.title),
        t(resultCopy.description, { skill: `${result.namespace}/${result.slug}@${result.version}` })
      )
      navigateAfterOverlays(() => { navigate({ to: '/dashboard/skills' }) })
    } catch (error) {
      const message = error instanceof ApiError ? error.serverMessage || error.message : ''

      if (error instanceof ApiError && isPrecheckConfirmationMessage(message)) {
        setPrecheckWarnings(extractPrecheckWarnings(message))
        setWarningDialogOpen(true)
        return
      }

      if (error instanceof ApiError && isVersionExistsMessage(message)) {
        toast.error(t('publish.versionExistsTitle'), t('publish.versionExistsDescription'))
        return
      }

      if (error instanceof ApiError && isPrecheckFailureMessage(message)) {
        toast.error(t('publish.precheckFailedTitle'), error.serverMessage || t('publish.precheckFailedDescription'))
        return
      }

      if (error instanceof ApiError && isFrontmatterFailureMessage(message)) {
        toast.error(t('publish.frontmatterFailedTitle'), error.serverMessage || t('publish.frontmatterFailedDescription'))
        return
      }

      toast.error(t('publish.error'), errorCopy(error))
    } finally {
      submitting.current = false
      setIsSubmitting(false)
    }
  }

  const steps = [
    { key: 'address', label: t('publish.stepAddress') },
    { key: 'preview', label: t('publish.stepPreview') },
    { key: 'basic', label: t('publish.stepBasic') },
  ]

  const primaryLabel = stepIndex === 2
    ? (isSubmitting ? t('publish.publishing') : t('publish.repoSubmit', { defaultValue: t('skillDetail.submitReview') }))
    : t('publish.next')

  return (
    <div>
      <div className="mt-8 grid gap-x-10 gap-y-6 lg:grid-cols-[minmax(132px,176px)_minmax(0,1fr)]">
        <StepRail steps={steps} current={stepIndex} className="lg:pt-1" />

        <div className="min-w-0">
          {stepIndex === 0 ? (
            <RepoAddressStep
              value={repoUrl}
              onChange={(value) => {
                setRepoUrl(value)
                if (addressError) setAddressError('')
              }}
              onSubmit={() => void handleAddressSubmit()}
              error={addressError}
              disabled={inspectMutation.isPending || isSubmitting}
            />
          ) : stepIndex === 1 ? (
            <RepoPreviewStep
              skills={skills}
              selectedPath={selectedPath ?? ''}
              onSelectPath={setSelectedPath}
              files={previewFiles}
              skillMd={previewQuery.data?.skillMd ?? ''}
              skillMdPath={previewFilePath}
              isLoading={previewQuery.isFetching}
              errorMessage={previewQuery.isError ? errorCopy(previewQuery.error) : undefined}
              onRetry={() => void previewQuery.refetch()}
              disabled={isSubmitting}
            />
          ) : (
            <ImportBasicInfoStep
              namespaces={namespaces}
              isLoadingNamespaces={isLoadingNamespaces}
              namespaceSlug={namespaceSlug}
              visibility={visibility}
              displayName={displayName}
              changelog={changelog}
              version={version}
              versionRequired={versionRequired}
              skillName={selectedSkill?.name ?? undefined}
              disabled={isSubmitting}
              onNamespaceChange={setNamespaceSlug}
              onVisibilityChange={setVisibility}
              onDisplayNameChange={(value) => {
                displayNameEdited.current = true
                setDisplayName(value)
              }}
              onChangelogChange={setChangelog}
              onVersionChange={setVersion}
              onAvatarChange={setAvatarEdit}
            />
          )}
        </div>
      </div>

      {/* ── 底部操作条：取消 / 上一步 / 下一步·提交审核 ─────────── */}
      <div className="sticky bottom-0 z-10 mt-10 border-t border-border/60 bg-background/90 py-3 backdrop-blur">
        <div className="flex items-center justify-center gap-3">
          <Button
            data-analytics-id="skill.import.cancel"
            type="button"
            variant="outline"
            className="rounded-full px-7"
            onClick={onCancel}
            disabled={isSubmitting}
          >
            {t('dialog.cancel')}
          </Button>

          {stepIndex > 0 ? (
            <Button
              data-analytics-id="skill.import.step.prev"
              type="button"
              variant="outline"
              className="rounded-full px-7"
              onClick={() => setStepIndex(stepIndex - 1)}
              disabled={isSubmitting}
            >
              {t('publish.prev')}
            </Button>
          ) : null}

          {stepIndex === 0 ? (
            <Button
              type="submit"
              form={GITCODE_ADDRESS_FORM_ID}
              className="rounded-full px-7 text-primary-foreground disabled:text-primary-foreground"
              disabled={inspectMutation.isPending || !repoUrl.trim()}
            >
              {inspectMutation.isPending ? t('publish.readingPackage') : primaryLabel}
            </Button>
          ) : stepIndex === 1 ? (
            <Button
              data-analytics-id="skill.import.step.next"
              type="button"
              className="rounded-full px-7 text-primary-foreground disabled:text-primary-foreground"
              onClick={() => setStepIndex(2)}
              disabled={!canLeavePreview || isSubmitting}
            >
              {primaryLabel}
            </Button>
          ) : (
            <Button
              data-analytics-id="skill.import.step.submit"
              type="button"
              className="rounded-full px-7 text-primary-foreground disabled:text-primary-foreground"
              onClick={() => void submitImport(false)}
              disabled={!canSubmit}
            >
              {primaryLabel}
            </Button>
          )}
        </div>
      </div>

      <ConfirmDialog
        confirmAnalyticsId="skill.import.warning.confirm"
        cancelAnalyticsId="skill.import.warning.cancel"
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
        onConfirm={() => void submitImport(true)}
      />
    </div>
  )
}
