import { useTranslation } from 'react-i18next'
import { AlertTriangle, Download, Terminal } from 'lucide-react'
import { Button } from '@/shared/ui/button'
import { InstallCommand } from '@/features/skill/install-command'

interface InstallGuideProps {
  namespace: string
  slug: string
  version?: string
  /** Whether the selected version can be distributed through the public download path. */
  installable: boolean
  /** Whether the caller may actually fetch the package (permission + lifecycle). */
  canDownload: boolean
  onDownload: () => void
}

/**
 * Install guidance shown on the skill detail page.
 *
 * The CLI command is generated from the current market origin and the full skill
 * coordinate, so it always points at the instance that served this page. Manual
 * installation is the fallback that stays available even when the CLI command
 * cannot be produced for the selected version.
 */
export function InstallGuide({ namespace, slug, version, installable, canDownload, onDownload }: InstallGuideProps) {
  const { t } = useTranslation()

  if (!installable) {
    return (
      <div className="rounded-xl border border-border/60 bg-muted/30 p-4 text-sm text-muted-foreground">
        {t('skillDetail.installNotDistributable')}
      </div>
    )
  }

  return (
    <div className="space-y-5">
      <section className="space-y-3">
        <div className="flex items-center gap-2">
          <Terminal className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
          <h3 className="text-sm font-semibold font-heading text-foreground">{t('skillDetail.installCliTitle')}</h3>
        </div>
        <p className="text-sm leading-6 text-muted-foreground">{t('skillDetail.installCliDescription')}</p>
        <InstallCommand
          analyticsId="skill.command.copy"
          namespace={namespace}
          slug={slug}
          version={version}
          skillhubAnalyticsId="skill.command.skillhub.select"
          clawhubAnalyticsId="skill.command.clawhub.select"
        />
        <ul className="space-y-1.5 text-sm leading-6 text-muted-foreground">
          <li>{t('skillDetail.installCliRequirements')}</li>
          <li>{t('skillDetail.installCliTargetHint')}</li>
        </ul>
      </section>

      <section className="space-y-3 border-t border-border/60 pt-5">
        <div className="flex items-center gap-2">
          <Download className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
          <h3 className="text-sm font-semibold font-heading text-foreground">{t('skillDetail.installManualTitle')}</h3>
        </div>
        <p className="text-sm leading-6 text-muted-foreground">
          {t('skillDetail.installManualDescription', { slug })}
        </p>
        <ol className="list-decimal space-y-1.5 pl-5 text-sm leading-6 text-muted-foreground">
          <li>{t('skillDetail.installManualStepDownload')}</li>
          <li>{t('skillDetail.installManualStepExtract', { slug })}</li>
          <li>{t('skillDetail.installManualStepVerify')}</li>
        </ol>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={onDownload}
          disabled={!canDownload}
        >
          <Download className="mr-2 h-4 w-4" aria-hidden="true" />
          {t('skillDetail.installManualDownload', { version: version ?? '' })}
        </Button>
        <p className="text-sm leading-6 text-muted-foreground">{t('skillDetail.installManualMetadataNote')}</p>
      </section>

      <section className="space-y-2 border-t border-border/60 pt-5">
        <div className="flex items-center gap-2">
          <AlertTriangle className="h-4 w-4 text-amber-600" aria-hidden="true" />
          <h3 className="text-sm font-semibold font-heading text-foreground">{t('skillDetail.installConflictTitle')}</h3>
        </div>
        <p className="text-sm leading-6 text-muted-foreground">{t('skillDetail.installConflictDescription')}</p>
      </section>
    </div>
  )
}
