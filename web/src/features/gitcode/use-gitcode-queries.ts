import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { importGitcodeSkill, inspectGitcodeRepository } from './api'
import type { GitcodeImportRequest, GitcodeImportResult, GitcodeInspectRequest } from './types'

const GITCODE_QUERY_ROOT = 'gitcode'
/** Repository contents are immutable per commit; keep a preview warm while the user walks the wizard. */
const PREVIEW_STALE_TIME_MS = 5 * 60 * 1000

/**
 * Step 1 → step 2: validate the address and discover the skill directories of the repository.
 *
 * A mutation, not a query, because it only runs when the user asks for it (下一步); the wizard
 * needs the request to fail inline on the address field rather than through the global error toast.
 */
export function useInspectGitcodeRepository() {
  return useMutation({
    mutationFn: (request: GitcodeInspectRequest) => inspectGitcodeRepository(request),
    meta: { skipGlobalErrorHandler: true },
  })
}

/**
 * Step 2: file tree and SKILL.md of the selected skill directory.
 *
 * Re-inspects the same endpoint with a pinned `path`, so switching directory in the picker reuses
 * the cache entry of a directory the user already looked at and fetches a new one otherwise.
 */
export function useGitcodeSkillPreview(params: { repoUrl: string; ref?: string; path: string | null; enabled?: boolean }) {
  return useQuery({
    queryKey: [GITCODE_QUERY_ROOT, 'preview', params.repoUrl, params.ref ?? '', params.path ?? ''],
    queryFn: () => inspectGitcodeRepository({
      repoUrl: params.repoUrl,
      ref: params.ref,
      path: params.path ?? undefined,
    }),
    enabled: params.enabled !== false && !!params.repoUrl && params.path !== null,
    staleTime: PREVIEW_STALE_TIME_MS,
    retry: false,
    meta: { skipGlobalErrorHandler: true },
  })
}

/**
 * Step 3: import through the existing publish pipeline.
 *
 * The cache refresh mirrors `usePublishSkill`, so a successful import lands on the same My Skills
 * and review lists as a local upload.
 */
export function useImportGitcodeSkill() {
  const queryClient = useQueryClient()

  return useMutation<GitcodeImportResult, unknown, { namespace: string; request: GitcodeImportRequest }>({
    mutationFn: ({ namespace, request }) => importGitcodeSkill(namespace, request),
    meta: { skipGlobalErrorHandler: true },
    onSuccess: async (result) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['skills'] }),
        queryClient.invalidateQueries({ queryKey: ['reviews'] }),
        queryClient.invalidateQueries({ queryKey: ['skill-avatar', result.namespace, result.slug] }),
      ])
    },
  })
}
