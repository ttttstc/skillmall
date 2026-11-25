import { ConfigStore } from '../stores/config-store'
import { CredentialsStore } from '../stores/credentials-store'
import { AuthService } from '../services/auth-service'
import { resolveRegistry, resolveToken } from '../services/registry-service'
import { openExternalUrl } from '../platform/browser'
import { CliError } from '../shared/errors'
import { EXIT } from '../shared/constants'

export interface LoginCommandOptions {
  registry?: string
  token?: string
  /**
   * GitCode personal access token. A GitCode deployment accepts this PAT as the
   * bearer credential, so it follows the same validation and storage path as
   * `--token`; it is only a second spelling of the same credential.
   */
  gitcodeToken?: string | undefined
  json?: boolean
  noOpen?: boolean
}

export async function loginCommand(options: LoginCommandOptions): Promise<string> {
  const configStore = new ConfigStore()
  const credentialsStore = new CredentialsStore()
  const registry = resolveRegistry(options, process.env, await configStore.read())
  if (options.gitcodeToken !== undefined && options.gitcodeToken.trim() === '') {
    throw new CliError('the GitCode token is empty', EXIT.usage, {
      next: 'pass --gitcode-token <PAT> or set GITCODE_TOKEN'
    })
  }
  // An explicit --gitcode-token wins over --token; both then follow the shared
  // resolution chain (SKILLHUB_TOKEN, GITCODE_TOKEN, stored token).
  const explicitToken = options.gitcodeToken ?? options.token
  const token = resolveToken({ token: explicitToken }, process.env, await credentialsStore.getToken(registry))
  const authService = new AuthService(configStore, credentialsStore)
  const result = token
    ? await authService.login(registry, token)
    : await authService.loginWithDeviceFlow(registry, async details => {
        const opened = options.noOpen ? false : openExternalUrl(details.verificationUri)
        const message = options.json
          ? JSON.stringify({ event: 'device_authorization', ...details, browserOpened: opened })
          : [
              `Authorize this device at: ${details.verificationUri}`,
              `Code: ${details.userCode}`,
              opened ? 'Browser launch requested. If no browser opened, use the URL above.' : 'Open the URL in a browser to continue.',
              `Waiting for authorization (expires in ${details.expiresIn}s)...`
            ].join('\n')
        process.stderr.write(`${message}\n`)
      })
  return options.json
    ? JSON.stringify({ ok: true, registry, handle: result.handle })
    : `Logged in to ${registry} as ${result.handle}`
}
