import { Link, useNavigate } from '@tanstack/react-router'
import { useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { normalizeSearchQuery } from '@/shared/lib/search-query'
import {
  CheckCircle2,
  LayoutGrid,
  Send,
  GitBranch,
  Lock,
  Monitor,
  PackageOpen,
  Search as SearchIcon,
  Server,
  Settings,
  Shield,
  Terminal,
  Users,
} from 'lucide-react'
import { LandingQuickStartSection } from '@/shared/components/landing-quick-start'
import { SkillCard } from '@/features/skill/skill-card'
import { DomainFilter } from '@/features/skill/domain-filter'
import { DomainSkillList } from '@/features/skill/domain-skill-list'
import type { SkillDomain } from '@/shared/lib/skill-domains'
import { SkeletonList } from '@/shared/components/skeleton-loader'
import { LoadError } from '@/shared/components/load-error'
import { RankingList, type RankingRowView } from '@/features/home/ranking-list'
import { useHomeRanking, useHomeRecommendations } from '@/shared/hooks/use-home-discovery'
import { useInView } from '@/shared/hooks/use-in-view'
import { Button } from '@/shared/ui/button'
import { formatCompactCount } from '@/shared/lib/number-format'
import { formatRelativeTime } from '@/shared/lib/format-relative-time'
import type { HomeRankingItem } from '@/api/types'

const RECOMMENDATION_SIZE = 6
const RANKING_SIZE = 8

type RankingKind = 'recent' | 'cumulative' | 'newest'

/**
 * Laurel branch drawn as a curved stem with leaves, used to frame the
 * discovery section headings the way the UCD home page does.
 */
function LaurelBranch({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 34" aria-hidden="true" className={className} fill="currentColor">
      <path
        d="M28.6 32c-8.4-2.6-15-8.6-18.4-16.6"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.3"
        strokeLinecap="round"
      />
      <ellipse cx="8.6" cy="13.4" rx="4.6" ry="1.9" transform="rotate(-38 8.6 13.4)" opacity="0.9" />
      <ellipse cx="12.4" cy="18.8" rx="4.9" ry="2" transform="rotate(-30 12.4 18.8)" opacity="0.9" />
      <ellipse cx="17.4" cy="23.4" rx="4.9" ry="2" transform="rotate(-18 17.4 23.4)" opacity="0.9" />
      <ellipse cx="23.2" cy="27" rx="4.6" ry="1.9" transform="rotate(-8 23.2 27)" opacity="0.9" />
      <ellipse cx="12.8" cy="10.6" rx="3.4" ry="1.5" transform="rotate(24 12.8 10.6)" opacity="0.5" />
      <ellipse cx="17.8" cy="15.4" rx="3.4" ry="1.5" transform="rotate(32 17.8 15.4)" opacity="0.5" />
      <ellipse cx="23.4" cy="20.6" rx="3.2" ry="1.4" transform="rotate(40 23.4 20.6)" opacity="0.5" />
    </svg>
  )
}

/** Centered discovery heading with the UCD laurel decorations and title colour. */
function SectionHeading({ children, className = '' }: { children: ReactNode; className?: string }) {
  return (
    <div className={`flex items-center justify-center gap-3 ${className}`}>
      <LaurelBranch className="h-8 w-8 -scale-x-100 text-[#808080]" />
      <h2 className="text-3xl font-semibold tracking-tight text-[#182333]">{children}</h2>
      <LaurelBranch className="h-8 w-8 text-[#808080]" />
    </div>
  )
}

function EnterpriseSection() {
  const { t } = useTranslation()

  return (
    <section className="w-full bg-background px-6 py-16 md:py-20">
      <div className="mx-auto max-w-6xl">
        <div className="text-center mb-12">
          <p className="mb-3 text-[11px] font-semibold uppercase tracking-[0.16em] text-muted-foreground">Enterprise</p>
          <h2 className="mb-3 text-2xl font-medium tracking-tight text-foreground md:text-3xl">{t('landing.experience.enterprise.title')}</h2>
          <p className="text-muted-foreground">{t('landing.experience.enterprise.description')}</p>
        </div>

        <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
          <div className="md:col-span-2 md:row-span-2 flex flex-col rounded-xl border border-border/70 bg-card p-6 shadow-[var(--shadow-card)]">
            <div className="mb-3 flex items-center gap-3">
              <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-neutral-900 text-white">
                <Server className="h-4 w-4" />
              </div>
              <h3 className="font-semibold text-foreground">{t('landing.experience.enterprise.deployment.title')}</h3>
            </div>
            <p className="mb-4 text-sm leading-relaxed text-muted-foreground">
              {t('landing.experience.enterprise.deployment.description')}
            </p>
            <div className="flex-1 rounded-lg border border-border/70 bg-secondary/50 p-4">
              <div className="mb-3 flex flex-col items-stretch justify-between gap-2 sm:flex-row sm:items-center">
                <div className="flex flex-col gap-1.5 sm:flex-shrink-0">
                  <div className="mb-0.5 text-center text-[10px] text-muted-foreground">{t('landing.experience.enterprise.clientLayer')}</div>
                  <div className="rounded-md border border-border/70 bg-background px-3 py-1.5 text-center text-[11px] font-medium text-muted-foreground">Agent</div>
                  <div className="rounded-md border border-border/70 bg-background px-3 py-1.5 text-center text-[11px] font-medium text-muted-foreground">CLI</div>
                  <div className="rounded-md border border-border/70 bg-background px-3 py-1.5 text-center text-[11px] font-medium text-muted-foreground">Web</div>
                </div>
                <div className="flex h-8 items-center justify-center sm:h-auto sm:min-w-0 sm:flex-1">
                  <svg className="h-4 w-16 rotate-90 text-border sm:w-full sm:max-w-16 sm:rotate-0" fill="none" stroke="currentColor" viewBox="0 0 64 16"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5" strokeDasharray="3 3" d="M0 8h54" /><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5" d="M50 4l6 4-6 4" /></svg>
                </div>
                <div className="flex flex-col items-center sm:flex-shrink-0">
                  <div className="mb-0.5 text-center text-[10px] text-muted-foreground">{t('landing.experience.enterprise.serviceLayer')}</div>
                  <div className="rounded-lg bg-neutral-900 px-3.5 py-3 text-center text-xs font-semibold leading-tight text-white">SkillHub<br />Registry</div>
                </div>
                <div className="flex h-8 items-center justify-center sm:h-auto sm:min-w-0 sm:flex-1">
                  <svg className="h-4 w-16 rotate-90 text-border sm:w-full sm:max-w-16 sm:rotate-0" fill="none" stroke="currentColor" viewBox="0 0 64 16"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5" strokeDasharray="3 3" d="M0 8h54" /><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5" d="M50 4l6 4-6 4" /></svg>
                </div>
                <div className="flex flex-col gap-1.5 sm:flex-shrink-0">
                  <div className="mb-0.5 text-center text-[10px] text-muted-foreground">{t('landing.experience.enterprise.storageLayer')}</div>
                  <div className="rounded-md border border-border/70 bg-background px-3 py-1.5 text-center text-[11px] font-medium text-muted-foreground">PostgreSQL</div>
                  <div className="rounded-md border border-border/70 bg-background px-3 py-1.5 text-center text-[11px] font-medium text-muted-foreground">Redis</div>
                  <div className="rounded-md border border-border/70 bg-background px-3 py-1.5 text-center text-[11px] font-medium text-muted-foreground">MinIO / S3</div>
                </div>
              </div>
              <div className="border-t border-dashed border-border/70 pt-2.5 text-center text-[10px] text-muted-foreground">{t('landing.experience.enterprise.networkBoundary')}</div>
            </div>
          </div>

          <div className="rounded-xl border border-border/70 bg-card p-5 shadow-[var(--shadow-card)]">
            <div className="mb-2 flex items-center gap-3">
              <Shield className="h-5 w-5 text-foreground" />
              <h3 className="text-sm font-semibold text-foreground">{t('landing.experience.enterprise.security.title')}</h3>
            </div>
            <p className="text-sm leading-relaxed text-muted-foreground">{t('landing.experience.enterprise.security.description')}</p>
          </div>

          <div className="rounded-xl border border-border/70 bg-card p-5 shadow-[var(--shadow-card)]">
            <div className="mb-2 flex items-center gap-3">
              <Lock className="h-5 w-5 text-foreground" />
              <h3 className="text-sm font-semibold text-foreground">{t('landing.experience.enterprise.rbac.title')}</h3>
            </div>
            <p className="text-sm leading-relaxed text-muted-foreground">{t('landing.experience.enterprise.rbac.description')}</p>
          </div>

          <div className="rounded-xl border border-border/70 bg-card p-5 shadow-[var(--shadow-card)]">
            <div className="mb-2 flex items-center gap-3">
              <Settings className="h-5 w-5 text-foreground" />
              <h3 className="text-sm font-semibold text-foreground">{t('landing.experience.enterprise.access.title')}</h3>
            </div>
            <p className="text-sm leading-relaxed text-muted-foreground">{t('landing.experience.enterprise.access.description')}</p>
          </div>

          <div className="rounded-xl border border-border/70 bg-card p-5 shadow-[var(--shadow-card)]">
            <div className="mb-2 flex items-center gap-3">
              <Monitor className="h-5 w-5 text-foreground" />
              <h3 className="text-sm font-semibold text-foreground">{t('landing.experience.enterprise.audit.title')}</h3>
            </div>
            <p className="text-sm leading-relaxed text-muted-foreground">{t('landing.experience.enterprise.audit.description')}</p>
          </div>

          <div className="rounded-xl border border-border/70 bg-card p-5 shadow-[var(--shadow-card)]">
            <div className="mb-2 flex items-center gap-3">
              <GitBranch className="h-5 w-5 text-foreground" />
              <h3 className="text-sm font-semibold text-foreground">{t('landing.experience.enterprise.openSource.title')}</h3>
            </div>
            <p className="text-sm leading-relaxed text-muted-foreground">{t('landing.experience.enterprise.openSource.description')}</p>
          </div>
        </div>
      </div>
    </section>
  )
}

/**
 * Marketing-style landing page for unauthenticated and first-time visitors.
 *
 * The page mixes static positioning content with live skill queries so popular and latest skills
 * stay aligned with the current registry state.
 */
export function LandingPage() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()

  // "换一换" rotates by excluding what is already on screen and bumping a nonce, so the next draw
  // is a fresh random sample rather than a cached replay.
  const [rotation, setRotation] = useState(0)
  const [shownIds, setShownIds] = useState<number[]>([])
  // One category control drives the recommendation row and all three rankings, so the page never
  // shows two different answers to "what is in this domain".
  const [domain, setDomain] = useState<SkillDomain | undefined>(undefined)

  const handleDomainChange = (next: SkillDomain | undefined) => {
    setDomain(next)
    setShownIds([])
    setRotation(current => current + 1)
  }

  const {
    data: recommended,
    isLoading: isLoadingRecommended,
    isError: isErrorRecommended,
    refetch: refetchRecommended,
  } = useHomeRecommendations(RECOMMENDATION_SIZE, shownIds, rotation, domain)

  const recentPopular = useHomeRanking('recent-popular', RANKING_SIZE, domain)
  const mostDownloaded = useHomeRanking('most-downloaded', RANKING_SIZE, domain)
  const newest = useHomeRanking('newest', RANKING_SIZE, domain)

  const handleSkillClick = (namespace: string, slug: string) => {
    navigate({ to: `/space/${namespace}/${encodeURIComponent(slug)}` })
  }

  const handleShuffle = () => {
    setShownIds((recommended ?? []).map(skill => skill.id))
    setRotation(current => current + 1)
  }

  const formatRankingMeta = (item: HomeRankingItem, kind: RankingKind): string | null => {
    if (kind === 'recent') {
      // The windowed number only. Never the cumulative counter: they answer different questions.
      return item.windowDownloadCount == null
        ? null
        : t('home.ranking.windowDownloads', { value: formatCompactCount(item.windowDownloadCount) })
    }
    if (kind === 'cumulative') {
      return t('home.ranking.totalDownloads', { value: formatCompactCount(item.skill.downloadCount) })
    }
    return item.firstPublishedAt ? formatRelativeTime(item.firstPublishedAt, i18n.language) : null
  }

  const rankingRows = (items: HomeRankingItem[] | undefined, kind: RankingKind): RankingRowView[] =>
    (items ?? []).map(item => ({ skill: item.skill, meta: formatRankingMeta(item, kind) }))

  const heroView = useInView()
  const featuresView = useInView()
  const quickStartView = useInView()
  const enterpriseView = useInView()
  const discoverView = useInView()
  const rankingsView = useInView()

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

      <section ref={discoverView.ref} className={`relative z-10 w-full px-6 pb-16 pt-8 scroll-fade-up${discoverView.inView ? ' in-view' : ''} md:pb-20 md:pt-12`}>
        <div className="mx-auto max-w-[1704px] space-y-6">
          <div className="flex flex-col gap-4 text-center">
            <div>
              <SectionHeading className="mb-2">{t('home.recommend.title')}</SectionHeading>
              <p className="text-sm text-muted-foreground">{t('home.recommend.description')}</p>
            </div>
            <DomainFilter value={domain} onChange={handleDomainChange} />
            <div>
              <Button data-analytics-id="discovery.recommend.shuffle"
                variant="ghost"
                disabled={isLoadingRecommended}
                onClick={handleShuffle}
              >
                {t('home.recommend.shuffle')}
              </Button>
            </div>
          </div>
          {isLoadingRecommended ? (
            <SkeletonList count={RECOMMENDATION_SIZE} />
          ) : isErrorRecommended ? (
            <LoadError compact onRetry={() => { void refetchRecommended() }} />
          ) : (recommended?.length ?? 0) === 0 ? (
            <div data-analytics-id="discovery.recommend.empty" className="rounded-xl border border-border/60 bg-card px-6 py-12 text-center text-sm text-muted-foreground">
              {domain ? t('home.domainEmpty') : t('home.recommend.empty')}
            </div>
          ) : (
            <div className="grid grid-cols-1 gap-5 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
              {recommended?.map((skill, idx) => (
                <div key={skill.id} className={`animate-fade-up delay-${Math.min(idx + 1, 6)}`}>
                  <SkillCard analyticsId="skill.card.open" skill={skill} market onClick={() => handleSkillClick(skill.namespace, skill.slug)} />
                </div>
              ))}
            </div>
          )}
        </div>
      </section>

      <section ref={rankingsView.ref} className={`relative z-10 w-full px-6 pb-16 pt-4 scroll-fade-up${rankingsView.inView ? ' in-view' : ''} md:pb-20 md:pt-6`}>
        <div className="mx-auto max-w-[1704px] space-y-4">
          <div className="grid grid-cols-1 gap-5 lg:grid-cols-3">
            <RankingList
              title={t('home.ranking.recent.title')}
              description={t('home.ranking.recent.description')}
              analyticsId="discovery.ranking.recent"
              rows={rankingRows(recentPopular.data?.items, 'recent')}
              isLoading={recentPopular.isLoading}
              isError={recentPopular.isError}
              errorLabel={t('home.ranking.error')}
              emptyLabel={domain ? t('home.domainEmpty') : t('home.ranking.recent.empty')}
              onSelect={skill => handleSkillClick(skill.namespace, skill.slug)}
            />
            <RankingList
              title={t('home.ranking.cumulative.title')}
              description={t('home.ranking.cumulative.description')}
              analyticsId="discovery.ranking.cumulative"
              rows={rankingRows(mostDownloaded.data?.items, 'cumulative')}
              isLoading={mostDownloaded.isLoading}
              isError={mostDownloaded.isError}
              errorLabel={t('home.ranking.error')}
              emptyLabel={domain ? t('home.domainEmpty') : t('home.ranking.cumulative.empty')}
              onSelect={skill => handleSkillClick(skill.namespace, skill.slug)}
            />
            <RankingList
              title={t('home.ranking.newest.title')}
              description={t('home.ranking.newest.description')}
              analyticsId="discovery.ranking.newest"
              rows={rankingRows(newest.data?.items, 'newest')}
              isLoading={newest.isLoading}
              isError={newest.isError}
              errorLabel={t('home.ranking.error')}
              emptyLabel={domain ? t('home.domainEmpty') : t('home.ranking.newest.empty')}
              onSelect={skill => handleSkillClick(skill.namespace, skill.slug)}
            />
          </div>
          <p className="text-center text-xs text-muted-foreground">{t('home.ranking.downloadsNote')}</p>
        </div>
      </section>

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

      <div ref={enterpriseView.ref} className={`scroll-fade-up${enterpriseView.inView ? ' in-view' : ''}`}>
        <EnterpriseSection />
      </div>

      <section className="relative z-10 w-full border-t border-border/70 bg-background px-6 py-16 text-center md:py-20">
        <div className="mx-auto max-w-4xl">
          <h2 className="mb-3 text-2xl font-medium tracking-tight text-foreground md:text-3xl">{t('landing.experience.cta.title')}</h2>
          <p className="mb-8 text-muted-foreground">{t('landing.experience.cta.description')}</p>
          <div className="flex flex-wrap items-center justify-center gap-3">
            <a data-analytics-id="navigation.repository.open" href="https://github.com/iflytek/skillhub" target="_blank" rel="noreferrer" className="btn-pill btn-pill-primary">
              {t('landing.experience.cta.deploy')}
            </a>
            <a data-analytics-id="navigation.documentation.open" href="https://iflytek.github.io/skillhub/" target="_blank" rel="noreferrer" className="group btn-pill btn-pill-outline inline-flex items-center gap-2">
              {t('landing.experience.cta.docs')} <CheckCircle2 className="h-4 w-4" />
            </a>
          </div>
        </div>
      </section>
    </>
  )
}
