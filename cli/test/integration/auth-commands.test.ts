import { afterEach, describe, expect, test } from 'bun:test'
import { createTempHome } from '../helpers/temp-env'
import { startFakeRegistry } from '../helpers/fake-registry'
import { runCli } from '../helpers/run-cli'

let registry: Awaited<ReturnType<typeof startFakeRegistry>> | undefined

afterEach(() => {
  registry?.stop()
  registry = undefined
})

describe('auth commands', () => {
  // -------------------------------------------------------------------------
  // login
  // -------------------------------------------------------------------------

  test('login stores registry and token only after whoami succeeds', async () => {
    const env = await createTempHome()
    registry = await startFakeRegistry({ token: 'sk_ok', user: { handle: 'u1', displayName: 'User One' } })

    const result = await runCli(['login', '--registry', registry.url, '--token', 'sk_ok'], {
      HOME: env.home,
      USERPROFILE: env.home
    })

    expect(result.exitCode).toBe(0)
    expect(result.stdout).toContain('Logged in')
    expect(await Bun.file(`${env.home}/.skillhub/config.json`).json()).toMatchObject({ registry: registry.url })
    expect(await Bun.file(`${env.home}/.skillhub/credentials.json`).json()).toMatchObject({ tokens: { [registry.url]: 'sk_ok' } })
  })

  test('login and logout preserve compatible third-party state', async () => {
    const env = await createTempHome()
    registry = await startFakeRegistry({ token: 'sk_ok', user: { handle: 'u1', displayName: 'User One' } })
    const thirdPartyUser = { token: 'third-party-token', host: 'https://api.skillhub.cn' }
    await Bun.write(`${env.home}/.skillhub/config.json`, JSON.stringify({
      self_update_url: 'https://skillhub.example.com/version.json',
      auto_self_upgrade: false
    }))
    await Bun.write(`${env.home}/.skillhub/credentials.json`, JSON.stringify({ user: thirdPartyUser }))

    const login = await runCli(['login', '--registry', registry.url, '--token', 'sk_ok'], {
      HOME: env.home,
      USERPROFILE: env.home
    })
    expect(login.exitCode).toBe(0)
    expect(await Bun.file(`${env.home}/.skillhub/config.json`).json()).toEqual({
      self_update_url: 'https://skillhub.example.com/version.json',
      auto_self_upgrade: false,
      registry: registry.url
    })
    expect(await Bun.file(`${env.home}/.skillhub/credentials.json`).json()).toEqual({
      user: thirdPartyUser,
      tokens: { [registry.url]: 'sk_ok' }
    })

    const logout = await runCli(['logout', '--registry', registry.url], {
      HOME: env.home,
      USERPROFILE: env.home
    })
    expect(logout.exitCode).toBe(0)
    expect(await Bun.file(`${env.home}/.skillhub/credentials.json`).json()).toEqual({
      user: thirdPartyUser,
      tokens: {}
    })
  })

  test('login fails with invalid token', async () => {
    const env = await createTempHome()
    registry = await startFakeRegistry({ token: 'sk_ok' })

    const result = await runCli(['login', '--registry', registry.url, '--token', 'sk_bad'], {
      HOME: env.home,
      USERPROFILE: env.home
    })

    expect(result.exitCode).toBe(2)
    expect(result.stderr).toContain('authentication failed')
  })

  test('login without a token uses device flow and never prints the access token', async () => {
    const env = await createTempHome()
    registry = await startFakeRegistry({
      token: 'oauth-secret',
      user: { handle: 'oauth-user', displayName: 'OAuth User' },
      deviceFlow: { accessToken: 'oauth-secret', pendingPolls: 1 }
    })

    const result = await runCli(['login', '--registry', registry.url, '--no-open'], {
      HOME: env.home,
      USERPROFILE: env.home
    })

    expect(result.exitCode).toBe(0)
    expect(result.stdout).toContain('Logged in')
    expect(result.stderr).toContain('ABCD-2345')
    expect(result.stderr).toContain('/device')
    expect(`${result.stdout}\n${result.stderr}`).not.toContain('oauth-secret')
    expect(await Bun.file(`${env.home}/.skillhub/credentials.json`).json())
      .toMatchObject({ tokens: { [registry.url]: 'oauth-secret' } })
    expect(registry.received.devicePolls).toBe(2)
  })

  test('device login --json emits a non-secret authorization event and final result', async () => {
    const env = await createTempHome()
    registry = await startFakeRegistry({
      token: 'oauth-secret',
      user: { handle: 'oauth-user', displayName: 'OAuth User' },
      deviceFlow: { accessToken: 'oauth-secret' }
    })

    const result = await runCli(['login', '--registry', registry.url, '--no-open', '--json'], {
      HOME: env.home,
      USERPROFILE: env.home
    })

    expect(result.exitCode).toBe(0)
    expect(JSON.parse(result.stdout)).toEqual({ ok: true, registry: registry.url, handle: 'oauth-user' })
    expect(JSON.parse(result.stderr)).toMatchObject({
      event: 'device_authorization',
      userCode: 'ABCD-2345',
      verificationUri: `${registry.url}/device`,
      browserOpened: false
    })
    expect(`${result.stdout}\n${result.stderr}`).not.toContain('oauth-secret')
    expect(`${result.stdout}\n${result.stderr}`).not.toContain('device-secret')
  })

  // [P0] whoami failure must NOT write credentials
  test('login does not write credentials when whoami fails', async () => {
    const env = await createTempHome()
    registry = await startFakeRegistry({ token: 'sk_ok', failures: { whoami: 'auth' } })

    const result = await runCli(['login', '--registry', registry.url, '--token', 'sk_bad'], {
      HOME: env.home,
      USERPROFILE: env.home
    })

    expect(result.exitCode).not.toBe(0)

    const credFile = Bun.file(`${env.home}/.skillhub/credentials.json`)
    const exists = await credFile.exists()
    if (exists) {
      const creds = await credFile.json() as { tokens?: Record<string, string> }
      expect(creds.tokens?.[registry.url]).toBeUndefined()
    }
    // file not existing is also acceptable — either way no token was stored
  })

  // [P1] --json output shape on success
  test('login --json emits { ok, registry, handle }', async () => {
    const env = await createTempHome()
    registry = await startFakeRegistry({ token: 'sk_ok', user: { handle: 'u1', displayName: 'User One' } })

    const result = await runCli(['login', '--registry', registry.url, '--token', 'sk_ok', '--json'], {
      HOME: env.home,
      USERPROFILE: env.home
    })

    expect(result.exitCode).toBe(0)
    const parsed = JSON.parse(result.stdout)
    expect(parsed).toEqual({ ok: true, registry: registry.url, handle: 'u1' })
  })

  // [P1] network error → EXIT.network. The exit code is the contract; the
  // exact message can be "registry unreachable" or "registry returned 5xx"
  // depending on whether Bun's fetch throws or returns a 5xx Response on
  // connection refusal — both indicate the same network-class failure.
  test('login with network failure exits with network error', async () => {
    const env = await createTempHome()
    registry = await startFakeRegistry({ token: 'sk_ok', failures: { whoami: 'network' } })

    const result = await runCli(['login', '--registry', registry.url, '--token', 'sk_ok'], {
      HOME: env.home,
      USERPROFILE: env.home
    })

    expect(result.exitCode).toBe(3) // EXIT.network
    expect(result.stderr).toMatch(/registry unreachable|registry returned 5\d\d/)
  })

  // -------------------------------------------------------------------------
  // login with a GitCode personal access token
  //
  // A GitCode deployment accepts a GitCode PAT as `Authorization: Bearer <PAT>`.
  // It must travel through the same whoami validation and the same credentials
  // store as --token: one credential store, one validation path.
  // -------------------------------------------------------------------------

  test('login --gitcode-token validates the PAT with whoami, then stores it', async () => {
    const env = await createTempHome()
    // The fake registry only accepts this exact bearer value, so a successful
    // whoami proves the PAT itself was sent.
    registry = await startFakeRegistry({ token: 'pat_gitcode', user: { handle: 'gc-user', displayName: 'GitCode User' } })

    const result = await runCli(['login', '--registry', registry.url, '--gitcode-token', 'pat_gitcode'], {
      HOME: env.home,
      USERPROFILE: env.home
    })

    expect(result.exitCode).toBe(0)
    expect(result.stdout).toContain('Logged in')
    expect(result.stdout).toContain('gc-user')
    expect(`${result.stdout}\n${result.stderr}`).not.toContain('pat_gitcode')
    expect(await Bun.file(`${env.home}/.skillhub/credentials.json`).json())
      .toMatchObject({ tokens: { [registry.url]: 'pat_gitcode' } })
    expect(await Bun.file(`${env.home}/.skillhub/config.json`).json()).toMatchObject({ registry: registry.url })
  })

  test('GITCODE_TOKEN supplies the PAT when --gitcode-token is absent', async () => {
    const env = await createTempHome()
    registry = await startFakeRegistry({ token: 'pat_env', user: { handle: 'env-user', displayName: 'Env User' } })

    const result = await runCli(['login', '--registry', registry.url], {
      HOME: env.home,
      USERPROFILE: env.home,
      GITCODE_TOKEN: 'pat_env'
    })

    expect(result.exitCode).toBe(0)
    expect(result.stdout).toContain('env-user')
    expect(`${result.stdout}\n${result.stderr}`).not.toContain('pat_env')
    expect(await Bun.file(`${env.home}/.skillhub/credentials.json`).json())
      .toMatchObject({ tokens: { [registry.url]: 'pat_env' } })
  })

  test('--gitcode-token wins over GITCODE_TOKEN and over --token', async () => {
    const env = await createTempHome()
    registry = await startFakeRegistry({ token: 'pat_flag', user: { handle: 'flag-user', displayName: 'Flag User' } })

    const result = await runCli(
      ['login', '--registry', registry.url, '--gitcode-token', 'pat_flag', '--token', 'sk_wrong'],
      { HOME: env.home, USERPROFILE: env.home, GITCODE_TOKEN: 'pat_wrong' }
    )

    expect(result.exitCode).toBe(0)
    expect(result.stdout).toContain('flag-user')
    expect(await Bun.file(`${env.home}/.skillhub/credentials.json`).json())
      .toMatchObject({ tokens: { [registry.url]: 'pat_flag' } })
  })

  test('an empty --gitcode-token is a usage error instead of a device-flow login', async () => {
    const env = await createTempHome()

    const result = await runCli(['login', '--gitcode-token', ''], {
      HOME: env.home,
      USERPROFILE: env.home
    })

    expect(result.exitCode).toBe(5) // EXIT.usage
    expect(result.stderr).toContain('GitCode token is empty')
    expect(result.stderr).toContain('--gitcode-token')
  })

  test('--gitcode-token without a value is a usage error', async () => {
    const env = await createTempHome()

    const result = await runCli(['login', '--gitcode-token'], {
      HOME: env.home,
      USERPROFILE: env.home
    })

    expect(result.exitCode).toBe(5) // EXIT.usage
    expect(result.stderr).toMatch(/value is missing/)
  })

  // A GitCode deployment disables the device flow server-side, so the public
  // device endpoints answer 404. The error must name the credential that works.
  test('device flow answered with 404 points the user at --gitcode-token', async () => {
    const env = await createTempHome()
    registry = await startFakeRegistry({ token: 'pat_gitcode' })

    const result = await runCli(['login', '--registry', registry.url, '--no-open'], {
      HOME: env.home,
      USERPROFILE: env.home
    })

    expect(result.exitCode).toBe(2) // EXIT.auth
    expect(result.stderr).toContain('does not offer OAuth Device Flow login')
    expect(result.stderr).toContain('skillhub login --gitcode-token <PAT>')
    expect(await Bun.file(`${env.home}/.skillhub/credentials.json`).exists()).toBe(false)
  })

  // -------------------------------------------------------------------------
  // logout
  // -------------------------------------------------------------------------

  test('logout removes token', async () => {
    const env = await createTempHome()
    registry = await startFakeRegistry({ token: 'sk_ok', user: { handle: 'u1', displayName: 'User One' } })

    await runCli(['login', '--registry', registry.url, '--token', 'sk_ok'], {
      HOME: env.home,
      USERPROFILE: env.home
    })

    const result = await runCli(['logout', '--registry', registry.url], {
      HOME: env.home,
      USERPROFILE: env.home
    })

    expect(result.exitCode).toBe(0)
    expect(result.stdout).toContain('Logged out')
  })

  // [P0] credentials entry is actually deleted after logout
  test('logout actually removes the token from credentials.json', async () => {
    const env = await createTempHome()
    registry = await startFakeRegistry({ token: 'sk_ok', user: { handle: 'u1', displayName: 'User One' } })

    await runCli(['login', '--registry', registry.url, '--token', 'sk_ok'], {
      HOME: env.home,
      USERPROFILE: env.home
    })

    // Confirm token is present before logout
    const before = await Bun.file(`${env.home}/.skillhub/credentials.json`).json() as { tokens: Record<string, string> }
    expect(before.tokens[registry.url]).toBe('sk_ok')

    await runCli(['logout', '--registry', registry.url], {
      HOME: env.home,
      USERPROFILE: env.home
    })

    // Token must be absent after logout
    const after = await Bun.file(`${env.home}/.skillhub/credentials.json`).json() as { tokens: Record<string, string> }
    expect(after.tokens[registry.url]).toBeUndefined()
  })

  // [P1] logout when no token exists should still succeed
  test('logout when not logged in exits 0 with success message', async () => {
    const env = await createTempHome()
    registry = await startFakeRegistry({})

    const result = await runCli(['logout', '--registry', registry.url], {
      HOME: env.home,
      USERPROFILE: env.home
    })

    expect(result.exitCode).toBe(0)
    expect(result.stdout).toContain('Logged out')
  })

  // [P1] --json output on logout
  test('logout --json emits { ok, registry }', async () => {
    const env = await createTempHome()
    registry = await startFakeRegistry({ token: 'sk_ok', user: { handle: 'u1', displayName: 'User One' } })

    await runCli(['login', '--registry', registry.url, '--token', 'sk_ok'], {
      HOME: env.home,
      USERPROFILE: env.home
    })

    const result = await runCli(['logout', '--registry', registry.url, '--json'], {
      HOME: env.home,
      USERPROFILE: env.home
    })

    expect(result.exitCode).toBe(0)
    const parsed = JSON.parse(result.stdout)
    expect(parsed).toEqual({ ok: true, registry: registry.url })
  })
})
