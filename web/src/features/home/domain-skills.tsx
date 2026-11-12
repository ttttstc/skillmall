import { useState } from 'react'
import { Link, useNavigate } from '@tanstack/react-router'
import { useTranslation } from 'react-i18next'
import {
  ArrowRight,
  Bookmark,
  ClipboardList,
  Code2,
  Download,
  FlaskConical,
  Palette,
  Shapes,
  Wrench,
  type LucideIcon,
} from 'lucide-react'
import { cn } from '@/shared/lib/utils'
import { formatCompactCount } from '@/shared/lib/number-format'
import { DecoratedSectionTitle } from '@/shared/components/section-title'
import { DOMAIN_KEYS, samplesForDomain, type DomainKey } from './domain-skills-data'

const DOMAIN_ICONS: Record<DomainKey, LucideIcon> = {
  design: Palette,
  development: Code2,
  testing: FlaskConical,
  engineering: Wrench,
  projectManagement: ClipboardList,
  other: Shapes,
}

const DOMAIN_TONES: Record<DomainKey, string> = {
  design: 'bg-purple-100 text-purple-700',
  development: 'bg-blue-100 text-blue-700',
  testing: 'bg-pink-100 text-pink-700',
  engineering: 'bg-amber-100 text-amber-700',
  projectManagement: 'bg-emerald-100 text-emerald-700',
  other: 'bg-neutral-100 text-neutral-700',
}

/**
 * Home "领域 Skills" section: one tab per domain plus the rows of the selected domain.
 *
 * Rows come from the sample set in `domain-skills-data.ts` until the registry owns a domain
 * taxonomy; selecting a row hands the name to search instead of linking to a skill detail,
 * because these are preview rows rather than published skills.
 */
export function DomainSkillsSection() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [activeDomain, setActiveDomain] = useState<DomainKey>('design')
  const rows = samplesForDomain(activeDomain)
  const search = { q: '', sort: 'relevance' as const, page: 0, starredOnly: false }

  return (
    <section className="relative z-10 w-full px-6 pb-16 pt-8 text-center md:pb-20 md:pt-12">
      <div className="mx-auto max-w-[1704px]">
        <DecoratedSectionTitle
          title={t('home.domainTitle')}
          subtitle={t('home.domain.subtitle')}
        />

        <div
          role="tablist"
          aria-label={t('home.domainTitle')}
          className="mt-[57px] flex flex-wrap items-center justify-center gap-3"
        >
          {DOMAIN_KEYS.map(domain => {
            const selected = domain === activeDomain
            return (
              <button
                key={domain}
                type="button"
                role="tab"
                aria-selected={selected}
                data-analytics-id="discovery.domain.tab"
                onClick={() => setActiveDomain(domain)}
                className={cn(
                  'inline-flex h-[34px] min-w-[80px] items-center justify-center rounded-full px-[17px] text-sm font-medium leading-none transition-colors focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-ring/15',
                  selected
                    ? 'border border-transparent bg-foreground text-background'
                    : 'border border-border bg-background text-foreground hover:bg-secondary/60',
                )}
              >
                {t(`home.domain.tabs.${domain}`)}
              </button>
            )
          })}
        </div>

        <div className="mt-5 overflow-hidden rounded-xl border border-border/60 bg-card text-left">
          <ul className="divide-y divide-border/60">
            {rows.map(sample => {
              const Icon = DOMAIN_ICONS[sample.domain]
              return (
                <li key={sample.id}>
                  <button
                    type="button"
                    data-analytics-id="discovery.domain.open"
                    onClick={() => navigate({ to: '/search', search: { ...search, q: sample.name } })}
                    className="flex h-[84px] w-full min-w-0 items-center gap-3 px-5 text-left transition-colors hover:bg-secondary/40 focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-ring/15"
                  >
                    <span
                      aria-hidden="true"
                      className={cn('flex h-[42px] w-[42px] shrink-0 items-center justify-center rounded-lg', DOMAIN_TONES[sample.domain])}
                    >
                      <Icon className="h-5 w-5" strokeWidth={1.75} />
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="flex min-w-0 items-center gap-3">
                        <span className="truncate text-base font-semibold text-foreground">{sample.name}</span>
                        <span className="shrink-0 rounded-md bg-secondary px-2 text-xs leading-5 font-medium text-secondary-foreground">
                          {t(`home.domain.tabs.${sample.domain}`)}
                        </span>
                      </span>
                      <span className="mt-1 block truncate text-xs text-muted-foreground">{sample.summary}</span>
                    </span>
                    <span className="ml-auto flex shrink-0 items-center gap-4 pr-[2px] text-sm tabular-nums text-muted-foreground">
                      <span className="flex items-center gap-1.5">
                        <Bookmark aria-hidden="true" className="h-3.5 w-3.5 text-[#F8C030]" />
                        {formatCompactCount(sample.starCount)}
                      </span>
                      <span className="flex items-center gap-1.5">
                        <Download aria-hidden="true" className="h-4 w-4" />
                        {formatCompactCount(sample.downloadCount)}
                      </span>
                    </span>
                  </button>
                </li>
              )
            })}
          </ul>
        </div>

        <Link
          data-analytics-id="discovery.domain.more"
          to="/search"
          search={search}
          className="mt-[38px] inline-flex items-center gap-1 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground"
        >
          {t('home.domain.viewAll')}
          <ArrowRight aria-hidden="true" className="h-4 w-4" />
        </Link>
      </div>
    </section>
  )
}
