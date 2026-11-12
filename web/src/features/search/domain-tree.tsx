import { useTranslation } from 'react-i18next'
import { ChevronDown, ChevronUp, Folder } from 'lucide-react'
import { cn } from '@/shared/lib/utils'

/**
 * Domain rail for the "all skills" page, matching the UCD layout (row height, indentation,
 * folder/chevron glyphs and the expanded root state).
 *
 * There is no domain taxonomy endpoint yet, so this tree is presentation only: no row is
 * interactive, no skill count or child domain is invented, and the expanded root says that domain
 * filtering is not open instead of listing made-up children.
 *
 * The eight names below are the UCD taxonomy, not registry data. They are translation keys only;
 * the tree stays presentation-only until a domain taxonomy endpoint exists.
 */
const DOMAIN_KEYS = [
  'ascend',
  'kunpeng',
  'yueying',
  'design',
  'testing',
  'itEquipment',
  'projectManagement',
  'other',
] as const

const ROW_CLASS_NAME = 'flex h-9 min-w-0 items-center gap-2 rounded-lg pl-2 pr-2 text-sm text-foreground'
const GLYPH_CLASS_NAME = 'h-3.5 w-3.5 shrink-0 text-muted-foreground'

export function DomainTree() {
  const { t } = useTranslation()

  return (
    <nav aria-label={t('home.domainTitle')} className="min-w-0 select-none">
      <ul className="min-w-0">
        {DOMAIN_KEYS.map((domain, index) => {
          const name = t(`domainTree.${domain}`)
          // The UCD mock shows the first domain expanded; keep that state so the rail opens with
          // the same rhythm without claiming the first domain has real children.
          const expanded = index === 0
          const Chevron = expanded ? ChevronUp : ChevronDown

          return (
            <li key={domain} className="min-w-0">
              <div className={cn(ROW_CLASS_NAME, expanded && 'bg-muted font-medium')} aria-disabled="true">
                <Folder aria-hidden="true" className={cn(GLYPH_CLASS_NAME, 'fill-current')} />
                <span className="truncate">{name}</span>
                <Chevron aria-hidden="true" className={cn(GLYPH_CLASS_NAME, 'ml-auto')} />
              </div>
              {expanded ? (
                <p className="flex min-h-9 items-center pb-1 pl-12 pr-2 text-xs leading-5 text-muted-foreground">
                  {t('home.domainUnavailable')}
                </p>
              ) : null}
            </li>
          )
        })}
      </ul>
    </nav>
  )
}
