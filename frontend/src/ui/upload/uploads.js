/*
 * Upload flow (docs/aws-architecture-plan.md §2.4):
 *   1. POST /api/albums/{albumId}/uploads {filename, contentType, size} -> {photoId, uploadUrl, method, headers, expiresAt}
 *   2. PUT the file to uploadUrl with exactly the returned headers (XHR, for progress)
 *   3. POST /api/albums/{albumId}/uploads/{photoId}/complete -> photo (PROCESSING or READY)
 * Backends without step 1 (404/405/501) get the older multipart POST /api/albums/{albumId}/photos instead.
 */
import { HASH_HEADER, api, errorMessage, isApiUrl, multipartBody, sha256Hex } from '../api.js'

const LEGACY_STATUSES = new Set([404, 405, 501])
// Remembered for the session once the backend turns out not to support presigned uploads.
let legacyOnly = false

function abortError() { const e = new Error('Upload stopped'); e.name = 'AbortError'; return e }

/** XMLHttpRequest wrapper with upload progress (0–1) and AbortSignal support. Resolves with {status, text}. */
function send({ method, url, headers = {}, body, onProgress, signal }) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) { reject(abortError()); return }
    const request = new XMLHttpRequest()
    const abort = () => request.abort()
    const done = () => signal?.removeEventListener('abort', abort)
    signal?.addEventListener('abort', abort)
    request.open(method, url)
    for (const [name, value] of Object.entries(headers)) request.setRequestHeader(name, value)
    if (onProgress) request.upload.onprogress = event => { if (event.lengthComputable) onProgress(event.loaded / event.total) }
    request.onload = () => { done(); resolve({ status: request.status, text: request.responseText }) }
    request.onerror = () => { done(); reject(new Error('Connection lost. Check your connection and retry.')) }
    request.onabort = () => { done(); reject(abortError()) }
    request.send(body)
  })
}

function failure(status, text, fallback) {
  let body = {}
  try { body = JSON.parse(text) } catch {}
  const error = new Error(errorMessage(status, body, fallback))
  error.status = status
  return error
}

/** Step 1. Returns null when the backend has no presigned upload endpoint. */
export async function createUpload(albumId, file, signal) {
  if (legacyOnly) return null
  try {
    return await api('/api/albums/' + albumId + '/uploads', {
      method: 'POST', signal, headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ filename: file.name, contentType: file.type, size: file.size }),
    })
  } catch (e) {
    if (LEGACY_STATUSES.has(e.status)) { legacyOnly = true; return null }
    throw e
  }
}

// Headers the browser sets itself and refuses to accept from scripts.
const FORBIDDEN = new Set(['content-length', 'host', 'connection', 'expect'])

/** Step 2: sends the bytes to the presigned URL with exactly the headers the API returned. */
export async function putFile(intent, file, onProgress, signal) {
  const headers = {}
  for (const [name, value] of Object.entries(intent.headers || {})) if (!FORBIDDEN.has(name.toLowerCase())) headers[name] = value
  // A relative upload URL is our own API (local backend or behind CloudFront OAC), which needs the body hash.
  // Presigned S3 URLs get nothing beyond what was signed.
  if (isApiUrl(intent.uploadUrl) && !Object.keys(headers).some(name => name.toLowerCase() === HASH_HEADER)) {
    const hash = await sha256Hex(file)
    if (hash) headers[HASH_HEADER] = hash
  }
  const { status, text } = await send({ method: intent.method || 'PUT', url: intent.uploadUrl, headers, body: file, onProgress, signal })
  if (status < 200 || status >= 300) {
    const s3 = /<Code>([^<]+)<\/Code>/.exec(text)?.[1]
    throw failure(status, isApiUrl(intent.uploadUrl) ? text : '',
      s3 === 'AccessDenied' || s3 === 'SignatureDoesNotMatch' || status === 403 ? 'The upload link expired or was rejected. Retry to get a new one.' : 'Upload failed. Try again.')
  }
}

/** Step 3. */
export function completeUpload(albumId, photoId, signal) {
  return api('/api/albums/' + albumId + '/uploads/' + photoId + '/complete', { method: 'POST', signal })
}

/** Older backends: multipart POST through the API. The body is encoded by hand so its hash matches the bytes sent. */
export async function multipartUpload(albumId, file, onProgress, signal) {
  const url = '/api/albums/' + albumId + '/photos'
  const { body, contentType } = multipartBody([['file', file]])
  const headers = { 'Content-Type': contentType }
  const hash = await sha256Hex(body)
  if (hash) headers[HASH_HEADER] = hash
  const { status, text } = await send({ method: 'POST', url, headers, body, onProgress, signal })
  if (status < 200 || status >= 300) throw failure(status, text, 'Upload failed. Try again.')
  return JSON.parse(text)
}

/** Best effort: removes an upload intent or photo the user gave up on. */
export function discardUpload(albumId, photoId) {
  return api('/api/albums/' + albumId + '/photos/' + photoId, { method: 'DELETE' }).catch(() => {})
}

/** Whole flow for one file without UI (used by the editor's "Save as copy"). */
export async function uploadToAlbum(albumId, file, signal) {
  const intent = await createUpload(albumId, file, signal)
  if (!intent) return multipartUpload(albumId, file, undefined, signal)
  try {
    await putFile(intent, file, undefined, signal)
    return await completeUpload(albumId, intent.photoId, signal)
  } catch (e) { discardUpload(albumId, intent.photoId); throw e }
}

export const intentExpired = intent => intent?.expiresAt && Date.parse(intent.expiresAt) - Date.now() < 30000
