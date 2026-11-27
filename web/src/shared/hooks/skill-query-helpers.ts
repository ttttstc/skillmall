import type { SearchParams } from '@/api/types'
import { WEB_API_PREFIX } from '@/api/client'
import { normalizeSearchQuery } from '@/shared/lib/search-query'

export function buildSkillSearchUrl(params: SearchParams) {
  const queryParams = new URLSearchParams()
  const normalizedQuery = normalizeSearchQuery(params.q ?? '')

  if (params.q !== undefined) {
    queryParams.append('q', normalizedQuery)
  }

  if (params.namespace) {
    const cleanNamespace = params.namespace.startsWith('@') ? params.namespace.slice(1) : params.namespace
    queryParams.append('namespace', cleanNamespace)
  }

  if (params.label) {
    queryParams.append('label', params.label)
  }

  // `category` supersedes the legacy `domain` wire value, so only one of them is ever sent: a
  // request carrying both would make the server's precedence rule the only thing keeping the two
  // filters in sync, and a mismatch here would look like a server bug.
  if (params.category) {
    queryParams.append('category', params.category)
  } else if (params.domain) {
    queryParams.append('domain', params.domain)
  }

  // Repeated params: the server OR-s them and rejects a set that spans two first-level categories.
  for (const slug of params.sub ?? []) {
    if (slug) {
      queryParams.append('sub', slug)
    }
  }

  if (params.creator) {
    queryParams.append('creator', params.creator)
  }

  if (params.sort) {
    queryParams.append('sort', params.sort)
  }

  if (params.page !== undefined) {
    queryParams.append('page', String(params.page))
  }

  if (params.size !== undefined) {
    queryParams.append('size', String(params.size))
  }

  const queryString = queryParams.toString()
  return queryString ? `${WEB_API_PREFIX}/skills?${queryString}` : `${WEB_API_PREFIX}/skills`
}

export function shouldEnableNamespaceMemberCandidates(slug: string, search: string, enabled = true) {
  return enabled && !!slug && search.trim().length >= 2
}
