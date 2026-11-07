import { useQuery } from '@tanstack/react-query'
import type { HomeRanking, HomeRankingType, HomeSkillList, SkillSummary } from '@/api/types'
import { fetchJson, WEB_API_PREFIX } from '@/api/client'

async function fetchRecommendations(size: number, excludeIds: number[]): Promise<SkillSummary[]> {
  const params = new URLSearchParams({ size: String(size) })
  if (excludeIds.length > 0) {
    params.append('exclude', excludeIds.join(','))
  }
  const response = await fetchJson<HomeSkillList>(`${WEB_API_PREFIX}/home/recommendations?${params.toString()}`)
  return response.items
}

export async function fetchHomeRanking(type: HomeRankingType, size: number): Promise<HomeRanking> {
  const params = new URLSearchParams({ type, size: String(size) })
  return fetchJson<HomeRanking>(`${WEB_API_PREFIX}/home/rankings?${params.toString()}`)
}

/**
 * Generic, non-personalised recommendations.
 *
 * `excludeIds` holds what the caller currently shows and `rotation` is bumped by "换一换". Both
 * are part of the query key so a shuffle issues a fresh random draw instead of replaying cache.
 */
export function useHomeRecommendations(size: number, excludeIds: number[], rotation: number) {
  return useQuery({
    queryKey: ['home', 'recommendations', size, excludeIds.join('-'), rotation],
    queryFn: () => fetchRecommendations(size, excludeIds),
    staleTime: 0,
  })
}

export function useHomeRanking(type: HomeRankingType, size: number) {
  return useQuery({
    queryKey: ['home', 'ranking', type, size],
    queryFn: () => fetchHomeRanking(type, size),
    staleTime: 60_000,
  })
}
