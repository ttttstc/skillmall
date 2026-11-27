import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { categoryApi } from '@/api/client'

/** Query key for the two-level catalogue; exported so mutations can invalidate it precisely. */
export const CATEGORY_TREE_QUERY_KEY = ['categories', 'tree'] as const

/**
 * The two-level category catalogue used by the discovery sidebar.
 *
 * The catalogue changes when a category administrator edits it, not on every page view, so it is
 * cached for a few minutes. Counts ride along with the same payload: a separate count endpoint
 * would let the sidebar and the result grid disagree about how many skills a category holds.
 */
export function useCategories() {
  return useQuery({
    queryKey: CATEGORY_TREE_QUERY_KEY,
    queryFn: () => categoryApi.tree(),
    staleTime: 5 * 60_000,
  })
}

/**
 * Creator lookup for the "created by" filter.
 *
 * Only people who already own publicly distributable skills are returned, so this cannot be used
 * to enumerate accounts. Keep `enabled` false until the picker is actually open.
 */
export function useSkillAuthors(query: string, enabled = true) {
  return useQuery({
    queryKey: ['skill-authors', query],
    queryFn: () => categoryApi.authors(query),
    enabled,
    placeholderData: keepPreviousData,
    staleTime: 60_000,
  })
}
