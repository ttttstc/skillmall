import { describe, expect, it, vi } from 'vitest'

const recommendationFixture = vi.hoisted(() => ({
  isLoading: false,
  isError: false,
  data: [] as unknown[],
}))

vi.mock('@tanstack/react-router', () => ({
  Link: ({ children }: { children: unknown }) => children,
  useNavigate: () => vi.fn(),
}))

vi.mock('react-i18next', async () => {
  const actual = await vi.importActual<typeof import('react-i18next')>('react-i18next')
  return {
    ...actual,
    useTranslation: () => ({
      t: (key: string) => key,
    }),
  }
})

vi.mock('lucide-react', () => ({
  AlertTriangle: () => null,
  ArrowRight: () => null,
  CheckCircle2: () => null,
  Clock3: () => null,
  Copy: () => null,
  PackageOpen: () => null,
  Terminal: () => null,
  Shield: () => null,
  Users: () => null,
  GitBranch: () => null,
  Lock: () => null,
  Monitor: () => null,
  Search: () => null,
  Server: () => null,
  Settings: () => null,
}))

vi.mock('@/shared/components/landing-quick-start', () => ({
  LandingQuickStartSection: () => null,
}))

vi.mock('@/features/skill/skill-card', () => ({
  SkillCard: () => null,
}))

// Renders a marker so the loading branch is observable instead of collapsing to nothing.
vi.mock('@/shared/components/skeleton-loader', () => ({
  SkeletonList: () => 'skeleton-loading',
}))

vi.mock('@/shared/hooks/use-home-discovery', () => ({
  useHomeRecommendations: () => ({
    data: recommendationFixture.data,
    isLoading: recommendationFixture.isLoading,
    isError: recommendationFixture.isError,
    refetch: vi.fn(),
  }),
  // The windowed ranking returns one row whose windowed count is unknown, so the page has to
  // decide what to show for it: nothing, rather than a borrowed cumulative total.
  useHomeRanking: (type: string) => ({
    data: {
      type,
      items:
        type === 'recent-popular'
          ? [
              {
                skill: {
                  id: 1,
                  slug: 'slug-1',
                  displayName: 'Alpha',
                  namespace: 'team',
                  downloadCount: 4200,
                  starCount: 0,
                  ratingCount: 0,
                  updatedAt: '2026-11-01T00:00:00Z',
                  canSubmitPromotion: false,
                },
                windowDownloadCount: null,
                firstPublishedAt: null,
              },
            ]
          : [],
    },
    isLoading: false,
    isError: false,
  }),
}))

vi.mock('@/shared/lib/format-relative-time', () => ({
  formatRelativeTime: () => 'recently',
}))

vi.mock('@/shared/hooks/use-in-view', () => ({
  useInView: () => ({ ref: vi.fn(), inView: true }),
}))

vi.mock('@/shared/lib/search-query', () => ({
  normalizeSearchQuery: (q: string) => q.trim(),
}))

vi.mock('@/shared/ui/button', () => ({
  Button: ({ children }: { children: unknown }) => children,
}))

import { renderToStaticMarkup } from 'react-dom/server'
import { LandingPage } from './landing'

function resetRecommendationFixture() {
  recommendationFixture.isLoading = false
  recommendationFixture.isError = false
  recommendationFixture.data = []
}

/**
 * The hero is the market-brand surface. The self-hosting section further down the page keeps the
 * upstream "SkillHub Registry" label on purpose, so brand assertions are scoped to the hero rather
 * than the whole document.
 */
function heroMarkup(html: string): string {
  const heroEnd = html.indexOf('</main>')
  return heroEnd === -1 ? html : html.slice(0, heroEnd)
}

describe('LandingPage', () => {
  it('exports a named component function', () => {
    expect(typeof LandingPage).toBe('function')
  })

  it('renders the SkillMall brand name in the hero section', () => {
    resetRecommendationFixture()
    const html = renderToStaticMarkup(<LandingPage />)

    expect(heroMarkup(html)).toContain('SkillMall')
    // The market must not still introduce itself as SkillHub (issue #10).
    expect(heroMarkup(html)).not.toContain('SkillHub')
    expect(html).toContain('landing.hero.mallSubtitle')
    expect(html).toContain('landing.hero.searchPlaceholder')
  })

  it('keeps the upstream registry label in the self-hosting diagram', () => {
    resetRecommendationFixture()
    const html = renderToStaticMarkup(<LandingPage />)

    // Deploying the SkillHub open-source project on your infrastructure is still SkillHub:
    // the market brand must not be pasted over the project the market is built on.
    expect(html).toContain('SkillHub<br/>Registry')
    expect(html).toContain('https://github.com/iflytek/skillhub')
  })

  it('shows the three rankings instead of the old popular and latest grids', () => {
    resetRecommendationFixture()
    const html = renderToStaticMarkup(<LandingPage />)

    // The page used to spend this space on two grids titled "热门下载" / "最新发布".
    expect(html).not.toContain('home.popularTitle')
    expect(html).not.toContain('home.latestTitle')
    expect(html).not.toContain('discovery.popular.open')
    expect(html).not.toContain('discovery.latest.open')

    expect(html).toContain('home.ranking.recent.title')
    expect(html).toContain('home.ranking.cumulative.title')
    expect(html).toContain('home.ranking.newest.title')
    expect(html).toContain('discovery.ranking.recent')
    expect(html).toContain('discovery.ranking.cumulative')
    expect(html).toContain('discovery.ranking.newest')
  })

  it('renders no window figure for a row whose windowed count is unknown', () => {
    resetRecommendationFixture()
    const html = renderToStaticMarkup(<LandingPage />)

    // The row has 4200 cumulative downloads; the 7-day number is unknown, so nothing is shown.
    expect(html).toContain('Alpha')
    expect(html).not.toContain('home.ranking.windowDownloads')
  })

  it('keeps the SkillMall brand while recommendations are loading', () => {
    resetRecommendationFixture()
    recommendationFixture.isLoading = true
    const html = renderToStaticMarkup(<LandingPage />)
    resetRecommendationFixture()

    expect(heroMarkup(html)).toContain('SkillMall')
    expect(heroMarkup(html)).not.toContain('SkillHub')
    expect(html).toContain('skeleton-loading')
  })

  it('keeps the SkillMall brand in the empty recommendation state', () => {
    resetRecommendationFixture()
    const html = renderToStaticMarkup(<LandingPage />)

    expect(heroMarkup(html)).toContain('SkillMall')
    expect(heroMarkup(html)).not.toContain('SkillHub')
    expect(html).toContain('home.recommend.empty')
  })

  it('keeps the SkillMall brand in the recommendation failure state', () => {
    resetRecommendationFixture()
    recommendationFixture.isError = true
    const html = renderToStaticMarkup(<LandingPage />)
    resetRecommendationFixture()

    // A failed request is a failure, not an empty result: LoadError owns this branch.
    expect(html).toContain('common.loadFailedTitle')
    expect(heroMarkup(html)).toContain('SkillMall')
    expect(heroMarkup(html)).not.toContain('SkillHub')
  })
})
