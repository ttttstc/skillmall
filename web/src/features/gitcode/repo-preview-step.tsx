import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { MarkdownRenderer } from '@/features/skill/markdown-renderer'
import { canPreviewFile } from '@/features/skill/file-type-utils'
import { FieldLabel } from '@/features/publish/field-label'
import { Button } from '@/shared/ui/button'
import { Card } from '@/shared/ui/card'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/shared/ui/select'
import { ancestorDirectories, GitcodeFileTree } from './gitcode-file-tree'
import type { GitcodeFileEntry, GitcodeSkillSummary } from './types'

/** Root skill directories have an empty path, which a Radix select value cannot carry. */
const ROOT_OPTION_VALUE = '/'

function toOptionValue(path: string): string {
  return path || ROOT_OPTION_VALUE
}

function fromOptionValue(value: string): string {
  return value === ROOT_OPTION_VALUE ? '' : value
}

interface PreviewPaneProps {
  files: readonly GitcodeFileEntry[]
  skillMd: string
  skillMdPath: string | null
  name?: string | null
  description?: string | null
}

function PreviewPane({ files, skillMd, skillMdPath, name, description }: PreviewPaneProps) {
  const { t } = useTranslation()
  const [selectedFile, setSelectedFile] = useState<string | null>(skillMdPath)
  const selectedEntry = selectedFile ? files.find((file) => file.path === selectedFile) : undefined
  const isDocument = !selectedFile || selectedFile === skillMdPath
  const previewCheck = selectedEntry ? canPreviewFile(selectedEntry.path, selectedEntry.size ?? 0) : null

  // The inspect contract only carries the SKILL.md body, so any other file explains why it is not
  // rendered instead of pretending to load it.
  const unavailableCopy = isDocument
    ? skillMd.trim() ? null : t('publish.repo.previewEmpty', { defaultValue: t('filePreview.loadError') })
    : previewCheck?.reason === 'too-large'
      ? t('filePreview.tooLarge')
      : previewCheck?.reason === 'binary'
        ? t('filePreview.binaryFile')
        : t('publish.repo.previewSkillMdOnly', { defaultValue: t('filePreview.unsupported') })

  return (
    <div className="grid min-w-0 gap-4 lg:grid-cols-[minmax(200px,264px)_minmax(0,1fr)]">
      <Card className="min-w-0 p-2 lg:h-[620px] lg:overflow-auto">
        <GitcodeFileTree
          files={files}
          selectedPath={selectedFile}
          initiallyExpanded={ancestorDirectories(skillMdPath)}
          onSelectFile={setSelectedFile}
        />
      </Card>
      <Card className="min-w-0 px-6 py-5 lg:h-[620px] lg:overflow-auto">
        <section className="space-y-4">
          <h3 className="text-sm font-medium text-muted-foreground">
            {t('publish.repo.metadata', { defaultValue: '技能元数据' })}
          </h3>
          <dl className="space-y-3">
            <div>
              <dt className="text-xs text-muted-foreground">
                {t('publish.repo.name', { defaultValue: t('mySkills.table.name') })}
              </dt>
              <dd className="mt-1 break-words text-[15px] text-foreground">{name || '—'}</dd>
            </div>
            <div>
              <dt className="text-xs text-muted-foreground">
                {t('publish.repo.description', { defaultValue: t('namespaceEdit.descriptionLabel') })}
              </dt>
              <dd className="mt-1 break-words text-[15px] text-foreground">{description || '—'}</dd>
            </div>
          </dl>
        </section>
        <hr className="my-5 border-0 border-t border-border/60" />
        {unavailableCopy ? (
          <p role="status" className="text-sm text-muted-foreground">{unavailableCopy}</p>
        ) : (
          <MarkdownRenderer content={skillMd} />
        )}
      </Card>
    </div>
  )
}

interface RepoPreviewStepProps {
  skills: readonly GitcodeSkillSummary[]
  selectedPath: string
  onSelectPath: (path: string) => void
  files: readonly GitcodeFileEntry[]
  skillMd: string
  skillMdPath: string | null
  isLoading: boolean
  errorMessage?: string
  onRetry: () => void
  disabled?: boolean
}

/**
 * Step 2 of the import wizard: the repository tree on the left, the selected skill's metadata and
 * SKILL.md on the right. When the repository holds more than one skill, the directory picker is
 * shown above them (design §2).
 */
export function RepoPreviewStep({
  skills,
  selectedPath,
  onSelectPath,
  files,
  skillMd,
  skillMdPath,
  isLoading,
  errorMessage,
  onRetry,
  disabled = false,
}: RepoPreviewStepProps) {
  const { t } = useTranslation()
  const skill = skills.find((candidate) => candidate.path === selectedPath)

  return (
    <div className="space-y-4">
      {skills.length > 1 ? (
        <div className="space-y-2">
          <FieldLabel htmlFor="gitcode-skill-directory" required>{t('publish.repo.directory', { defaultValue: t('review.githubSkillPath') })}</FieldLabel>
          <Select value={toOptionValue(selectedPath)} onValueChange={(value) => onSelectPath(fromOptionValue(value))} disabled={disabled}>
            <SelectTrigger id="gitcode-skill-directory" data-analytics-id="skill.import.directory.interact">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {skills.map((candidate) => (
                <SelectItem
                  key={toOptionValue(candidate.path)}
                  data-analytics-id="skill.import.directory.select"
                  value={toOptionValue(candidate.path)}
                >
                  {candidate.path ? `${candidate.name} · ${candidate.path}` : `${candidate.name} · /`}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <p className="text-xs text-muted-foreground">{t('publish.repoMultipleSkills')}</p>
        </div>
      ) : null}

      {isLoading ? (
        <Card className="h-[420px] animate-shimmer" />
      ) : errorMessage ? (
        <Card className="space-y-3 p-6">
          <p role="alert" className="text-sm text-destructive">{errorMessage}</p>
          <Button data-analytics-id="skill.import.preview.retry" type="button" variant="outline" size="sm" className="rounded-full px-4" onClick={onRetry}>
            {t('common.retry')}
          </Button>
        </Card>
      ) : files.length === 0 && !skillMd.trim() ? (
        <Card className="p-6">
          <p role="status" className="text-sm text-muted-foreground">{t('skillDetail.noFiles')}</p>
        </Card>
      ) : (
        <PreviewPane
          key={selectedPath}
          files={files}
          skillMd={skillMd}
          skillMdPath={skillMdPath}
          name={skill?.name}
          description={skill?.description}
        />
      )}
    </div>
  )
}
