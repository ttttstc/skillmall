import { useTranslation } from 'react-i18next'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/shared/ui/dialog'
import { SecurityAuditSection } from './security-audit-section'

interface SecurityAuditDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  skillId: number
  versionId: number
  versionStatus?: string
}

/**
 * Detail surface for one version's security scan records.
 *
 * Shared by the sidebar summary card and the detail header status cell so both entries open the
 * same evidence, and neither can drift into presenting a verdict the scanner never produced.
 */
export function SecurityAuditDialog({ open, onOpenChange, skillId, versionId, versionStatus }: SecurityAuditDialogProps) {
  const { t } = useTranslation()

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent closeAnalyticsId="security.scan.close" className="w-[min(calc(100vw-2rem),48rem)] max-h-[calc(100vh-2rem)] overflow-hidden flex flex-col">
        <DialogHeader className="shrink-0">
          <DialogTitle>{t('securityAudit.title')}</DialogTitle>
          <DialogDescription>{t('securityAudit.dialogDescription')}</DialogDescription>
        </DialogHeader>
        <div className="-mx-8 -mb-8 overflow-y-auto overscroll-contain px-8 pb-8">
          <SecurityAuditSection skillId={skillId} versionId={versionId} versionStatus={versionStatus} bare />
        </div>
      </DialogContent>
    </Dialog>
  )
}
