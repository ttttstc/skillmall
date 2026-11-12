import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'

// Everything the section reads is mocked, so the fixtures have to be hoisted as well: the mock
// factories below run before the module body is evaluated.
const fixture = vi.hoisted(() => {
  function skill(id: number, displayName: string) {
    return {
      id,
      slug: `slug-${id}`,
      displayName,
      namespace: 'team',
      downloadCount: 0,
      starCount: 0,
      ratingCount: 0,
      updatedAt: '2026-11-01T00:00:00Z',
      canSubmitPromotion: false,
    }
  }

  return {
    recommended: skill(1, 'Recommended Alpha'),
    ranked: skill(2, 'Ranked Beta'),
    state: {
      loading: false,
      error: false,
      empty: false,
    },
  }
})

vi.mock('@tanstack/react-router', () => ({
  Link: ({ children }: { children: ReactNode }) => children,
  useNavigate: () => vi.fn(),
}))

vi.mock('react-i18next', async () => {
  const actual = await vi.importActual<typeof import('react-i18next')>('react-i18next')
  return {
    ...actual,
    useTranslation: () => ({ t: (key: string) => key, i18n: { language: 'en' } }),
  }
})

vi.mock('lucide-react', () => ({
  ArrowRight: () => null,
  ArrowUpRight: () => null,
  Award: () => null,
  Bookmark: () => null,
  Calendar: () => null,
  CalendarDays: () => null,
  ChevronLeft: () => null,
  ChevronRight: () => null,
  Clock3: () => null,
  Crown: () => null,
  Download: () => null,
  ExternalLink: () => null,
  Eye: () => null,
  Flame: () => null,
  LayoutGrid: () => null,
  ListFilter: () => null,
  MoveRight: () => null,
  RefreshCw: () => null,
  RotateCcw: () => null,
  Shuffle: () => null,
  Sparkles: () => null,
  Star: () => null,
  TrendingUp: () => null,
  Trophy: () => null,
  Users: () => null,
  Zap: () => null,
}))

vi.mock('@/shared/hooks/use-home-discovery', () => ({
  useHomeRecommendations: () => ({
    data: fixture.state.empty ? [] : [fixture.recommended],
    isLoading: fixture.state.loading,
    isError: fixture.state.error,
    refetch: () => undefined,
  }),
  useHomeRanking: () => ({
    data: {
      items: [
        {
          skill: fixture.ranked,
          windowDownloadCount: 12,
          firstPublishedAt: '2026-11-01T00:00:00Z',
        },
      ],
    },
    isLoading: false,
    isError: false,
  }),
}))

vi.mock('@/features/skill/skill-card', () => ({
  SkillCard: ({ skill }: { skill: { displayName: string } }) => <div>{skill.displayName}</div>,
}))

vi.mock('@/shared/components/skeleton-loader', () => ({
  SkeletonList: () => <div>skeleton-list</div>,
}))

vi.mock('@/shared/components/load-error', () => ({
  LoadError: () => <div>load-error</div>,
}))

import { renderToStaticMarkup } from 'react-dom/server'
import { PopularSkillsSection } from '@/features/home/popular-skills'

const recommendTab = 'home.popular.tabs.recommend'
const recentTab = 'home.ranking.recent.title'
const cumulativeTab = 'home.ranking.cumulative.title'
const newestTab = 'home.ranking.newest.title'

beforeEach(() => {
  fixture.state.loading = false
  fixture.state.error = false
  fixture.state.empty = false
})

describe('PopularSkillsSection', () => {
  it('exports a named component function', () => {
    expect(typeof PopularSkillsSection).toBe('function')
  })

  it('renders the laurel heading, the four tabs and the recommended skills by default', () => {
    const html = renderToStaticMarkup(<PopularSkillsSection />)

    expect(html).toContain('home.popular.title')
    expect(html).toContain('home.popular.viewAll')

    const tabLabels = [recommendTab, recentTab, cumulativeTab, newestTab]
    for (const label of tabLabels) {
      expect(html).toContain(label)
    }
    // Four tabs, each carrying its own analytics marker, declared in the order above.
    expect(html).toContain('role="tablist"')
    expect(occurrences(html, 'data-analytics-id="discovery.popular.tab"')).toBe(4)
    const positions = tabLabels.map(label => html.indexOf(label))
    expect([...positions].sort((left, right) => left - right)).toEqual(positions)

    // The recommended tab owns the grid, so its four-column layout and data are on screen.
    expect(html).toContain('xl:grid-cols-4')
    expect(html).toContain('Recommended Alpha')
  })

  it('renders the description and shuffle control of the recommended tab only', () => {
    const html = renderToStaticMarkup(<PopularSkillsSection />)

    expect(html).toContain('home.recommend.description')
    expect(html).not.toContain('home.ranking.recent.description')
    expect(html).not.toContain('home.ranking.cumulative.description')
    expect(html).not.toContain('home.ranking.newest.description')

    // "换一换" only redraws recommendations; the rankings are fixed by their window.
    expect(html).toContain('data-analytics-id="discovery.popular.shuffle"')
    expect(html).toContain('home.recommend.shuffle')
  })

  it('renders the skeleton instead of cards while the recommendations load', () => {
    fixture.state.loading = true

    const html = renderToStaticMarkup(<PopularSkillsSection />)

    expect(html).toContain('skeleton-list')
    expect(html).not.toContain('Recommended Alpha')
  })

  it('renders the load error instead of cards when the recommendations fail', () => {
    fixture.state.error = true

    const html = renderToStaticMarkup(<PopularSkillsSection />)

    expect(html).toContain('load-error')
    expect(html).not.toContain('Recommended Alpha')
  })

  it('renders the empty hint when no skill is recommended', () => {
    fixture.state.empty = true

    const html = renderToStaticMarkup(<PopularSkillsSection />)

    expect(html).toContain('home.recommend.empty')
    expect(html).not.toContain('Recommended Alpha')
  })
})

function occurrences(haystack: string, needle: string): number {
  return haystack.split(needle).length - 1
}
