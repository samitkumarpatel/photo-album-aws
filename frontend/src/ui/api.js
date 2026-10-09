/*
 * Shared fetch helpers.
 *
 * VITE_API_BASE_URL is set by the frontend deployment workflow. Leaving it empty keeps local development on the
 * Vite `/api` proxy.
 */

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/+$/, '')
const EMPTY_SHA256 = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'
export const HASH_HEADER = 'x-amz-content-sha256'

/** True for local API URLs that still need the signed request body hash. */
export function isApiUrl(url) {
  if (API_BASE_URL) return false
  try {
    const parsed = new URL(url, location.href)
    return parsed.origin === location.origin && parsed.pathname.startsWith('/api/')
  } catch {
    return false
  }
}

/** Hex SHA-256 for local upload requests. */
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

const quote = value => String(value).replace(/"/g, '%22').replace(/\r/g, '%0D').replace(/\n/g, '%0A')

/** Encodes a file as multipart data with a known content type and exact body bytes. */
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

export async function apiFetch(url, options = {}) {
  const apiUrl = API_BASE_URL && url.startsWith('/api/') ? `${API_BASE_URL}${url}` : url
  return fetch(apiUrl, options)
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
