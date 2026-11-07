import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it, vi } from 'vitest'

// Layout is the app shell: it owns the header brand, the market navigation and the footer.
// These tests render it so the two brand surfaces users see on every page stay asserted.

vi.mock('@tanstack/react-router', () => ({
  Outlet: () => null,
  Link: ({ children }: { children: unknown }) => children,
  useRouterState: () => ({ pathname: '/', resolvedPathname: '/' }),
}))

vi.mock('react-i18next', async () => {
  const actual = await vi.importActual<typeof import('react-i18next')>('react-i18next')
  return {
    ...actual,
    useTranslation: () => ({
      t: (key: string) => key,
      i18n: { language: 'en' },
    }),
  }
})

vi.mock('@/features/auth/use-auth', () => ({
  useAuth: () => ({
    user: null,
    isLoading: false,
  }),
}))

vi.mock('@/shared/components/language-switcher', () => ({
  LanguageSwitcher: () => null,
}))

vi.mock('@/shared/components/theme-toggle', () => ({
  ThemeToggle: () => null,
}))

vi.mock('@/shared/components/user-menu', () => ({
  UserMenu: () => null,
}))

vi.mock('@/features/notification/notification-bell', () => ({
  NotificationBell: () => null,
}))

vi.mock('@/pages/dashboard', () => ({
  DashboardSidebar: () => null,
  SIDEBAR_GROUPS: [],
}))

vi.mock('@/shared/lib/governance-access', () => ({
  canViewGovernanceCenter: () => false,
}))

vi.mock('@/shared/lib/dismiss-open-overlays', () => ({
  dismissOpenOverlays: vi.fn(),
}))

vi.mock('@/shared/lib/document-language', () => ({
  syncDocumentLanguage: vi.fn(),
}))

vi.mock('./layout-header-style', () => ({
  getAppHeaderClassName: () => 'header-class',
}))

vi.mock('./layout-main-content', () => ({
  AUTH_ENTRY_PATHS: [],
  SPLIT_AUTH_PATHS: [],
  resolveAppMainContentPathname: (p: string) => p,
  getAppMainContentLayout: () => ({
    mainClassName: 'main-class',
    contentClassName: 'content-class',
  }),
}))

import { Layout } from './layout'

describe('Layout', () => {
  it('exports a named Layout component function', () => {
    expect(typeof Layout).toBe('function')
    expect(Layout.name).toBe('Layout')
  })

  it('brands the navigation and the footer as SkillMall', () => {
    const html = renderToStaticMarkup(createElement(Layout))

    // Header wordmark and footer wordmark both name the market.
    const brandOccurrences = html.split('SkillMall').length - 1
    expect(brandOccurrences).toBeGreaterThanOrEqual(2)
    expect(html).not.toContain('SkillHub')
  })

  it('keeps the footer links pointing at the SkillHub open-source project', () => {
    const html = renderToStaticMarkup(createElement(Layout))

    // Branding the market does not rename the upstream project it is built on (issue #10).
    expect(html).toContain('https://github.com/iflytek/skillhub')
    expect(html).toContain('https://www.npmjs.com/package/@astron-team/skillhub')
    expect(html).toContain('https://github.com/iflytek/skillhub/blob/main/LICENSE')
  })
})
