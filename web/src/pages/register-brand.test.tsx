import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it, vi } from 'vitest'

/**
 * The registration page renders inside the shared AuthShell, which carries the market wordmark.
 * This suite keeps the real AuthShell (unlike register.test.tsx, which stubs it) so the page's
 * visible brand is asserted end to end (issue #10).
 */

vi.mock('@tanstack/react-router', () => ({
  Link: ({ children }: { children: unknown }) => children,
  useNavigate: () => vi.fn(),
  useSearch: () => ({ returnTo: '' }),
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

vi.mock('@/features/auth/login-button', () => ({
  AuthMethodButtonList: () => null,
}))

vi.mock('@/features/auth/use-auth-methods', () => ({
  useAuthMethods: () => ({ data: [], isLoading: false }),
}))

vi.mock('@/features/auth/use-local-auth-capabilities', () => ({
  useLocalAuthCapabilities: () => ({
    data: { passwordLoginEnabled: true, selfRegistrationEnabled: true, registrationAvailable: true },
    isError: false,
  }),
}))

vi.mock('@/features/auth/use-local-auth', () => ({
  useLocalRegister: () => ({
    mutateAsync: vi.fn(),
    isPending: false,
    error: null,
  }),
}))

vi.mock('@/shared/ui/button', () => ({
  Button: ({ children }: { children: unknown }) => children,
}))

vi.mock('@/shared/ui/card', () => ({
  Card: ({ children }: { children: unknown }) => children,
  CardContent: ({ children }: { children: unknown }) => children,
  CardDescription: ({ children }: { children: unknown }) => children,
  CardHeader: ({ children }: { children: unknown }) => children,
  CardTitle: ({ children }: { children: unknown }) => children,
}))

vi.mock('@/shared/ui/input', () => ({
  Input: () => null,
}))

vi.mock('@/shared/ui/tabs', () => ({
  Tabs: ({ children }: { children: unknown }) => children,
  TabsContent: ({ children }: { children: unknown }) => children,
  TabsList: ({ children }: { children: unknown }) => children,
  TabsTrigger: ({ children }: { children: unknown }) => children,
}))

import { RegisterPage } from './register'

describe('RegisterPage brand', () => {
  it('presents the market as SkillMall on the registration page', () => {
    const html = renderToStaticMarkup(<RegisterPage />)

    expect(html).toContain('SkillMall')
    expect(html).not.toContain('SkillHub')
    expect(html).toContain('register.title')
  })
})
