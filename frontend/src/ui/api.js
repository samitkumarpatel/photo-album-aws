/*
 * Shared fetch helpers.
 *
 * On AWS the API sits behind CloudFront with origin access control (OAC) to a Lambda function URL. OAC signs the
 * request for Lambda, and Lambda requires POST/PUT/PATCH/DELETE requests to carry `x-amz-content-sha256` with the
 * hex SHA-256 of the exact body bytes. We add it to same-origin `/api/` requests only, never to presigned S3 URLs.
 * Multipart bodies are built by hand (instead of letting the browser encode FormData) so we know the exact bytes.
 */

const EMPTY_SHA256 = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'
const SIGNED_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])
export const HASH_HEADER = 'x-amz-content-sha256'

/** True for URLs on this site under /api/ (relative or absolute). */
export function isApiUrl(url) {
  try { const u = new URL(url, location.href); return u.origin === location.origin && u.pathname.startsWith('/api/') } catch { return false }
}

/** Hex SHA-256 of a request body, or null when it can't be computed (no crypto.subtle outside secure contexts). */
export async function sha256Hex(body) {
  if (!globalThis.crypto?.subtle) return null
  if (body == null || body === '') return EMPTY_SHA256
  const bytes = typeof body === 'string' ? new TextEncoder().encode(body)
    : body instanceof Blob ? await body.arrayBuffer()
    : body instanceof ArrayBuffer || ArrayBuffer.isView(body) ? body
    : null
  if (!bytes) return null
  const digest = await crypto.subtle.digest('SHA-256', bytes)
  return Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, '0')).join('')
}

// Same escaping browsers use for multipart/form-data names and filenames.
const quote = value => String(value).replace(/"/g, '%22').replace(/\r/g, '%0D').replace(/\n/g, '%0A')

/** Encodes FormData (or [name, value] pairs) as a multipart Blob whose bytes we can hash. */
export function multipartBody(entries) {
  const boundary = '----StillroomBoundary' + crypto.randomUUID().replace(/-/g, '')
  const parts = []
  for (const [name, value] of entries) {
    if (value instanceof Blob) {
      const filename = value.name ?? 'blob'
      parts.push('--' + boundary + '\r\nContent-Disposition: form-data; name="' + quote(name) + '"; filename="' + quote(filename) + '"\r\nContent-Type: ' + (value.type || 'application/octet-stream') + '\r\n\r\n', value, '\r\n')
    } else {
      parts.push('--' + boundary + '\r\nContent-Disposition: form-data; name="' + quote(name) + '"\r\n\r\n' + String(value).replace(/\r?\n/g, '\r\n') + '\r\n')
    }
  }
  parts.push('--' + boundary + '--\r\n')
  return { body: new Blob(parts), contentType: 'multipart/form-data; boundary=' + boundary }
}

/** Returns fetch options with FormData re-encoded and the OAC body hash added for same-origin API writes. */
export async function prepareApiRequest(url, options = {}) {
  const method = (options.method || 'GET').toUpperCase()
  if (!SIGNED_METHODS.has(method) || !isApiUrl(url)) return options
  const headers = new Headers(options.headers)
  let body = options.body
  if (body instanceof FormData) {
    const encoded = multipartBody(body.entries())
    body = encoded.body
    headers.set('Content-Type', encoded.contentType)
  }
  if (!headers.has(HASH_HEADER)) {
    const hash = await sha256Hex(body)
    if (hash) headers.set(HASH_HEADER, hash)
  }
  return { ...options, method, headers, body }
}

export async function apiFetch(url, options = {}) {
  return fetch(url, await prepareApiRequest(url, options))
}

export function errorMessage(status, body = {}, fallback = 'Something went wrong. Please try again.') {
  return body.detail || body.message || (status === 410 ? 'This share link has expired.' : status === 413 ? 'The file is larger than 100 MB.' : status === 415 ? 'This file type isn’t supported.' : fallback)
}

export async function api(path, options = {}) {
  const response = await apiFetch(path, options)
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    const error = new Error(errorMessage(response.status, body))
    error.status = response.status
    throw error
  }
  return response.status === 204 ? null : response.json()
}
