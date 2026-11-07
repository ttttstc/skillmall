import { renderToStaticMarkup } from 'react-dom/server'
import { createElement, type ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { RankingList, type RankingRowView } from './ranking-list'
import type { SkillSummary } from '@/api/types'

vi.mock('@/api/client', () => ({ buildApiUrl: (url: string) => url }))

vi.mock('@/shared/ui/card', () => ({
  Card: ({ children, className }: { children?: ReactNode; className?: string }) => (
    createElement('div', { className }, children)
  ),
}))

function skill(id: number, displayName: string, namespace = 'team'): SkillSummary {
  return {
    id,
    slug: `slug-${id}`,
    displayName,
    namespace,
    downloadCount: 0,
    starCount: 0,
    ratingCount: 0,
    updatedAt: '2026-11-01T00:00:00Z',
    canSubmitPromotion: false,
  } as SkillSummary
}

function render(overrides: Partial<Parameters<typeof RankingList>[0]> = {}) {
  return renderToStaticMarkup(
    createElement(RankingList, {
      title: 'Recent',
      description: 'Most downloads in the last 7 days',
      analyticsId: 'discovery.ranking.recent',
      rows: [],
      isLoading: false,
      isError: false,
      errorLabel: 'load failed',
      emptyLabel: 'no downloads in the last 7 days',
      onSelect: () => {},
      ...overrides,
    }),
  )
}

const rows: RankingRowView[] = [
  { skill: skill(1, 'Alpha'), meta: '12 in 7 days' },
  { skill: skill(2, 'Beta'), meta: '5 in 7 days' },
]

describe('RankingList', () => {
  it('renders rows in the order the ranking supplied, numbered from one', () => {
    const html = render({ rows })

    expect(html).toContain('Alpha')
    expect(html).toContain('Beta')
    expect(html.indexOf('Alpha')).toBeLessThan(html.indexOf('Beta'))
    expect(html).toContain('>1<')
    expect(html).toContain('>2<')
    expect(html).toContain('12 in 7 days')
  })

  it('shows the empty label and no rows when the ranking is empty', () => {
    const html = render({ rows: [] })

    expect(html).toContain('no downloads in the last 7 days')
    expect(html).not.toContain('Alpha')
  })

  it('shows the error label instead of rows when the request failed', () => {
    const html = render({ rows, isError: true })

    expect(html).toContain('load failed')
    expect(html).not.toContain('Alpha')
  })

  it('does not render rows while loading', () => {
    const html = render({ rows, isLoading: true })

    expect(html).not.toContain('Alpha')
    expect(html).not.toContain('no downloads in the last 7 days')
  })

  it('renders no meta label for a row whose windowed count is unknown', () => {
    // The 7-day number can legitimately be unknown (no bucket for the window). Such a row must
    // render nothing on the right rather than an empty badge or a borrowed cumulative total.
    const html = render({
      rows: [
        { skill: skill(1, 'Alpha'), meta: null },
        { skill: skill(2, 'Beta'), meta: '12 in 7 days' },
      ],
    })

    expect(html).toContain('Alpha')
    expect(html).toContain('Beta')
    expect(html).toContain('12 in 7 days')
    expect(occurrences(html, 'shrink-0 text-xs tabular-nums')).toBe(1)
    expect(html).not.toContain('undefined')
  })
})

function occurrences(haystack: string, needle: string): number {
  return haystack.split(needle).length - 1
}
