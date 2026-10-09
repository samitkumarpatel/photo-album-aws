import { api } from '../api.js'
import { putFile } from '../upload/uploads.js'

/** Save rendered pixels and the recipe together; a copy starts with its own independent source. */
export async function saveEditedPhoto(item, file, recipe, mode = 'replace', signal) {
  const json = body => ({ method: 'POST', signal, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
  let target = item
  if (mode === 'copy') {
    target = await api('/api/albums/' + item.albumId + '/photos/' + item.id + '/copy-source', json({ version: recipe.baseVersion }))
    target = { ...target, albumId: item.albumId }
    recipe = { ...recipe, baseVersion: 1 }
  }
  try {
    const path = '/api/albums/' + item.albumId + '/photos/' + target.id + '/replacement'
    const request = { filename: file.name, contentType: file.type, size: file.size, editRecipe: recipe }
    const intent = await api(path, json(request))
    await putFile(intent, file, undefined, signal)
    return await api(path + '/' + intent.uploadId + '/' + intent.version + '/complete', json(request))
  } catch (error) {
    // Remove an unfinished copy; replacements keep the previous version when completion fails.
    if (mode === 'copy') await api('/api/albums/' + item.albumId + '/photos/' + target.id, { method: 'DELETE' }).catch(() => {})
    throw error
  }
}
