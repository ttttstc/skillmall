import { useTranslation } from 'react-i18next'
import { Button } from '@/shared/ui/button'
import { useStar, useToggleStar } from './use-star'
import { Bookmark } from 'lucide-react'
import { useAuth } from '@/features/auth/use-auth'

interface StarButtonProps {
  skillId: number
  starCount: number
  onRequireLogin?: () => void
}

/**
 * Toggles the current user's star state for a skill while delegating the login
 * prompt to the surrounding page. The read query is kept separate so pages can
 * decide independently whether star controls should be rendered at all.
 *
 * Anonymous visitors keep the control so the login prompt stays reachable, but no
 * star state is requested for them: the endpoint is authenticated, so asking would
 * only produce a guaranteed 401. Their view is the unstarred one.
 */
export function StarButton({ skillId, starCount, onRequireLogin }: StarButtonProps) {
  const { t } = useTranslation()
  const { isAuthenticated } = useAuth()
  const { data: starStatus, isLoading } = useStar(skillId, isAuthenticated)
  const toggleMutation = useToggleStar(skillId)

  const starred = isAuthenticated && starStatus?.starred === true

  const handleToggle = () => {
    if (!isAuthenticated) {
      onRequireLogin?.()
      return
    }
    if (starStatus) {
      toggleMutation.mutate(starStatus.starred)
    }
  }

  // A signed-in reader cannot act before their own state is known; showing the wrong
  // state is worse than showing nothing.
  if (isAuthenticated && (isLoading || !starStatus)) {
    return null
  }

  return (
    <Button data-analytics-id={starred ? 'skill.star.remove' : 'skill.star.add'}
      variant={starred ? 'default' : 'outline'}
      size="sm"
      className="justify-between"
      onClick={handleToggle}
      disabled={toggleMutation.isPending}
    >
      <Bookmark className={`w-4 h-4 mr-2 ${starred ? 'fill-current' : ''}`} />
      {starred ? t('starButton.starred') : t('starButton.star')} ({starCount})
    </Button>
  )
}
