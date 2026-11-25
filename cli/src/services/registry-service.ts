import { DEFAULT_REGISTRY } from '../shared/constants'

export function resolveRegistry(
  args: { registry?: string | undefined },
  env: NodeJS.ProcessEnv,
  config: { registry?: string | undefined }
): string {
  return normalizeRegistry(args.registry || env.SKILLHUB_REGISTRY || config.registry || DEFAULT_REGISTRY)
}

/**
 * Explicit argument, then `SKILLHUB_TOKEN`, then `GITCODE_TOKEN`, then the token
 * stored for that registry. A GitCode deployment accepts a GitCode personal
 * access token as the bearer credential, so `GITCODE_TOKEN` is an additional
 * source for the same header; it is never merged with or preferred over an
 * explicit credential. Surrounding whitespace is dropped because tokens pasted
 * from a web page or a CI secret often carry a trailing newline.
 */
export function resolveToken(
  args: { token?: string | undefined },
  env: NodeJS.ProcessEnv,
  storedToken?: string | undefined
): string | undefined {
  return firstNonEmpty(args.token, env.SKILLHUB_TOKEN, env.GITCODE_TOKEN, storedToken)
}

function firstNonEmpty(...candidates: Array<string | undefined>): string | undefined {
  for (const candidate of candidates) {
    const trimmed = typeof candidate === 'string' ? candidate.trim() : ''
    if (trimmed) return trimmed
  }
  return undefined
}

function normalizeRegistry(registry: string): string {
  return registry.replace(/\/+$/, '')
}
