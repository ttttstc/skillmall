import { Link, useNavigate } from '@tanstack/react-router'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { normalizeSearchQuery } from '@/shared/lib/search-query'
import {
  GitBranch,
  LayoutGrid,
  PackageOpen,
  Search as SearchIcon,
  Send,
  Settings,
  Shield,
  Terminal,
  Users,
} from 'lucide-react'
import { LandingQuickStartSection } from '@/shared/components/landing-quick-start'
import { DomainFilter } from '@/features/skill/domain-filter'
import { DomainSkillList } from '@/features/skill/domain-skill-list'
import type { SkillDomain } from '@/shared/lib/skill-domains'
import { PopularSkillsSection } from '@/features/home/popular-skills'
import { SectionHeading } from '@/features/home/section-heading'
import { useInView } from '@/shared/hooks/use-in-view'

/**
 * Marketing-style landing page for unauthenticated and first-time visitors.
 *
 * The page follows the UCD home design: hero search, the tabbed "热门 Skills" discovery grid, the
 * "领域 Skills" board, then the platform positioning sections. The enterprise block and the CTA
 * that used to sit below it were removed on request.
 */
export function LandingPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()

  // One category control drives both the discovery grid and the domain board, so the page never
  // shows two different answers to "what is in this domain".
  const [domain, setDomain] = useState<SkillDomain | undefined>(undefined)

  const handleDomainChange = (next: SkillDomain | undefined) => {
    setDomain(next)
  }

  const heroView = useInView()
  const featuresView = useInView()
  const quickStartView = useInView()

  const handleSkillClick = (namespace: string, slug: string) => {
    navigate({ to: `/space/${namespace}/${encodeURIComponent(slug)}` })
  }

  const handleSearch = (query: string) => {
    const normalized = normalizeSearchQuery(query)
    navigate({
      to: '/search',
      search: { q: normalized, sort: 'relevance', page: 0, starredOnly: false, domain },
    })
  }

  const features = [
    {
      icon: <Shield className="h-5 w-5" strokeWidth={1.75} />,
      title: t('landing.features.secure.title'),
      description: t('landing.features.secure.description'),
    },
    {
      icon: <Users className="h-5 w-5" strokeWidth={1.75} />,
      title: t('landing.features.community.title'),
      description: t('landing.features.community.description'),
    },
    {
      icon: <PackageOpen className="h-5 w-5" strokeWidth={1.75} />,
      title: t('landing.features.integration.title'),
      description: t('landing.features.integration.description'),
    },
    {
      icon: <GitBranch className="h-5 w-5" strokeWidth={1.75} />,
      title: t('landing.features.versionControl.title'),
      description: t('landing.features.versionControl.description'),
    },
    {
      icon: <Terminal className="h-5 w-5" strokeWidth={1.75} />,
      title: t('landing.features.cli.title'),
      description: t('landing.features.cli.description'),
    },
    {
      icon: <Settings className="h-5 w-5" strokeWidth={1.75} />,
      title: t('landing.features.governance.title'),
      description: t('landing.features.governance.description'),
    },
  ]

  return (
    <>
      <main ref={heroView.ref} className={`relative z-10 w-full overflow-hidden px-6 pb-10 pt-20 text-center scroll-fade-up${heroView.inView ? ' in-view' : ''} md:pb-12 md:pt-28`}>
        <div className="absolute inset-0 -z-10 bg-[radial-gradient(ellipse_at_75%_55%,rgba(194,218,255,0.28),transparent_45%),radial-gradient(ellipse_at_25%_80%,rgba(255,225,225,0.22),transparent_45%)]" />
        <h1 className="text-5xl font-bold tracking-tight text-foreground md:text-7xl">SkillMall</h1>
        <p className="mt-3 text-base text-muted-foreground md:text-lg">{t('landing.hero.mallSubtitle')}</p>
        <div className="mx-auto mt-10 flex max-w-6xl flex-col items-stretch gap-3 lg:flex-row lg:items-center">
          <form className="flex h-12 min-w-0 flex-1 items-center gap-3 rounded-full border border-border bg-background px-5 text-left focus-within:border-ring" onSubmit={(event) => {
            event.preventDefault()
            handleSearch(String(new FormData(event.currentTarget).get('query') ?? ''))
          }}>
            <SearchIcon className="h-5 w-5 shrink-0 text-muted-foreground" aria-hidden="true" />
            <input data-analytics-id="discovery.query.enter" name="query" type="search" aria-label={t('landing.hero.searchPlaceholder')} placeholder={t('landing.hero.searchPlaceholder')} className="min-w-0 flex-1 bg-transparent text-base text-foreground outline-none placeholder:text-muted-foreground" />
          </form>
          <Link data-analytics-id="navigation.skills.open" to="/search" search={{ q: '', sort: 'relevance', page: 0, starredOnly: false }} className="btn-pill btn-pill-outline inline-flex h-12 items-center justify-center gap-2 px-6">
            <LayoutGrid className="h-4 w-4" /> {t('landing.hero.exploreSkills')}
          </Link>
          <Link data-analytics-id="skill.publish.open" to="/dashboard/publish" className="btn-pill btn-pill-primary inline-flex h-12 items-center justify-center gap-2 px-6">
            <Send className="h-4 w-4" /> {t('landing.hero.publishSkill')}
          </Link>
        </div>
      </main>

      <PopularSkillsSection domain={domain} onSelect={handleSkillClick} />

      <section className="relative z-10 w-full px-6 pb-16 pt-8 text-center md:pb-20 md:pt-12">
        <div className="mx-auto max-w-[1704px] space-y-6">
          <SectionHeading>{t('home.domainTitle')}</SectionHeading>
          <DomainFilter value={domain} onChange={handleDomainChange} />
          <div className="mx-auto max-w-5xl text-left">
            <DomainSkillList domain={domain} onSelect={handleSkillClick} />
          </div>
        </div>
      </section>

      <section ref={featuresView.ref} className={`relative z-10 w-full bg-background px-6 py-16 scroll-fade-up${featuresView.inView ? ' in-view' : ''} md:py-20`}>
        <div className="mx-auto max-w-6xl">
          <div className="mb-12 max-w-3xl">
            <p className="mb-3 text-[11px] font-semibold uppercase tracking-[0.16em] text-muted-foreground">{t('landing.experience.capabilities.eyebrow')}</p>
            <h2 className="mb-4 text-3xl font-medium tracking-tight text-foreground md:text-4xl">
              {t('landing.experience.capabilities.title')}
            </h2>
            <p className="max-w-2xl text-lg leading-relaxed text-muted-foreground">
              {t('landing.experience.capabilities.description')}
            </p>
          </div>

          <div className="grid grid-cols-1 overflow-hidden rounded-2xl border border-border/70 bg-border/70 md:grid-cols-2 lg:grid-cols-3">
            {features.map((feature) => (
              <div key={feature.title} className="bg-card p-7 transition-colors hover:bg-secondary/50">
                <div className="mb-5 flex h-10 w-10 items-center justify-center rounded-lg bg-secondary text-foreground">
                  {feature.icon}
                </div>
                <h3 className="mb-2 text-base font-semibold text-foreground">{feature.title}</h3>
                <p className="text-sm leading-relaxed text-muted-foreground">{feature.description}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      <div ref={quickStartView.ref} className={`scroll-fade-up${quickStartView.inView ? ' in-view' : ''}`}>
        <LandingQuickStartSection />
      </div>
    </>
  )
}
