import { useTranslation } from 'react-i18next'
import { KeyRound } from 'lucide-react'
import { isAuthTokenManagementEnabled } from '@/api/client'
import { TokenList } from '@/features/token/token-list'
import { DashboardPageHeader } from '@/shared/components/dashboard-page-header'
import { Card } from '@/shared/ui/card'

/**
 * Dedicated dashboard page for managing personal API tokens.
 *
 * Deployments that disabled SkillHub token issuance (identity moved to GitCode) keep the route but
 * replace the token UI with an explanation, so a bookmarked URL never shows an unactionable form.
 */
export function TokensPage() {
  const { t } = useTranslation()
  const tokenManagementEnabled = isAuthTokenManagementEnabled()

  return (
    <div className="space-y-8 animate-fade-up">
      <DashboardPageHeader
        title={t('tokens.pageTitle')}
        subtitle={t('tokens.pageSubtitle')}
      />
      {tokenManagementEnabled ? <TokenList /> : (
        <Card role="status" className="max-w-2xl p-6">
          <div className="flex items-start gap-3">
            <KeyRound aria-hidden="true" className="mt-0.5 h-5 w-5 shrink-0 text-muted-foreground" />
            <div className="space-y-1">
              <h2 className="text-base font-semibold text-foreground">{t('tokens.disabledTitle')}</h2>
              <p className="text-sm text-muted-foreground">{t('tokens.disabledDescription')}</p>
            </div>
          </div>
        </Card>
      )}
    </div>
  )
}
