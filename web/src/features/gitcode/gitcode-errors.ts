import { ApiError } from '@/api/client'

/**
 * Backend error codes of the GitCode importer (openspec GITCODE-IMPORT-DESIGN.md §4.3).
 * Each one maps to an i18n key; until those keys exist the caller substitutes a localized
 * fallback so the wizard never renders a raw key.
 */
export const GITCODE_ERROR_CODES = [
  'error.gitcode.url.invalid',
  'error.gitcode.unreachable',
  'error.gitcode.rateLimited',
  'error.gitcode.repo.notFound',
  'error.gitcode.skillMd.notFound',
  'error.gitcode.skillAmbiguous',
  'error.gitcode.tooLarge',
  'error.gitcode.tooManyFiles',
  'error.gitcode.version.invalid',
] as const

export type GitcodeErrorCode = typeof GITCODE_ERROR_CODES[number]

/** Shown when the failure carries no recognizable code. */
export const GITCODE_ERROR_FALLBACK_KEY = 'publish.repoInspectFailed'

function collectMessages(error: unknown): string[] {
  if (error instanceof ApiError) {
    return [error.serverMessageKey, error.serverMessage, error.message].filter((value): value is string => !!value)
  }

  return error instanceof Error ? [error.message] : []
}

/** Returns the importer error code carried by a failed request, when there is one. */
export function gitcodeErrorCode(error: unknown): GitcodeErrorCode | undefined {
  const messages = collectMessages(error)
  return GITCODE_ERROR_CODES.find((code) => messages.some((message) => message.includes(code)))
}

/**
 * Picks the copy for a failed inspect/import request.
 *
 * A coded failure uses its own key (falling back to the generic "could not read the repository"
 * copy); anything else keeps the server's localized message, which is more specific than ours.
 */
export function gitcodeErrorKeys(error: unknown): { key: string; fallbackKey: string } | { message: string } {
  const code = gitcodeErrorCode(error)
  if (code) {
    return { key: code, fallbackKey: GITCODE_ERROR_FALLBACK_KEY }
  }

  const serverMessage = error instanceof ApiError ? error.serverMessage ?? error.message : undefined
  if (serverMessage) {
    return { message: serverMessage }
  }

  return { key: GITCODE_ERROR_FALLBACK_KEY, fallbackKey: GITCODE_ERROR_FALLBACK_KEY }
}
