import { useTranslation } from 'react-i18next'
import type { ManagedNamespace } from '@/api/types'
import { SkillAvatarEditor, type AvatarEdit } from '@/features/skill/skill-avatar-editor'
import { FieldLabel } from '@/features/publish/field-label'
import { Input } from '@/shared/ui/input'
import { Textarea } from '@/shared/ui/textarea'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  normalizeSelectValue,
} from '@/shared/ui/select'

const EMPTY_NAMESPACE_VALUE = '__select_namespace__'

interface ImportBasicInfoStepProps {
  namespaces: ManagedNamespace[] | undefined
  isLoadingNamespaces: boolean
  namespaceSlug: string
  visibility: string
  displayName: string
  changelog: string
  /** Version to publish; only required when neither a tag ref nor SKILL.md supplies one. */
  version: string
  versionRequired: boolean
  /** Skill name from SKILL.md, used as the avatar placeholder and the default display name. */
  skillName?: string
  disabled?: boolean
  onNamespaceChange: (value: string) => void
  onVisibilityChange: (value: string) => void
  onDisplayNameChange: (value: string) => void
  onChangelogChange: (value: string) => void
  onVersionChange: (value: string) => void
  onAvatarChange: (edit: AvatarEdit) => void
}

/**
 * Step 3 of the import wizard.
 *
 * It reuses the field set of the local upload form — namespace, visibility, display name, change
 * note and avatar — because the import runs through the same publish pipeline; only the analytics
 * ids differ, so importer traffic stays separable from local uploads.
 */
export function ImportBasicInfoStep({
  namespaces,
  isLoadingNamespaces,
  namespaceSlug,
  visibility,
  displayName,
  changelog,
  version,
  versionRequired,
  skillName,
  disabled = false,
  onNamespaceChange,
  onVisibilityChange,
  onDisplayNameChange,
  onChangelogChange,
  onVersionChange,
  onAvatarChange,
}: ImportBasicInfoStepProps) {
  const { t } = useTranslation()
  const selectedNamespace = namespaces?.find((namespace) => namespace.slug === namespaceSlug)
  const namespaceOnlyLabel = selectedNamespace?.type === 'GLOBAL'
    ? t('publish.visibilityOptions.loggedInUsersOnly')
    : t('publish.visibilityOptions.namespaceOnly')

  return (
    <div className="space-y-6">
      <div className="grid gap-x-4 gap-y-6 md:grid-cols-2">
        <div className="space-y-2">
          <FieldLabel htmlFor="gitcode-namespace" required>{t('publish.namespace')}</FieldLabel>
          {isLoadingNamespaces ? (
            <div className="h-[var(--control-height)] animate-shimmer rounded-md" />
          ) : (
            <Select
              disabled={disabled}
              value={normalizeSelectValue(namespaceSlug) ?? EMPTY_NAMESPACE_VALUE}
              onValueChange={(value) => onNamespaceChange(value === EMPTY_NAMESPACE_VALUE ? '' : value)}
            >
              <SelectTrigger data-analytics-id="skill.import.namespace.interact" id="gitcode-namespace">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem data-analytics-id="skill.import.namespace.clear" value={EMPTY_NAMESPACE_VALUE}>{t('publish.selectNamespace')}</SelectItem>
                {namespaces?.map((namespace) => (
                  <SelectItem data-analytics-id="skill.import.namespace.select" key={namespace.id} value={namespace.slug}>
                    {namespace.displayName} (@{namespace.slug})
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          )}
        </div>

        <div className="space-y-2">
          <FieldLabel htmlFor="gitcode-visibility" required>{t('publish.visibility')}</FieldLabel>
          <Select value={visibility} onValueChange={onVisibilityChange} disabled={disabled}>
            <SelectTrigger data-analytics-id="skill.import.visibility.interact" id="gitcode-visibility">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem data-analytics-id="skill.import.visibility.public" value="PUBLIC">{t('publish.visibilityOptions.public')}</SelectItem>
              <SelectItem data-analytics-id="skill.import.visibility.namespace" value="NAMESPACE_ONLY">{namespaceOnlyLabel}</SelectItem>
              <SelectItem data-analytics-id="skill.import.visibility.private" value="PRIVATE">{t('publish.visibilityOptions.private')}</SelectItem>
            </SelectContent>
          </Select>
        </div>
      </div>

      <div className="space-y-2">
        <FieldLabel htmlFor="gitcode-display-name">{t('publish.displayName')}</FieldLabel>
        <Input
          id="gitcode-display-name"
          value={displayName}
          maxLength={200}
          disabled={disabled}
          placeholder={t('publish.displayNamePlaceholder')}
          aria-describedby="gitcode-display-name-help"
          onChange={(event) => onDisplayNameChange(event.target.value)}
        />
        <p id="gitcode-display-name-help" className="text-xs text-muted-foreground">{t('publish.displayNameHelp')}</p>
      </div>

      <div className="space-y-2">
        <FieldLabel htmlFor="gitcode-version" required={versionRequired}>{t('publish.repoVersion')}</FieldLabel>
        <Input
          id="gitcode-version"
          data-analytics-id="skill.import.version.input"
          value={version}
          disabled={disabled}
          placeholder="0.1.0"
          aria-describedby={versionRequired ? 'gitcode-version-help' : undefined}
          onChange={(event) => onVersionChange(event.target.value)}
        />
        {versionRequired ? (
          <p id="gitcode-version-help" className="text-xs text-muted-foreground">{t('publish.repoVersionHint')}</p>
        ) : null}
      </div>

      <div className="space-y-2">
        <FieldLabel htmlFor="gitcode-changelog">{t('publish.changelog')}</FieldLabel>
        <Textarea
          id="gitcode-changelog"
          data-analytics-id="skill.import.changelog.input"
          className="min-h-[104px] rounded-md border-border/60 bg-background px-3 py-2"
          value={changelog}
          maxLength={4000}
          rows={3}
          disabled={disabled}
          placeholder={t('publish.changelogPlaceholder')}
          aria-describedby="gitcode-changelog-help"
          onChange={(event) => onChangelogChange(event.target.value)}
        />
        <div className="flex items-start justify-between gap-4">
          <p id="gitcode-changelog-help" className="text-xs text-muted-foreground">{t('publish.changelogHelp')}</p>
          <span className="flex-shrink-0 text-xs text-muted-foreground tabular-nums">{changelog.length}/4000</span>
        </div>
      </div>

      <div className="space-y-4">
        <SkillAvatarEditor currentUrl={undefined} canRemove={false} disabled={disabled} name={skillName ?? namespaceSlug} onChange={onAvatarChange} />
      </div>
    </div>
  )
}
