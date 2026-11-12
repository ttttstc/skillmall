import { Link, useNavigate } from '@tanstack/react-router'
import { useTranslation } from 'react-i18next'
import { normalizeSearchQuery } from '@/shared/lib/search-query'
import {
  ArrowRight,
  GitBranch,
  PackageOpen,
  Search as SearchIcon,
  Settings,
  Shield,
  Terminal,
  Users,
} from 'lucide-react'
import { LandingQuickStartSection } from '@/shared/components/landing-quick-start'
import { DecoratedSectionTitle } from '@/shared/components/section-title'
import { PopularSkillsSection } from '@/features/home/popular-skills'
import { DomainSkillsSection } from '@/features/home/domain-skills'
import { useInView } from '@/shared/hooks/use-in-view'

/**
 * Marketing-style landing page for unauthenticated and first-time visitors.
 *
 * The page follows the UCD home design: a hero search entry point, the tabbed "热门 Skills"
 * discovery grid, the "领域 Skills" board, then the platform positioning sections.
 */
export function LandingPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()

  const heroView = useInView()
  const featuresView = useInView()
  const quickStartView = useInView()

  const handleSearch = (query: string) => {
    const normalized = normalizeSearchQuery(query)
    navigate({
      to: '/search',
      search: { q: normalized, sort: 'relevance', page: 0, starredOnly: false },
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
            {t('landing.hero.exploreSkills')} <ArrowRight className="h-4 w-4" />
          </Link>
          <Link data-analytics-id="skill.publish.open" to="/dashboard/publish" className="btn-pill btn-pill-primary inline-flex h-12 items-center justify-center px-6">
            {t('landing.hero.publishSkill')}
          </Link>
        </div>
      </main>

      <PopularSkillsSection />

      <DomainSkillsSection />

      <section ref={featuresView.ref} className={`relative z-10 w-full bg-background px-6 py-16 scroll-fade-up${featuresView.inView ? ' in-view' : ''} md:py-20`}>
        <div className="mx-auto max-w-6xl">
          <div className="mb-12">
            <DecoratedSectionTitle title={t('landing.experience.capabilities.eyebrow')} />
            <h3 className="mt-4 text-center text-xl font-medium tracking-tight text-foreground md:text-2xl">
              {t('landing.experience.capabilities.title')}
            </h3>
            <p className="mx-auto mt-3 max-w-2xl text-center text-base leading-relaxed text-muted-foreground">
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
