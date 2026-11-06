import { useTranslation } from 'react-i18next'
import { Shield } from 'lucide-react'
import { Card } from '@/shared/ui/card'

/**
 * Market-level security evaluation state for a public skill.
 *
 * The SkillMall first release does not run automated security evaluation, so the market never
 * presents a passing result. This notice states that capability boundary; it must not be read as
 * a per-skill verdict. Owner/admin audit details are rendered separately when they exist.
 */
export function SecurityEvaluationNotice() {
  const { t } = useTranslation()

  return (
    <Card className="p-5 space-y-2">
      <div className="flex items-center gap-2">
        <Shield className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
        <span className="text-sm font-semibold font-heading text-foreground">
          {t('securityAudit.notEvaluatedTitle')}
        </span>
      </div>
      <p className="text-xs leading-5 text-muted-foreground">
        {t('securityAudit.notEvaluatedDescription')}
      </p>
    </Card>
  )
}
