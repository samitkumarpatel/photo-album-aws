import { Zip, ZipPassThrough } from 'fflate'
import { isCrossOrigin, mediaUrl } from './media.js'
import { apiFetch } from './api.js'

export const MAX_ARCHIVE_BYTES = 512 * 1024 * 1024
export const archiveFilename = name => (name || 'album').replace(/[<>:"/\\|?*\x00-\x1f]/g, '_').replace(/[. ]+$/, '') || 'album'

export function uniqueFilename(filename, used) {
  const name = archiveFilename(filename)
  const dot = name.lastIndexOf('.'), base = dot > 0 ? name.slice(0, dot) : name, ext = dot > 0 ? name.slice(dot) : ''
  let next = name, number = 2
  while (used.has(next.toLocaleLowerCase())) next = base + ' (' + number++ + ')' + ext
  used.add(next.toLocaleLowerCase())
  return next
}

/** Read one original at a time; compressed images/videos are stored without redundant compression. */
export async function buildArchive(items, { signal, onProgress = () => {} } = {}) {
  if (!items.length) throw new Error('Choose at least one ready photo or video.')
  if (items.reduce((sum, item) => sum + (item.size || 0), 0) > MAX_ARCHIVE_BYTES)
    throw new Error('This ZIP would exceed 512 MB. Download a smaller selection.')
  const chunks = [], used = new Set()
  let bytes = 0, error = null, finished = false
  const zip = new Zip((failure, data, final) => {
    if (failure) { error = failure; return }
    // Store immutable Blob parts as they arrive, so finishing the archive does
    // not require a second contiguous allocation of all downloaded bytes.
    chunks.push(new Blob([data])); finished = final
  })
  try {
    for (let index = 0; index < items.length; index++) {
      signal?.throwIfAborted()
      const item = items[index], url = mediaUrl(item, 'original')
      onProgress({ done: index, total: items.length, filename: item.filename, bytes })
      const response = await apiFetch(url, { signal, mode: 'cors', cache: 'no-store', credentials: isCrossOrigin(url) ? 'omit' : 'same-origin' })
      if (!response.ok) throw new Error('Could not download ' + item.filename + ' (' + response.status + '). Refresh the album and retry.')
      const file = new ZipPassThrough(uniqueFilename(item.filename, used))
      zip.add(file)
      const reader = response.body.getReader()
      try {
        while (true) {
          const { done, value } = await reader.read()
          signal?.throwIfAborted()
          if (done) { file.push(new Uint8Array(0), true); break }
          bytes += value.byteLength
          if (bytes > MAX_ARCHIVE_BYTES) throw new Error('This ZIP exceeds 512 MB. Download a smaller selection.')
          file.push(value)
          if (error) throw error
        }
      } finally { await reader.cancel().catch(() => {}); reader.releaseLock() }
      onProgress({ done: index + 1, total: items.length, filename: item.filename, bytes })
    }
    signal?.throwIfAborted()
    zip.end()
    if (error) throw error
    if (!finished) throw new Error('The ZIP could not be completed. Please retry.')
    return new Blob(chunks, { type: 'application/zip' })
  } catch (failure) { zip.terminate(); throw failure }
}

export function saveArchive(blob, name) {
  const url = URL.createObjectURL(blob), link = document.createElement('a')
  link.href = url; link.download = archiveFilename(name) + '.zip'; link.click()
  setTimeout(() => URL.revokeObjectURL(url), 60_000)
}
