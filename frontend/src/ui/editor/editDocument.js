import { api } from '../api.js'
import { mediaUrl } from '../media.js'
import { loadEditableImage } from './loadImage.js'
import { normalizeEdit } from './imageOps.js'

export async function loadEditDocument(item, signal) {
  let document
  try { document = await api('/api/albums/' + item.albumId + '/photos/' + item.id + '/edit', { signal }) }
  catch (error) { if (error.status !== 404) throw error }
  const image = await loadEditableImage(document?.url || mediaUrl(item, 'original'), signal)
  const edit = normalizeEdit(document?.recipe || {})
  const maskData = document?.recipe?.background?.maskData
  const mask = maskData ? await loadEditableImage(maskData, signal) : null
  const backgroundImage = edit.background.asset ? await loadEditableImage(edit.background.asset, signal) : null
  return { image, edit, mask, backgroundImage, baseVersion: document?.baseVersion || item.version || 1 }
}
