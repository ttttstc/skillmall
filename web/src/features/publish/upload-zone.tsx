import { useCallback, useEffect, useRef, useState, type ChangeEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useDropzone } from 'react-dropzone'
import { cn } from '@/shared/lib/utils'

interface UploadZoneProps {
  analyticsId?: string
  folderAnalyticsId?: string
  pickerAnalyticsId?: string
  onFileSelect: (file: File) => void
  /** Optional: called with the raw files of a picked folder (webkitdirectory). */
  onFolderSelect?: (files: File[]) => void
  disabled?: boolean
  className?: string
}

export function supportsDirectorySelection(input: HTMLInputElement | null): boolean {
  return Boolean(input && ('webkitdirectory' in input || 'directory' in input))
}

/**
 * Provides the publish page dropzone for uploading one zip package at a time.
 * The component is intentionally stateless so packaging validation can remain in
 * the publish flow that knows the surrounding form and backend constraints.
 */
export function UploadZone({ onFileSelect, onFolderSelect, disabled, analyticsId, folderAnalyticsId, pickerAnalyticsId, className }: UploadZoneProps) {
  const { t } = useTranslation()
  const folderInputRef = useRef<HTMLInputElement>(null)
  const [folderSelectionSupported, setFolderSelectionSupported] = useState(false)

  // `webkitdirectory` / `directory` are not in React's input attribute types;
  // set them imperatively so the folder picker works without an untyped cast.
  useEffect(() => {
    const el = folderInputRef.current
    const supported = supportsDirectorySelection(el)
    if (el && supported) {
      el.setAttribute('webkitdirectory', '')
      el.setAttribute('directory', '')
    }
    setFolderSelectionSupported(supported)
  }, [])

  const onDrop = useCallback(
    (acceptedFiles: File[]) => {
      if (acceptedFiles.length > 0) {
        onFileSelect(acceptedFiles[0])
      }
    },
    [onFileSelect]
  )

  const { getRootProps, getInputProps, isDragActive } = useDropzone({
    onDrop,
    accept: {
      'application/zip': ['.zip'],
    },
    maxFiles: 1,
    disabled,
  })

  const handleFolderChange = (event: ChangeEvent<HTMLInputElement>) => {
    const files = event.target.files
    if (files && files.length > 0) {
      onFolderSelect?.(Array.from(files))
    }
    // Reset so picking the same folder again re-triggers change.
    event.target.value = ''
  }

  return (
    <div className="flex flex-col gap-3">
      <div
        {...getRootProps()}
        data-analytics-id={disabled ? undefined : analyticsId}
        data-analytics-event={!disabled && analyticsId ? 'click+keydown+drop' : undefined}
        className={cn(
          'upload-zone flex min-h-[260px] flex-col justify-center rounded-md p-10 text-center cursor-pointer transition-all duration-300 md:min-h-[420px]',
          className,
          isDragActive && 'border-primary bg-primary/5',
          disabled && 'opacity-50 cursor-not-allowed'
        )}
      >
        <input {...getInputProps()} aria-label={t('upload.dragHint')} data-analytics-id={disabled ? undefined : analyticsId} />
        <div className="flex flex-col items-center gap-4">
          <svg
            className={cn(
              'h-6 w-6 upload-zone-icon transition-colors',
              isDragActive && 'text-primary'
            )}
            fill="none"
            stroke="currentColor"
            strokeWidth={1.5}
            viewBox="0 0 24 24"
            aria-hidden="true"
          >
            <path strokeLinecap="round" strokeLinejoin="round" d="M12 5v14M5 12h14" />
          </svg>
          {isDragActive ? (
            <p className="text-sm text-primary font-medium">{t('upload.dropHint')}</p>
          ) : (
            <div className="space-y-1.5">
              <p className="text-sm font-medium text-foreground">{t('upload.dragHint')}</p>
              <p className="text-xs text-muted-foreground">{t('upload.formatHint')}</p>
            </div>
          )}
        </div>
      </div>
      {onFolderSelect && (
        <div className="text-center">
          <input
            data-analytics-id={folderAnalyticsId}
            ref={folderInputRef}
            type="file"
            className="hidden"
            multiple
            onChange={handleFolderChange}
            disabled={disabled}
          />
          {folderSelectionSupported ? (
            <button
              data-analytics-id={pickerAnalyticsId}
              type="button"
              className="text-xs text-muted-foreground underline-offset-2 hover:text-foreground hover:underline disabled:cursor-not-allowed disabled:opacity-50"
              onClick={() => folderInputRef.current?.click()}
              disabled={disabled}
            >
              {t('upload.folderHint')}
            </button>
          ) : null}
        </div>
      )}
    </div>
  )
}
