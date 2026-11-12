import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import { useTranslation } from 'react-i18next'
import { ArrowRight, RefreshCw } from 'lucide-react'
import type { SkillSummary } from '@/api/types'
import type { SkillDomain } from '@/shared/lib/skill-domains'
import { SkillCard } from '@/features/skill/skill-card'
import { SkeletonList } from '@/shared/components/skeleton-loader'
import { LoadError } from '@/shared/components/load-error'
import { useHomeRanking, useHomeRecommendations } from '@/shared/hooks/use-home-discovery'
import { cn } from '@/shared/lib/utils'
import { SectionHeading } from './section-heading'

const GRID_SIZE = 8

type PopularTab = 'recommend' | 'recent' | 'cumulative' | 'newest'

const TABS: PopularTab[] = ['recommend', 'recent', 'cumulative', 'newest']

const TAB_LABEL_KEYS: Record<PopularTab, string> = {
  recommend: 'home.popular.tabs.recommend',
  recent: 'home.ranking.recent.title',
  cumulative: 'home.ranking.cumulative.title',
  newest: 'home.ranking.newest.title',
}

const TAB_DESCRIPTION_KEYS: Record<PopularTab, string> = {
  recommend: 'home.recommend.description',
  recent: 'home.ranking.recent.description',
  cumulative: 'home.ranking.cumulative.description',
  newest: 'home.ranking.newest.description',
}

const TAB_EMPTY_KEYS: Record<PopularTab, string> = {
  recommend: 'home.recommend.empty',
  recent: 'home.ranking.recent.empty',
  cumulative: 'home.ranking.cumulative.empty',
  newest: 'home.ranking.newest.empty',
}

export interface PopularSkillsSectionProps {
  domain?: SkillDomain
  onSelect: (namespace: string, slug: string) => void
}

/**
 * Home discovery section of the UCD design: one title, four switching tabs and the same card grid
 * for every source.
 *
 * The three rankings used to be three separate lists side by side; the design shows them as tabs of
 * a single "热门 Skills" block, which is also what the product asked for. Switching swaps the query
 * behind one grid instead of stacking three lists.
 */
export function PopularSkillsSection({ domain, onSelect }: PopularSkillsSectionProps) {
  const { t } = useTranslation()
  const [activeTab, setActiveTab] = useState<PopularTab>('recommend')
  const [rotation, setRotation] = useState(0)
  const [shownIds, setShownIds] = useState<number[]>([])

  const recommendations = useHomeRecommendations(GRID_SIZE, shownIds, rotation, domain)
  const recent = useHomeRanking('recent-popular', GRID_SIZE, domain)
  const cumulative = useHomeRanking('most-downloaded', GRID_SIZE, domain)
  const newest = useHomeRanking('newest', GRID_SIZE, domain)

  const ranking = activeTab === 'recent' ? recent : activeTab === 'cumulative' ? cumulative : newest

  const skills: SkillSummary[] =
    activeTab === 'recommend'
      ? recommendations.data ?? []
      : ranking.data?.items.map(item => item.skill) ?? []

  const isLoading = activeTab === 'recommend' ? recommendations.isLoading : ranking.isLoading
  const isError = activeTab === 'recommend' ? recommendations.isError : ranking.isError
  const retry = activeTab === 'recommend' ? recommendations.refetch : ranking.refetch
  const emptyLabel = domain ? t('home.domainEmpty') : t(TAB_EMPTY_KEYS[activeTab])

  const handleShuffle = () => {
    setShownIds(skills.map(skill => skill.id))
    setRotation(current => current + 1)
  }

  return (
    <section className="relative z-10 w-full px-6 pb-16 pt-8 md:pb-20 md:pt-12">
      <div className="mx-auto max-w-[1704px]">
        <SectionHeading>{t('home.popular.title')}</SectionHeading>
        <p className="mt-2 text-center text-sm text-muted-foreground">{t(TAB_DESCRIPTION_KEYS[activeTab])}</p>

        <div
          role="tablist"
          aria-label={t('home.popular.title')}
          className="mt-[58px] flex flex-wrap items-center justify-center gap-3.5"
        >
          {TABS.map(tab => {
            const selected = tab === activeTab
            return (
              <button
                key={tab}
                type="button"
                role="tab"
                aria-selected={selected}
                data-analytics-id="discovery.popular.tab"
                onClick={() => setActiveTab(tab)}
                className={cn(
                  'inline-flex h-[34px] items-center justify-center rounded-full border px-[17px] text-sm font-medium leading-none transition-colors focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-ring/15',
                  selected
                    ? 'border-transparent bg-foreground text-background'
                    : 'border-border bg-background text-foreground hover:bg-secondary/60',
                )}
              >
                {t(TAB_LABEL_KEYS[tab])}
              </button>
            )
          })}
        </div>

        <div className="mt-5">
          {isLoading ? (
            <SkeletonList count={GRID_SIZE} />
          ) : isError ? (
            <LoadError compact onRetry={() => { void retry() }} />
          ) : skills.length === 0 ? (
            <div
              data-analytics-id={activeTab === 'recommend' ? 'discovery.recommend.empty' : undefined}
              className="rounded-xl border border-border/60 bg-card px-6 py-12 text-center text-sm text-muted-foreground"
            >
              {emptyLabel}
            </div>
          ) : (
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
              {skills.map(skill => (
                <SkillCard
                  key={skill.id}
                  analyticsId="skill.card.open"
                  skill={skill}
                  market
                  onClick={() => onSelect(skill.namespace, skill.slug)}
                />
              ))}
            </div>
          )}
        </div>

        <div className="mt-9 flex flex-wrap items-center justify-center gap-10">
          {activeTab === 'recommend' ? (
            <button
              type="button"
              data-analytics-id="discovery.recommend.shuffle"
              disabled={isLoading}
              onClick={handleShuffle}
              className="inline-flex items-center gap-[9px] text-sm font-medium text-muted-foreground transition-colors hover:text-foreground disabled:opacity-50"
            >
              <RefreshCw aria-hidden="true" className="h-4 w-4" />
              {t('home.recommend.shuffle')}
            </button>
          ) : null}
          <Link
            data-analytics-id="discovery.popular.more"
            to="/search"
            search={{ q: '', sort: 'relevance', page: 0, starredOnly: false, domain }}
            className="inline-flex items-center gap-1 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground"
          >
            {t('home.popular.viewAll')}
            <ArrowRight aria-hidden="true" className="h-4 w-4" />
          </Link>
        </div>

        <p className="mt-6 text-center text-xs text-muted-foreground">{t('home.ranking.downloadsNote')}</p>
      </div>
    </section>
  )
}