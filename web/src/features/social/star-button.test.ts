// @vitest-environment jsdom

import { createElement } from 'react'
import { fireEvent, render, screen, cleanup } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const { useAuth, useStar, useToggleStar, mutate } = vi.hoisted(() => ({
  useAuth: vi.fn(),
  useStar: vi.fn(),
  useToggleStar: vi.fn(),
  mutate: vi.fn(),
}))

vi.mock('@/features/auth/use-auth', () => ({ useAuth }))
vi.mock('./use-star', () => ({ useStar, useToggleStar }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

import { StarButton } from './star-button'

describe('StarButton', () => {
  beforeEach(() => {
    mutate.mockClear()
    useStar.mockClear()
    useAuth.mockReturnValue({ isAuthenticated: false })
    useStar.mockReturnValue({ data: { starred: false }, isLoading: false })
    useToggleStar.mockReturnValue({ mutate, isPending: false })
  })
  afterEach(() => cleanup())

  it('renders the unstarred control for anonymous visitors without asking for star state', () => {
    render(createElement(StarButton, { skillId: 7, starCount: 3 }))

    expect(screen.getByRole('button', { name: /starButton\.star\s*\(3\)/ })).toBeTruthy()
    // The endpoint is authenticated: anonymous readers must not trigger a guaranteed 401.
    expect(useStar).toHaveBeenCalledWith(7, false)
    expect(useStar).not.toHaveBeenCalledWith(7, true)
  })

  it('asks for login instead of writing when an anonymous visitor clicks', () => {
    const onRequireLogin = vi.fn()
    render(createElement(StarButton, { skillId: 7, starCount: 3, onRequireLogin }))

    fireEvent.click(screen.getByRole('button'))

    expect(onRequireLogin).toHaveBeenCalledTimes(1)
    expect(mutate).not.toHaveBeenCalled()
  })

  it('stars when the signed-in user has not starred the skill', () => {
    useAuth.mockReturnValue({ isAuthenticated: true })
    render(createElement(StarButton, { skillId: 7, starCount: 3 }))

    fireEvent.click(screen.getByRole('button', { name: /starButton\.star\s*\(3\)/ }))

    // mutate(false) => "currently unstarred, so this call stars it".
    expect(mutate).toHaveBeenCalledWith(false)
  })

  it('unstars when the signed-in user already starred the skill', () => {
    useAuth.mockReturnValue({ isAuthenticated: true })
    useStar.mockReturnValue({ data: { starred: true }, isLoading: false })
    render(createElement(StarButton, { skillId: 7, starCount: 4 }))

    fireEvent.click(screen.getByRole('button', { name: /starButton\.starred\s*\(4\)/ }))

    expect(mutate).toHaveBeenCalledWith(true)
  })

  it('does not render a control before a signed-in reader\'s own state is known', () => {
    useAuth.mockReturnValue({ isAuthenticated: true })
    useStar.mockReturnValue({ data: undefined, isLoading: true })

    const { container } = render(createElement(StarButton, { skillId: 7, starCount: 3 }))

    expect(container.textContent).toBe('')
  })
})
