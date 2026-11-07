import { useQuery } from '@tanstack/react-query'
import type { HomeRanking, HomeRankingType, HomeSkillList, SkillSummary } from '@/api/types'
import { fetchJson, WEB_API_PREFIX } from '@/api/client'
import type { SkillDomain } from '@/shared/lib/skill-domains'

async function fetchRecommendations(size: number, excludeIds: number[], domain?: SkillDomain): Promise<SkillSummary[]> {
  const params = new URLSearchParams({ size: String(size) })
  if (excludeIds.length > 0) {
    params.append('exclude', excludeIds.join(','))
  }
  if (domain) {
    params.append('domain', domain)
  }
  const response = await fetchJson<HomeSkillList>(`${WEB_API_PREFIX}/home/recommendations?${params.toString()}`)
  return response.items
}

export async function fetchHomeRanking(type: HomeRankingType, size: number, domain?: SkillDomain): Promise<HomeRanking> {
  const params = new URLSearchParams({ type, size: String(size) })
  if (domain) {
    params.append('domain', domain)
  }
  return fetchJson<HomeRanking>(`${WEB_API_PREFIX}/home/rankings?${params.toString()}`)
}

/**
 * Generic, non-personalised recommendations.
 *
 * `excludeIds` holds what the caller currently shows and `rotation` is bumped by "换一换". Both
 * are part of the query key so a shuffle issues a fresh random draw instead of replaying cache.
 * `domain` is part of the key too: switching category must not show the previous category's row.
 */
export function useHomeRecommendations(size: number, excludeIds: number[], rotation: number, domain?: SkillDomain) {
  return useQuery({
    queryKey: ['home', 'recommendations', size, excludeIds.join('-'), rotation, domain ?? 'all'],
    queryFn: () => fetchRecommendations(size, excludeIds, domain),
    staleTime: 0,
  })
}

export function useHomeRanking(type: HomeRankingType, size: number, domain?: SkillDomain) {
  return useQuery({
    queryKey: ['home', 'ranking', type, size, domain ?? 'all'],
    queryFn: () => fetchHomeRanking(type, size, domain),
    staleTime: 60_000,
  })
}
