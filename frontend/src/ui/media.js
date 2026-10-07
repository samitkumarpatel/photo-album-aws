/*
 * Photo URLs and status.
 *
 * Photos carry `urls: {thumbnail, display, original, download}` (relative `/api/...` or absolute CloudFront / S3 presigned URLs)
 * and `status: UPLOADING | PROCESSING | READY | FAILED`. Older backends send neither, so we fall back to the
 * streaming endpoint and treat a missing status as READY.
 */

export const isVideo = item => item.contentType?.startsWith('video/')
export const statusOf = item => item.status || 'READY'
export const isAbsoluteUrl = url => /^([a-z][a-z\d+.-]*:|\/\/)/i.test(url)

/** True when loading `url` into a canvas needs CORS (`crossOrigin="anonymous"`). */
export function isCrossOrigin(url) {
  if (!url || url.startsWith('blob:') || url.startsWith('data:')) return false
  try { return new URL(url, location.href).origin !== location.origin } catch { return false }
}

/** Cache-busts edited photos. Only for relative URLs: extra parameters would break signed URLs. */
export function withVersion(url, item) {
  if (!item.editedAt || isAbsoluteUrl(url)) return url
  return url + (url.includes('?') ? '&' : '?') + 'v=' + Date.parse(item.editedAt)
}

/**
 * The URL for one rendition: 'thumbnail' for grids and covers, 'display' for the viewer, 'original' for zoom,
 * download and editing. Derivatives only exist once the photo is READY, so earlier states use the original.
 */
export function mediaUrl(item, kind = 'original') {
  const urls = item.urls
  if (urls) {
    const url = (statusOf(item) === 'READY' && urls[kind]) || urls.original || urls.display || urls.thumbnail
    if (url) return withVersion(url, item)
  }
  if (item.url) return withVersion(item.url, item)
  return withVersion('/api/albums/' + item.albumId + '/photos/' + item.id, item)
}

/**
 * Saves the original under its filename. The server's download URL sets Content-Disposition where it can (API routes,
 * S3 presigned); CloudFront URLs can't, so they rely on the `download` attribute, which browsers honour only for
 * same-origin links. Cross-origin URLs without a disposition are fetched and saved from a blob instead.
 */
export async function downloadOriginal(item) {
  const url = item.urls?.download || mediaUrl(item, 'original')
  const disposed = item.urls?.download && item.urls.download !== item.urls.original
  const link = document.createElement('a')
  link.download = item.filename || ''
  if (disposed || !isCrossOrigin(url)) {
    link.href = url
    link.click()
    return
  }
  const response = await fetch(url, { mode: 'cors', cache: 'no-store' })
  if (!response.ok) throw new Error('Download failed (' + response.status + ')')
  const objectUrl = URL.createObjectURL(await response.blob())
  link.href = objectUrl
  link.click()
  setTimeout(() => URL.revokeObjectURL(objectUrl), 60_000)
}

/** CSS aspect ratio from the photo's stored size, so tiles keep their space while loading. */
export const aspectStyle = item => item.width > 0 && item.height > 0 ? { aspectRatio: item.width + ' / ' + item.height } : undefined

// An upload intent that never completed (closed tab, lost connection). The server should expire these itself.
const STALE_UPLOAD_MS = 30 * 60 * 1000
export const isStaleUpload = item => statusOf(item) === 'UPLOADING' && !item.local && Date.now() - Date.parse(item.uploadedAt || 0) > STALE_UPLOAD_MS

/** Photos the server is still working on, so the page should poll. Local uploads in progress don't count. */
export const needsPolling = item => statusOf(item) === 'PROCESSING' || (statusOf(item) === 'UPLOADING' && !item.local && !isStaleUpload(item))

/** Photos that can open in the viewer: ready ones, and processing ones whose original is available. */
export const isViewable = item => statusOf(item) === 'READY' || (statusOf(item) === 'PROCESSING' && (!item.urls || !!item.urls.original))

export const isBroken = item => statusOf(item) === 'FAILED' || isStaleUpload(item)

const rank = { UPLOADING: 0, PROCESSING: 1, READY: 2, FAILED: 2 }

/**
 * Merges photos uploaded in this session (`staged`: id -> {albumId, photo, preview, completed}) into the server's list,
 * so new tiles show at once with a local preview until the server has a thumbnail.
 */
export function withStaged(photos, staged, albumId) {
  const mine = new Map()
  staged?.forEach((entry, id) => { if (entry.albumId === albumId) mine.set(id, entry) })
  if (!mine.size) return photos
  const merged = photos.map(photo => {
    const entry = mine.get(photo.id)
    if (!entry) return photo
    mine.delete(photo.id)
    const best = rank[statusOf(entry.photo)] > rank[statusOf(photo)] ? { ...photo, ...entry.photo } : photo
    return statusOf(best) === 'READY' || statusOf(best) === 'FAILED' ? best : { ...best, preview: entry.preview }
  })
  const extra = [...mine.values()].map(entry => ({ ...entry.photo, preview: entry.preview, local: !entry.completed }))
  return [...extra, ...merged]
}
