import { describe, expect, mock, test } from 'bun:test'
import { AuthService } from '../../../src/services/auth-service'
import { CliError } from '../../../src/shared/errors'
import { EXIT } from '../../../src/shared/constants'

// A GitCode deployment sends a GitCode personal access token as the bearer
// credential. It must use the existing login path: validate with whoami, then
// persist into the existing per-registry credential store.
describe('AuthService token login (GitCode PAT)', () => {
  test('validates the PAT with whoami before persisting it', async () => {
    const setRegistry = mock(async () => {})
    const setToken = mock(async () => {})
    const whoami = mock(async () => ({ handle: 'gitcode-user', displayName: 'GitCode User' }))
    const clientFactory = mock((_registry: string, _token?: string) => ({ whoami }))

    const service = new AuthService(
      { setRegistry } as never,
      { setToken } as never,
      { clientFactory: clientFactory as never }
    )

    const result = await service.login('https://skillhub.example.com', 'pat-secret')

    expect(result).toEqual({ handle: 'gitcode-user' })
    expect(clientFactory).toHaveBeenCalledWith('https://skillhub.example.com', 'pat-secret')
    expect(whoami).toHaveBeenCalledTimes(1)
    expect(setRegistry).toHaveBeenCalledWith('https://skillhub.example.com')
    expect(setToken).toHaveBeenCalledWith('https://skillhub.example.com', 'pat-secret')
  })

  test('does not persist the PAT when whoami rejects it', async () => {
    const setRegistry = mock(async () => {})
    const setToken = mock(async () => {})
    const service = new AuthService(
      { setRegistry } as never,
      { setToken } as never,
      {
        clientFactory: (() => ({
          whoami: async () => { throw new CliError('authentication failed', EXIT.auth) }
        })) as never
      }
    )

    await expect(service.login('https://skillhub.example.com', 'pat-invalid'))
      .rejects.toMatchObject({ message: 'authentication failed', exitCode: EXIT.auth })
    expect(setRegistry).not.toHaveBeenCalled()
    expect(setToken).not.toHaveBeenCalled()
  })
})
