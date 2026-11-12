import { describe, expect, it, vi } from 'vitest'

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
  ArrowRight: () => null,
  Bookmark: () => null,
  CheckCircle2: () => null,
  ClipboardList: () => null,
  Clock3: () => null,
  Code2: () => null,
  Copy: () => null,
  Download: () => null,
  FlaskConical: () => null,
  PackageOpen: () => null,
  Palette: () => null,
  Shapes: () => null,
  Terminal: () => null,
  Shield: () => null,
  Users: () => null,
  GitBranch: () => null,
  Lock: () => null,
  Monitor: () => null,
  Search: () => null,
  Server: () => null,
  Settings: () => null,
  Wrench: () => null,
}))

vi.mock('@/shared/components/landing-quick-start', () => ({
  LandingQuickStartSection: () => null,
}))

// The home discovery section owns its own data and interactions now, so the page only has to
// place it.
vi.mock('@/features/home/popular-skills', () => ({
  PopularSkillsSection: () => <div>popular-skills-section</div>,
}))

vi.mock('@/features/skill/skill-card', () => ({
  SkillCard: () => null,
}))

vi.mock('@/shared/components/skeleton-loader', () => ({
  SkeletonList: () => null,
}))

vi.mock('@/shared/hooks/use-home-discovery', () => ({
  useHomeRecommendations: () => ({
    data: [],
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
  }),
  useHomeRanking: () => ({
    data: {
      items: [],
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

describe('LandingPage', () => {
  it('exports a named component function', () => {
    expect(typeof LandingPage).toBe('function')
  })

  it('renders the brand name in the hero section', () => {
    const html = renderToStaticMarkup(<LandingPage />)

    expect(html).toContain('SkillMall')
    expect(html).toContain('landing.hero.mallSubtitle')
    expect(html).toContain('landing.hero.searchPlaceholder')
  })

  it('delegates the popular skills block to its own section instead of hosting the grids and rankings', () => {
    const html = renderToStaticMarkup(<LandingPage />)

    // "推荐 Skills" and the three side-by-side rankings moved into PopularSkillsSection.
    expect(html).toContain('popular-skills-section')

    // The page used to spend this space on two grids titled "热门下载" / "最新发布".
    expect(html).not.toContain('home.popularTitle')
    expect(html).not.toContain('home.latestTitle')
    expect(html).not.toContain('discovery.popular.open')
    expect(html).not.toContain('discovery.latest.open')

    expect(html).not.toContain('home.ranking.recent.title')
    expect(html).not.toContain('home.ranking.cumulative.title')
    expect(html).not.toContain('home.ranking.newest.title')
    expect(html).not.toContain('discovery.ranking.recent')
    expect(html).not.toContain('discovery.ranking.cumulative')
    expect(html).not.toContain('discovery.ranking.newest')
    // The windowed download figure lived on those rows, so it left with them.
    expect(html).not.toContain('home.ranking.windowDownloads')
  })

  it('renders the UCD domain tabs with sample rows instead of the unavailable placeholder', () => {
    const html = renderToStaticMarkup(<LandingPage />)

    for (const domain of ['design', 'development', 'testing', 'engineering', 'projectManagement', 'other']) {
      expect(html).toContain(`home.domain.tabs.${domain}`)
    }
    expect(html).toContain('home.domain.subtitle')
    expect(html).toContain('home.domain.viewAll')
    expect(html).toContain('discovery.domain.tab')
    expect(html).not.toContain('home.domainUnavailable')
    // The default tab is 设计, so its first sample row is on screen.
    expect(html).toContain('delta-design-usecase-generator')
  })

  it('no longer renders the enterprise section or the chapters below it', () => {
    const html = renderToStaticMarkup(<LandingPage />)

    expect(html).not.toContain('landing.experience.enterprise')
    expect(html).not.toContain('landing.experience.cta')
    expect(html).not.toContain('navigation.repository.open')
    expect(html).not.toContain('navigation.documentation.open')
    // The capability grid and quick start sit above the removed sections and stay.
    expect(html).toContain('landing.experience.capabilities.title')
  })
})
