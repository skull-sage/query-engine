const STYLES = {
  error: 'bg-rose-50 text-rose-800 ring-rose-200',
  warning: 'bg-amber-50 text-amber-900 ring-amber-200',
  success: 'bg-emerald-50 text-emerald-800 ring-emerald-200',
  info: 'bg-blue-50 text-blue-800 ring-blue-200',
}

/**
 * Inline message box. Errors and warnings are announced to screen readers (role="alert");
 * info and success are polite (role="status"). `actions` renders buttons under the message.
 */
export default function Alert({ variant = 'info', title, children, actions, className = '' }) {
  const urgent = variant === 'error' || variant === 'warning'
  return (
    <div
      role={urgent ? 'alert' : 'status'}
      className={`rounded-md px-4 py-3 text-sm ring-1 ring-inset motion-safe:animate-fade-in ${STYLES[variant]} ${className}`}
    >
      {title && <p className="font-semibold">{title}</p>}
      {children && <div className={title ? 'mt-1' : ''}>{children}</div>}
      {actions && <div className="mt-3 flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  )
}

// Plain titles for engine codes whose raw name would mean little to someone using the UI.
const CODE_TITLES = {
  SQL_REJECTED: 'Not a read-only SELECT',
}

/** Renders an EngineError (or any Error) with its engine code as the title. */
export function ErrorAlert({ error, title, actions, className }) {
  if (!error) return null
  return (
    <Alert variant="error" title={title || CODE_TITLES[error.code] || error.code || 'Something went wrong'} actions={actions} className={className}>
      {error.message || String(error)}
    </Alert>
  )
}
