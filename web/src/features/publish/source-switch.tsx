import { useTranslation } from 'react-i18next'
import { cn } from '@/shared/lib/utils'

/** Where the published skill package comes from. */
export type PublishSource = 'local' | 'gitcode'

interface PublishSourceSwitchProps {
  value: PublishSource
  onChange: (value: PublishSource) => void
  disabled?: boolean
}

const OPTION_CLASS_NAME = 'h-9 rounded-md px-5 text-sm transition-colors duration-150 ease-out'
const SELECTED_CLASS_NAME = 'bg-background font-medium text-foreground shadow-[0_1px_2px_0_rgb(0_0_0/0.08)]'
const IDLE_CLASS_NAME = 'text-muted-foreground hover:text-foreground'

/**
 * Segmented control that selects the publishing source (UCD 发布-代码仓导入.png).
 *
 * The control only decides which panel is visible. Both panels keep their own state, so a round
 * trip through 代码仓导入 never clears a chosen package, its preview or the basic-info fields of
 * the local upload.
 */
export function PublishSourceSwitch({ value, onChange, disabled = false }: PublishSourceSwitchProps) {
  const { t } = useTranslation()

  return (
    <div role="group" className="inline-flex items-center rounded-lg bg-muted p-1">
      <button
        type="button"
        aria-pressed={value === 'local'}
        disabled={disabled}
        data-analytics-id={value === 'local' ? undefined : 'skill.import.source.local'}
        onClick={() => onChange('local')}
        className={cn(OPTION_CLASS_NAME, value === 'local' ? SELECTED_CLASS_NAME : IDLE_CLASS_NAME, disabled && 'cursor-not-allowed opacity-60')}
      >
        {t('publish.source.local')}
      </button>
      <button
        type="button"
        aria-pressed={value === 'gitcode'}
        disabled={disabled}
        data-analytics-id={value === 'gitcode' ? undefined : 'skill.import.source.repo'}
        onClick={() => onChange('gitcode')}
        className={cn(OPTION_CLASS_NAME, value === 'gitcode' ? SELECTED_CLASS_NAME : IDLE_CLASS_NAME, disabled && 'cursor-not-allowed opacity-60')}
      >
        {t('publish.source.repo')}
      </button>
    </div>
  )
}
