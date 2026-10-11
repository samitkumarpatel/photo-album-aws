import { isCrossOrigin } from '../media.js'

/*
 * Loads the original so drawing it doesn't taint the canvas. Cross-origin URLs (CloudFront / S3) are fetched with CORS
 * and no cache, because an earlier non-CORS <img> load of the same URL may sit in the HTTP cache without
 * Access-Control-Allow-Origin. Fetch same-origin URLs too: they may redirect to S3.
 * Wait for the load event rather than decode(), which can reject usable images on Safari.
 */
export async function loadEditableImage(src, signal) {
  const image = new Image()
  image.decoding = 'async'
  const response = await fetch(src, { mode: 'cors', cache: 'no-store', credentials: isCrossOrigin(src) ? 'omit' : 'same-origin', signal })
  if (!response.ok) throw new Error('Could not load photo (' + response.status + ')')
  const objectUrl = URL.createObjectURL(await response.blob())
  try {
    await new Promise((resolve, reject) => {
      const cleanup = () => {
        image.onload = image.onerror = null
        signal?.removeEventListener('abort', abort)
      }
      const abort = () => {
        cleanup()
        image.removeAttribute('src')
        reject(new DOMException('Photo loading cancelled', 'AbortError'))
      }
      image.onload = () => {
        cleanup()
        if (image.naturalWidth > 0 && image.naturalHeight > 0) resolve()
        else reject(new Error('Photo has no pixels'))
      }
      image.onerror = () => { cleanup(); reject(new Error('Could not open photo')) }
      if (signal?.aborted) { abort(); return }
      signal?.addEventListener('abort', abort, { once: true })
      image.src = objectUrl
    })
  } finally {
    URL.revokeObjectURL(objectUrl)
  }
  return image
}

