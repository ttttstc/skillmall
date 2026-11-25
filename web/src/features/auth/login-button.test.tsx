/** @vitest-environment jsdom */
import { existsSync } from 'node:fs'
import { resolve } from 'node:path'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AuthMethod } from '@/api/types'
import { AuthMethodButtonList } from './login-button'

vi.mock('react-i18next', async () => {
  const actual = await vi.importActual<typeof import('react-i18next')>('react-i18next')
  return {
    ...actual,
    useTranslation: () => ({
      t: (key: string, values?: Record<string, string>) => {
        if (key === 'loginButton.loginWith' && values?.name) {
          return `Login with ${values.name}`
        }
        if (key === 'loginButton.providerGitcode') {
          return 'GitCode'
        }
        return key
      },
    }),
  }
})

vi.mock('@/shared/lib/base-path', () => ({
  BASE_PATH: '/',
  withBasePath: (path: string) => path,
}))

vi.mock('./use-auth-methods', () => ({
  useAuthMethods: () => ({ data: [], isLoading: false }),
}))

vi.mock('@/shared/ui/button', () => ({
  Button: ({ children, ...props }: React.ButtonHTMLAttributes<HTMLButtonElement>) => (
    <button type="button" {...props}>{children}</button>
  ),
}))

const methods: AuthMethod[] = [
  {
    id: 'github',
    methodType: 'OAUTH_REDIRECT',
    provider: 'github',
    displayName: 'GitHub',
    actionUrl: '/oauth2/authorization/github',
  },
  {
    id: 'feishu',
    methodType: 'OAUTH_REDIRECT',
    provider: 'feishu',
    displayName: 'Feishu',
    actionUrl: '/oauth2/authorization/feishu',
  },
  {
    id: 'password',
    methodType: 'PASSWORD',
    provider: 'local',
    displayName: 'Password',
    actionUrl: '/login',
  },
]

describe('AuthMethodButtonList', () => {
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it('renders OAuth redirect methods from backend configuration only', () => {
    render(<AuthMethodButtonList methods={methods} />)

    expect(screen.getByText('Login with GitHub')).toBeTruthy()
    expect(screen.getByText('Login with Feishu')).toBeTruthy()
    expect(screen.queryByText('Login with Password')).toBeNull()
  })

  it.each([false, true])('uses the Keycloak icon while preserving existing provider icons (compact=%s)', (compact) => {
    const keycloakMethod: AuthMethod = {
      id: 'keycloak',
      methodType: 'OAUTH_REDIRECT',
      provider: 'Keycloak',
      displayName: 'Keycloak',
      actionUrl: '/oauth2/authorization/keycloak',
    }

    render(<AuthMethodButtonList methods={[keycloakMethod, methods[0]]} compact={compact} />)

    expect(screen.getByRole('img', { name: 'Keycloak' }).getAttribute('src')).toBe('/keycloak-logo.svg')
    expect(screen.getByRole('img', { name: 'github' }).getAttribute('src')).toBe('/github-logo.svg')
  })

  it.each([
    ['Auth0', 'auth0'],
    ['OKTA', 'okta'],
  ])('uses the %s provider icon', (provider, icon) => {
    render(<AuthMethodButtonList methods={[{
      id: provider,
      methodType: 'OAUTH_REDIRECT',
      provider,
      displayName: provider,
      actionUrl: `/oauth2/authorization/${provider}`,
    }]} />)

    expect(screen.getByRole('img', { name: provider }).getAttribute('src')).toBe(`/${icon}-logo.svg`)
  })

  it.each(['Enterprise SSO', 'constructor', '__proto__'])('uses the packaged OIDC icon for unrecognized provider %s', (provider) => {
    render(<AuthMethodButtonList methods={[{
      id: 'enterprise-sso',
      methodType: 'OAUTH_REDIRECT',
      provider,
      displayName: provider,
      actionUrl: '/oauth2/authorization/enterprise-sso',
    }]} />)

    expect(screen.getByRole('img', { name: provider }).getAttribute('src')).toBe('/oidc-logo.svg')
  })

  it.each(['github', 'gitlab', 'feishu', 'dingtalk', 'keycloak', 'auth0', 'okta', 'unknown'])('bundles the icon selected for %s', (provider) => {
    render(<AuthMethodButtonList methods={[{
      id: provider,
      methodType: 'OAUTH_REDIRECT',
      provider,
      displayName: provider,
      actionUrl: `/oauth2/authorization/${provider}`,
    }]} />)

    const iconPath = screen.getByRole('img', { name: provider }).getAttribute('src') ?? ''
    expect(iconPath).toMatch(/^\/[a-z0-9-]+-logo\.svg$/)
    expect(existsSync(resolve(process.cwd(), 'public', iconPath.slice(1)))).toBe(true)
  })

  it('renders the GitCode login method advertised by the backend', () => {
    const gitcodeMethod: AuthMethod = {
      id: 'oauth-gitcode',
      methodType: 'OAUTH_REDIRECT',
      provider: 'gitcode',
      displayName: 'GitCode',
      actionUrl: '/oauth2/authorization/gitcode',
    }

    render(<AuthMethodButtonList methods={[gitcodeMethod]} />)

    expect(screen.getByText('Login with GitCode')).toBeTruthy()
  })

  it('prefers the server-provided display name over the bundled fallback label', () => {
    render(<AuthMethodButtonList methods={[{
      id: 'oauth-gitcode',
      methodType: 'OAUTH_REDIRECT',
      provider: 'gitcode',
      displayName: 'GitCode Enterprise',
      actionUrl: '/oauth2/authorization/gitcode',
    }]} />)

    expect(screen.getByText('Login with GitCode Enterprise')).toBeTruthy()
  })

  it.each([false, true])('falls back to the localized GitCode label when the catalog omits a display name (compact=%s)', (compact) => {
    render(<AuthMethodButtonList methods={[{
      id: 'oauth-gitcode',
      methodType: 'OAUTH_REDIRECT',
      provider: 'gitcode',
      displayName: '  ',
      actionUrl: '/oauth2/authorization/gitcode',
    }]} compact={compact} />)

    expect(compact ? screen.getByText('GitCode') : screen.getByText('Login with GitCode')).toBeTruthy()
  })

  it('falls back to the provider code instead of a raw translation key', () => {
    render(<AuthMethodButtonList methods={[{
      id: 'enterprise',
      methodType: 'OAUTH_REDIRECT',
      provider: 'enterprise',
      displayName: '',
      actionUrl: '/oauth2/authorization/enterprise',
    }]} compact />)

    expect(screen.getByText('enterprise')).toBeTruthy()
    expect(screen.queryByText('loginButton.providerGitcode')).toBeNull()
  })

  it('renders the supplied empty fallback when no OAuth methods are available', () => {
    render(<AuthMethodButtonList methods={methods.filter((method) => method.methodType !== 'OAUTH_REDIRECT')} emptyFallback={<p>No methods</p>} />)

    expect(screen.getByText('No methods')).toBeTruthy()
  })

  it('navigates to the configured OAuth action URL', () => {
    vi.stubGlobal('location', { href: 'http://localhost/login' })

    render(<AuthMethodButtonList methods={methods.slice(0, 1)} />)
    fireEvent.click(screen.getByText('Login with GitHub'))

    expect(window.location.href).toBe('/oauth2/authorization/github')
  })
})
