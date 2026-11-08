// @vitest-environment jsdom

import { createElement } from 'react'
import { fireEvent, render, screen, cleanup } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const navigate = vi.fn()
const { useMyStarsPage, toggleStar } = vi.hoisted(() => ({ useMyStarsPage: vi.fn(), toggleStar: vi.fn() }))

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => navigate,
  useSearch: () => ({ page: 2 }),
  useLocation: () => ({
    pathname: '/dashboard/stars',
    searchStr: '?page=2',
    hash: '#saved',
  }),
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

vi.mock('@/features/skill/skill-list-row', () => ({
  SkillListRow: ({ onClick, actions }: { onClick?: () => void; actions?: unknown }) =>
    createElement('div', null, createElement('button', { onClick }, 'skill-row'), actions as never),
}))

vi.mock('@/features/social/use-star', () => ({
  useToggleStar: () => ({ mutate: toggleStar, isPending: false }),
}))

vi.mock('@/shared/components/pagination', () => ({
  Pagination: () => null,
}))

vi.mock('@/shared/hooks/use-user-queries', () => ({
  useMyStarsPage,
}))

vi.mock('@/shared/ui/card', () => ({
  Card: ({ children }: { children: unknown }) => children,
}))

vi.mock('@/shared/components/dashboard-page-header', () => ({
  DashboardPageHeader: () => null,
}))

import { MyStarsPage } from './stars'

function starsPage(overrides: Record<string, unknown> = {}) {
  return {
    data: {
      items: [{ id: 1, namespace: 'team-a', slug: 'demo skill', headlineVersion: { version: '1.2.3', status: 'PUBLISHED' } }],
      total: 1,
      page: 0,
      size: 12,
    },
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
    ...overrides,
  }
}

describe('MyStarsPage', () => {
  beforeEach(() => {
    navigate.mockClear()
    toggleStar.mockClear()
    useMyStarsPage.mockReturnValue(starsPage())
  })
  afterEach(() => cleanup())

  it('exports a named component function', () => {
    expect(typeof MyStarsPage).toBe('function')
  })

  it('preserves the favorites page when opening a skill', () => {
    render(createElement(MyStarsPage))

    fireEvent.click(screen.getByRole('button', { name: 'skill-row' }))

    expect(navigate).toHaveBeenCalledWith({
      to: '/space/team-a/demo%20skill',
      search: { returnTo: '/dashboard/stars?page=2#saved' },
    })
  })

  it('uses the URL page as the query source', () => {
    render(createElement(MyStarsPage))

    expect(useMyStarsPage).toHaveBeenCalledWith({ page: 2, size: 12 })
  })

  it('offers per-row unstar and download actions', () => {
    render(createElement(MyStarsPage))

    expect(screen.getByRole('button', { name: 'stars.actions.unstar' })).toBeTruthy()
    const download = screen.getByRole('link', { name: 'stars.actions.download' })
    expect(download.getAttribute('href')).toContain('/api/web/skills/team-a/demo%20skill/versions/1.2.3/download')
  })

  it('removes the star in place instead of opening the skill', () => {
    render(createElement(MyStarsPage))

    fireEvent.click(screen.getByRole('button', { name: 'stars.actions.unstar' }))

    expect(toggleStar).toHaveBeenCalledWith(true)
    expect(navigate).not.toHaveBeenCalled()
  })

  it('hides the download action when the skill exposes no headline version', () => {
    useMyStarsPage.mockReturnValue(starsPage({
      data: { items: [{ id: 1, namespace: 'team-a', slug: 'demo skill' }], total: 1, page: 0, size: 12 },
    }))

    render(createElement(MyStarsPage))

    expect(screen.queryByRole('link', { name: 'stars.actions.download' })).toBeNull()
  })

  it('reports a failed load instead of presenting it as an empty collection', () => {
    const refetch = vi.fn()
    useMyStarsPage.mockReturnValue(starsPage({ data: undefined, isError: true, refetch }))

    render(createElement(MyStarsPage))

    expect(screen.getByRole('alert')).toBeTruthy()
    expect(screen.queryByText('stars.empty')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'common.retry' }))
    expect(refetch).toHaveBeenCalledTimes(1)
  })

  it('keeps the empty state when the collection is genuinely empty', () => {
    useMyStarsPage.mockReturnValue(starsPage({
      data: { items: [], total: 0, page: 0, size: 12 },
    }))

    render(createElement(MyStarsPage))

    expect(screen.getByText('stars.empty')).toBeTruthy()
    expect(screen.queryByRole('alert')).toBeNull()
  })
})
