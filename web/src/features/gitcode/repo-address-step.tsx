import type { FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { FieldLabel } from '@/features/publish/field-label'
import { Input } from '@/shared/ui/input'

/** Id so the wizard's bottom-bar primary button can submit this form (Enter works too). */
export const GITCODE_ADDRESS_FORM_ID = 'gitcode-repo-address-form'

interface RepoAddressStepProps {
  value: string
  onChange: (value: string) => void
  onSubmit: () => void
  error?: string
  disabled?: boolean
}

/**
 * Step 1 of the import wizard: the repository address.
 *
 * The only field of the step is required, so the wizard disables 下一步 while it is empty and this
 * component only reports format/backend failures inline, under the input.
 */
export function RepoAddressStep({ value, onChange, onSubmit, error, disabled = false }: RepoAddressStepProps) {
  const { t } = useTranslation()
  const errorId = 'gitcode-repo-address-error'

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    onSubmit()
  }

  return (
    <form id={GITCODE_ADDRESS_FORM_ID} data-analytics-id="skill.import.address.submit" onSubmit={handleSubmit} className="space-y-2">
      <FieldLabel htmlFor="gitcode-repo-address" required>{t('publish.repoAddress')}</FieldLabel>
      <Input
        id="gitcode-repo-address"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder={t('publish.repoAddressPlaceholder')}
        autoComplete="off"
        spellCheck={false}
        inputMode="url"
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? errorId : undefined}
        disabled={disabled}
      />
      {error ? <p id={errorId} role="alert" className="text-xs text-destructive">{error}</p> : null}
    </form>
  )
}
