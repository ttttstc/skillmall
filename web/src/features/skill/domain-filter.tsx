import { useTranslation } from 'react-i18next'
import { Button } from '@/shared/ui/button'
import { cn } from '@/shared/lib/utils'
import { SKILL_DOMAINS, type SkillDomain } from '@/shared/lib/skill-domains'

interface DomainFilterProps {
  /** Selected category, or undefined for "all domains". */
  value?: SkillDomain
  onChange: (domain: SkillDomain | undefined) => void
  /** `start` keeps the chips left-aligned inside the list page's sidebar. */
  align?: 'center' | 'start'
  className?: string
}

/**
 * Category chips for the public discovery surfaces.
 *
 * A radio-like group rather than a dropdown: six options fit on one row, every click is one
 * request instead of two, and `aria-pressed` tells assistive tech which filter is active.
 *
 * The same control is used on the landing page, the home lists and the Skill list page, so those
 * surfaces cannot disagree about which category is on screen.
 */
export function DomainFilter({ value, onChange, align = 'center', className }: DomainFilterProps) {
  const { t } = useTranslation()
  const options: Array<SkillDomain | undefined> = [undefined, ...SKILL_DOMAINS]

  return (
    <div
      role="group"
      aria-label={t('home.domainFilterLabel')}
      className={cn(
        'flex flex-wrap items-center gap-2',
        align === 'start' ? 'justify-start' : 'justify-center',
        className,
      )}
    >
      {options.map(domain => {
        const selected = value === domain
        return (
          <Button
            key={domain ?? 'all'}
            type="button"
            size="sm"
            variant={selected ? 'default' : 'outline'}
            aria-pressed={selected}
            // Analytics ids must be literals: the manifest generator rejects template strings.
            data-analytics-id={
              domain === 'DESIGN' ? 'discovery.domain.design'
              : domain === 'DEVELOPMENT' ? 'discovery.domain.development'
              : domain === 'TESTING' ? 'discovery.domain.testing'
              : domain === 'ENGINEERING' ? 'discovery.domain.engineering'
              : domain === 'PROJECT_MANAGEMENT' ? 'discovery.domain.project.management'
              : domain === 'OTHER' ? 'discovery.domain.other'
              : 'discovery.domain.all'
            }
            onClick={() => onChange(domain)}
          >
            {domain ? t(`home.domain.${domain}`) : t('home.domainAll')}
          </Button>
        )
      })}
    </div>
  )
}
