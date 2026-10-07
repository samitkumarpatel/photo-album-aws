import { Camera } from 'lucide-react'

/**
 * The app's loading indicator: a camera with a spinning lens ring and a shutter "click".
 *
 * - `size` in pixels. Small sizes (about 20) fit inside buttons.
 * - `inherit` uses the surrounding text colour instead of the accent colour, for buttons and dark overlays.
 * - `delay` fades the spinner in after a moment, so fast loads never flash it.
 * - `label` is announced to screen readers; pass `decorative` when nearby text already says what is happening.
 */
export default function CameraSpinner({ size = 56, label = 'Loading', inherit = false, delay = false, decorative = false, className = '' }) {
  const classes = ['camera-spinner', inherit && 'inherit', delay && 'delayed', size < 32 && 'small', className].filter(Boolean).join(' ')
  return <span className={classes} style={{ '--size': size + 'px' }} {...(decorative ? { 'aria-hidden': true } : { role: 'status', 'aria-label': label })}>
    <span className="cs-track" />
    <span className="cs-ring" />
    <Camera className="cs-camera" strokeWidth={size < 32 ? 2.4 : 1.8} aria-hidden="true" />
    {size >= 32 && <span className="cs-flash" />}
  </span>
}

/** Centered spinner with a message, used while a page waits for the API. */
export function PageLoader({ label }) {
  return <div className="page-loader" role="status" aria-live="polite">
    <CameraSpinner size={64} decorative />
    <p>{label}</p>
  </div>
}
