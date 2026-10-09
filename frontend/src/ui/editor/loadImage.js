import { isCrossOrigin } from '../media.js'

/*
 * Loads the original so drawing it doesn't taint the canvas. Cross-origin URLs (CloudFront / S3) are fetched with CORS
 * and no cache, because an earlier non-CORS <img> load of the same URL may sit in the HTTP cache without
 * Access-Control-Allow-Origin. Same-origin URLs use crossOrigin="anonymous" in case they redirect to the CDN.
 */
export async function loadEditableImage(src, signal) {
  const image = new Image()
  image.decoding = 'async'
  let objectUrl = null
  if (isCrossOrigin(src)) {
    const response = await fetch(src, { mode: 'cors', cache: 'no-store', credentials: 'omit', signal })
    if (!response.ok) throw new Error('Could not load photo')
    objectUrl = URL.createObjectURL(await response.blob())
    image.src = objectUrl
  } else {
    image.crossOrigin = 'anonymous'
    image.src = src
  }
  try { await image.decode() } finally { if (objectUrl) URL.revokeObjectURL(objectUrl) }
  return image
}

