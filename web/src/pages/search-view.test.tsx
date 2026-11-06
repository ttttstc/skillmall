import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it, vi } from 'vitest'

const useSearchMock = vi.fn()

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => vi.fn(),
  useSearch: () => useSearchMock(),
}))

vi.mock('react-i18next', async () => {
  const actual = await vi.importActual<typeof import('react-i18next')>('react-i18next')
  return {
    ...actual,
    useTranslation: () => ({
      t: (key: string, options?: Record<string, unknown>) => (
        options && typeof options.count === 'number' ? `${key}:${options.count}` : key
      ),
    }),
  }
})

vi.mock('@/features/auth/use-auth', () => ({ useAuth: () => ({ isAuthenticated: false }) }))

vi.mock('@/features/search/search-bar', () => ({ SearchBar: () => <div>search-bar</div> }))
vi.mock('@/features/skill/skill-card', () => ({ SkillCard: () => <div>skill-card</div> }))
vi.mock('@/features/skill/skill-list-row', () => ({ SkillListRow: () => <div>skill-list-row</div> }))
vi.mock('@/shared/components/skeleton-loader', () => ({ SkeletonList: () => <div>skeleton</div> }))
vi.mock('@/shared/components/empty-state', () => ({
  EmptyState: ({ title }: { title: string }) => <div>empty-state<span>{title}</span></div>,
}))
vi.mock('@/shared/components/pagination', () => ({ Pagination: () => <div>pagination</div> }))
vi.mock('@/app/page-shell-style', () => ({ APP_SHELL_PAGE_CLASS_NAME: 'page-shell' }))
vi.mock('@/shared/hooks/use-label-queries', () => ({ useVisibleLabels: () => ({ data: [] }) }))
vi.mock('@/shared/hooks/use-user-queries', () => ({
  useMyStars: () => ({ data: [], isLoading: false, isFetching: false }),
}))
vi.mock('@/shared/hooks/use-skill-queries', () => ({
  useSearchSkills: () => ({
    data: {
      items: [
        { id: 1, slug: 'alpha', namespace: 'team-a', displayName: 'Alpha', downloadCount: 3, starCount: 1 },
      ],
      total: 1,
      size: 12,
    },
    isLoading: false,
    isFetching: false,
  }),
}))

import { SearchPage } from './search'

describe('SearchPage discovery view', () => {
  it('renders the browsing grid by default', () => {
    useSearchMock.mockReturnValue({ q: '', sort: 'newest', page: 0, starredOnly: false })

    const html = renderToStaticMarkup(<SearchPage />)

    expect(html).toContain('skill-grid-view')
    expect(html).not.toContain('skill-list-view')
    expect(html).toContain('search.allSkillsTitle')
  })

  it('renders list rows when the view parameter is list', () => {
    useSearchMock.mockReturnValue({ q: '', sort: 'newest', page: 0, starredOnly: false, view: 'list' })

    const html = renderToStaticMarkup(<SearchPage />)

    expect(html).toContain('skill-list-view')
    expect(html).not.toContain('skill-grid-view')
    expect(html).toContain('skill-list-row')
  })

  it('ignores an unsupported view value', () => {
    useSearchMock.mockReturnValue({ q: '', sort: 'newest', page: 0, starredOnly: false, view: 'table' })

    const html = renderToStaticMarkup(<SearchPage />)

    expect(html).toContain('skill-grid-view')
  })
})
