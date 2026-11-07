import { useMemo, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { useTranslation } from 'react-i18next'
import { ApiError, gitcodeImportApi, type GitCodeImportResolveResult } from '@/api/client'
import { useMyNamespaces } from '@/shared/hooks/use-namespace-queries'
import { Button } from '@/shared/ui/button'
import { Card } from '@/shared/ui/card'
import { Input } from '@/shared/ui/input'
import { Label } from '@/shared/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  normalizeSelectValue,
} from '@/shared/ui/select'
import { Textarea } from '@/shared/ui/textarea'
import { navigateAfterOverlays } from '@/shared/lib/navigate-after-overlays'
import { toast } from '@/shared/lib/toast'

const EMPTY_NAMESPACE_VALUE = '__select_namespace__'

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/**
 * Imports one skill from a pinned snapshot of a public GitCode repository.
 *
 * The panel shows the pinned commit before anything is published and sends that same commit plus
 * the previewed content digest when publishing, so a branch that moves in between cannot change the
 * published version.
 */
export function GitCodeImportPanel() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { data: namespaces } = useMyNamespaces()

  const [repoUrl, setRepoUrl] = useState('')
  const [resolvedRepoUrl, setResolvedRepoUrl] = useState('')
  const [preview, setPreview] = useState<GitCodeImportResolveResult | null>(null)
  const [selectedPath, setSelectedPath] = useState('')
  const [namespaceSlug, setNamespaceSlug] = useState('')
  const [visibility, setVisibility] = useState('PUBLIC')
  const [displayName, setDisplayName] = useState('')
  const [changelog, setChangelog] = useState('')

  const selected = preview?.selected ?? null
  const candidates = useMemo(() => preview?.candidates ?? [], [preview])

  const resolveMutation = useMutation({
    mutationFn: gitcodeImportApi.resolve,
    onSuccess: (data, variables) => {
      setPreview(data)
      setResolvedRepoUrl(variables.repoUrl)
      if (variables.path) {
        setSelectedPath(variables.path)
        if (!displayName) {
          setDisplayName(variables.path.split('/').filter(Boolean).pop() ?? '')
        }
      } else {
        setSelectedPath('')
      }
    },
  })

  const publishMutation = useMutation({
    mutationFn: gitcodeImportApi.publish,
    onSuccess: async (data) => {
      await queryClient.invalidateQueries({ queryKey: ['skills'] })
      toast.success(
        t('gitcodeImport.title'),
        t('gitcodeImport.published', {
          skill: `${data.namespace}/${data.slug}@${data.version}`,
          commit: data.commit?.slice(0, 12) ?? '',
        }),
      )
      navigateAfterOverlays(() => { navigate({ to: '/dashboard/skills' }) })
    },
  })

  const loadRepository = () => {
    const trimmed = repoUrl.trim()
    if (!trimmed) return
    resolveMutation.mutate({ repoUrl: trimmed })
  }

  const selectCandidate = (path: string) => {
    if (!preview?.commit) return
    setSelectedPath(path)
    // Re-resolve at the pinned commit so the preview always describes the commit shown above.
    resolveMutation.mutate({
      repoUrl: resolvedRepoUrl || repoUrl.trim(),
      commit: preview.commit,
      path,
    })
  }

  const publish = () => {
    if (!preview?.commit || !selectedPath || !selected) return
    if (!namespaceSlug) {
      toast.error(t('gitcodeImport.publish'), t('publish.selectNamespace'))
      return
    }
    publishMutation.mutate({
      repoUrl: resolvedRepoUrl || repoUrl.trim(),
      commit: preview.commit,
      branch: preview.defaultBranch ?? null,
      path: selectedPath,
      namespace: namespaceSlug,
      visibility,
      displayName: displayName.trim() || null,
      changelog: changelog.trim() || null,
      contentDigest: selected.contentDigest ?? '',
    })
  }

  const resolveError = resolveMutation.error instanceof ApiError ? resolveMutation.error : null
  const publishError = publishMutation.error instanceof ApiError ? publishMutation.error : null
  const canPublish = !!preview?.commit && !!selectedPath && !!selected && !!namespaceSlug
    && !publishMutation.isPending

  return (
    <Card className="space-y-6 p-6">
      <div className="space-y-1">
        <h2 className="text-lg font-semibold font-heading text-foreground">{t('gitcodeImport.title')}</h2>
        <p className="text-sm text-muted-foreground">{t('gitcodeImport.subtitle')}</p>
      </div>

      <div className="space-y-2">
        <Label htmlFor="gitcode-repo-url">{t('gitcodeImport.repoUrl')}</Label>
        <div className="flex gap-3">
          <Input
            id="gitcode-repo-url"
            value={repoUrl}
            placeholder={t('gitcodeImport.repoUrlPlaceholder')}
            onChange={(event) => setRepoUrl(event.target.value)}
            disabled={resolveMutation.isPending || publishMutation.isPending}
          />
          <Button
            type="button"
            variant="outline"
            onClick={loadRepository}
            disabled={!repoUrl.trim() || resolveMutation.isPending || publishMutation.isPending}
          >
            {resolveMutation.isPending ? t('gitcodeImport.resolving') : t('gitcodeImport.resolve')}
          </Button>
        </div>
        <p className="text-xs text-muted-foreground">{t('gitcodeImport.repoUrlHelp')}</p>
        {resolveError && (
          <p role="alert" className="text-sm text-destructive">
            {t('gitcodeImport.resolveFailed')}: {resolveError.serverMessage || resolveError.message}
          </p>
        )}
      </div>

      {preview?.commit && (
        <dl className="grid gap-3 rounded-lg border border-border/60 bg-secondary/20 p-4 text-sm sm:grid-cols-3">
          <div>
            <dt className="text-xs text-muted-foreground">{t('gitcodeImport.pinnedCommit')}</dt>
            <dd className="break-all font-mono text-xs text-foreground">{preview.commit}</dd>
          </div>
          <div>
            <dt className="text-xs text-muted-foreground">{t('gitcodeImport.defaultBranch')}</dt>
            <dd className="text-foreground">{preview.defaultBranch ?? '—'}</dd>
          </div>
          <div>
            <dt className="text-xs text-muted-foreground">{t('gitcodeImport.repoUrl')}</dt>
            <dd className="break-all text-foreground">{preview.repoUrl}</dd>
          </div>
        </dl>
      )}

      {preview && candidates.length > 0 && (
        <div className="space-y-2">
          <Label>{t('gitcodeImport.candidatesTitle')}</Label>
          <p className="text-xs text-muted-foreground">{t('gitcodeImport.choosePath')}</p>
          <ul className="space-y-2">
            {candidates.map((candidate) => (
              <li key={candidate.path}>
                <button
                  type="button"
                  onClick={() => selectCandidate(candidate.path ?? '')}
                  aria-pressed={selectedPath === candidate.path}
                  disabled={resolveMutation.isPending}
                  className={`w-full rounded-lg border px-3 py-2 text-left font-mono text-xs transition-colors ${
                    selectedPath === candidate.path
                      ? 'border-primary bg-primary/10 text-foreground'
                      : 'border-border/60 bg-background hover:bg-secondary/40'
                  }`}
                >
                  {candidate.path}
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}

      {preview && candidates.length === 0 && (
        <p role="status" className="text-sm text-muted-foreground">{t('gitcodeImport.candidatesEmpty')}</p>
      )}

      {selected && (
        <div className="space-y-3 rounded-lg border border-border/60 p-4">
          <h3 className="text-sm font-semibold text-foreground">{t('gitcodeImport.selectedTitle')}</h3>
          <dl className="grid gap-2 text-sm sm:grid-cols-3">
            <div>
              <dt className="text-xs text-muted-foreground">{t('gitcodeImport.filesTitle')}</dt>
              <dd className="text-foreground">{t('gitcodeImport.fileCount', { files: selected.fileCount })}</dd>
            </div>
            <div>
              <dt className="text-xs text-muted-foreground">{t('gitcodeImport.totalSize')}</dt>
              <dd className="text-foreground">{formatBytes(selected.totalSize ?? 0)}</dd>
            </div>
            <div>
              <dt className="text-xs text-muted-foreground">{t('gitcodeImport.contentDigest')}</dt>
              <dd className="break-all font-mono text-xs text-foreground">{selected.contentDigest}</dd>
            </div>
          </dl>
          <ul className="max-h-56 space-y-1 overflow-y-auto text-xs text-muted-foreground">
            {(selected.files ?? []).map((file) => (
              <li key={file.path} className="flex justify-between gap-3 font-mono">
                <span className="break-all">{file.path}</span>
                <span>{formatBytes(file.size ?? 0)}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {selected && (
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="space-y-2">
            <Label htmlFor="gitcode-namespace">{t('publish.namespace')}</Label>
            <Select
              value={normalizeSelectValue(namespaceSlug) ?? EMPTY_NAMESPACE_VALUE}
              onValueChange={(value) => setNamespaceSlug(value === EMPTY_NAMESPACE_VALUE ? '' : value)}
            >
              <SelectTrigger id="gitcode-namespace">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={EMPTY_NAMESPACE_VALUE}>{t('publish.selectNamespace')}</SelectItem>
                {namespaces?.map((ns) => (
                  <SelectItem key={ns.id} value={ns.slug}>{ns.displayName} (@{ns.slug})</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-2">
            <Label htmlFor="gitcode-visibility">{t('publish.visibility')}</Label>
            <Select value={visibility} onValueChange={setVisibility}>
              <SelectTrigger id="gitcode-visibility">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="PUBLIC">{t('publish.visibilityOptions.public')}</SelectItem>
                <SelectItem value="NAMESPACE_ONLY">{t('publish.visibilityOptions.namespaceOnly')}</SelectItem>
                <SelectItem value="PRIVATE">{t('publish.visibilityOptions.private')}</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-2">
            <Label htmlFor="gitcode-display-name">{t('publish.displayName')}</Label>
            <Input
              id="gitcode-display-name"
              value={displayName}
              maxLength={200}
              onChange={(event) => setDisplayName(event.target.value)}
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="gitcode-changelog">{t('publish.changelog')}</Label>
            <Textarea
              id="gitcode-changelog"
              value={changelog}
              rows={3}
              maxLength={4000}
              onChange={(event) => setChangelog(event.target.value)}
            />
          </div>
        </div>
      )}

      {publishError && (
        <p role="alert" className="text-sm text-destructive">
          {t('gitcodeImport.publishFailed')}: {publishError.serverMessage || publishError.message}
        </p>
      )}

      <Button type="button" size="lg" onClick={publish} disabled={!canPublish} className="w-full">
        {publishMutation.isPending ? t('gitcodeImport.publishing') : t('gitcodeImport.publish')}
      </Button>
    </Card>
  )
}
