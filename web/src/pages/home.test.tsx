import { describe, expect, it, vi } from 'vitest'

// HomePage is a component-only page. We verify it exports correctly
// and renders key sections.

const listFixture = vi.hoisted(() => ({
  isLoading: false,
  items: [] as unknown[],
}))

vi.mock('@tanstack/react-router', () => ({
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

vi.mock('@/features/search/search-bar', () => ({
  SearchBar: () => null,
}))

vi.mock('@/features/skill/skill-card', () => ({
  SkillCard: () => null,
}))

// Renders a marker so the loading branch is observable instead of collapsing to nothing.
vi.mock('@/shared/components/skeleton-loader', () => ({
  SkeletonList: () => 'skeleton-loading',
}))

vi.mock('@/shared/components/quick-start', () => ({
  QuickStartSection: () => null,
}))

vi.mock('@/shared/hooks/use-skill-queries', () => ({
  useSearchSkills: () => ({
    data: { items: listFixture.items },
    isLoading: listFixture.isLoading,
  }),
}))

vi.mock('@/shared/lib/search-query', () => ({
  normalizeSearchQuery: (q: string) => q.trim(),
}))

vi.mock('@/shared/ui/button', () => ({
  Button: ({ children }: { children: unknown }) => children,
}))

import { renderToStaticMarkup } from 'react-dom/server'
import { HomePage } from './home'

describe('HomePage', () => {
  it('exports a named component function', () => {
    expect(typeof HomePage).toBe('function')
  })

  it('renders the hero section with the SkillMall brand name', () => {
    const html = renderToStaticMarkup(<HomePage />)

    expect(html).toContain('SkillMall')
    // The market must not still introduce itself as SkillHub (issue #10).
    expect(html).not.toContain('SkillHub')
    expect(html).toContain('home.subtitle')
  })

  it('keeps the SkillMall brand while skill lists are loading', () => {
    listFixture.isLoading = true
    const html = renderToStaticMarkup(<HomePage />)
    listFixture.isLoading = false

    expect(html).toContain('SkillMall')
    expect(html).not.toContain('SkillHub')
    expect(html).toContain('skeleton-loading')
  })

  it('keeps the SkillMall brand in the empty list state', () => {
    const html = renderToStaticMarkup(<HomePage />)

    expect(html).toContain('SkillMall')
    expect(html).not.toContain('SkillHub')
    expect(html).toContain('home.recommend.empty')
  })
})
