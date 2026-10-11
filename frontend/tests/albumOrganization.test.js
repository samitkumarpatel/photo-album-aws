import test from 'node:test'
import assert from 'node:assert/strict'
import { unzipSync, strFromU8 } from 'fflate'
import { sortMedia, moveItem } from '../src/ui/albumOrdering.js'
import { buildArchive, uniqueFilename, MAX_ARCHIVE_BYTES } from '../src/ui/downloadArchive.js'

const items = [
  { id: 'a', filename: 'photo10.jpg', uploadedAt: '2026-10-11', takenAt: '2026-01-01' },
  { id: 'b', filename: 'photo2.jpg', uploadedAt: '2026-10-10', takenAt: '2026-10-12' },
  { id: 'c', filename: 'video.mp4', uploadedAt: '2026-10-09' },
]
test('existing upload sorting, capture dates and numeric filenames remain distinct', () => {
  assert.deepEqual(sortMedia(items).map(p => p.id), ['a', 'b', 'c'])
  assert.deepEqual(sortMedia(items, 'oldest').map(p => p.id), ['c', 'b', 'a'])
  assert.deepEqual(sortMedia(items, 'captured').map(p => p.id), ['b', 'c', 'a'])
  assert.deepEqual(sortMedia(items, 'filename').map(p => p.id), ['b', 'a', 'c'])
})
test('manual order never hides new items and handles deleted and duplicate IDs', () => {
  assert.deepEqual(sortMedia(items, 'custom', ['b', 'deleted', 'a', 'a']).map(p => p.id), ['b', 'a', 'c'])
  assert.deepEqual(moveItem(items, 0, 2).map(p => p.id), ['b', 'c', 'a'])
  assert.equal(moveItem(items, -1, 2), items)
  assert.deepEqual(items.map(p => p.id), ['a', 'b', 'c'])
})
test('archive filenames are safe, Unicode-capable and collision-free', () => {
  const used = new Set()
  assert.equal(uniqueFilename('IMG.jpg', used), 'IMG.jpg')
  assert.equal(uniqueFilename('img.jpg', used), 'img (2).jpg')
  assert.equal(uniqueFilename('img (2).jpg', used), 'img (2) (2).jpg')
  assert.equal(uniqueFilename('../旅行.jpg', used), '.._旅行.jpg')
})
function mediaSetup(t, fetcher) {
  const previous = globalThis.location
  globalThis.location = { origin: 'https://album.example', href: 'https://album.example/' }
  t.after(() => { if (previous === undefined) delete globalThis.location; else globalThis.location = previous })
  t.mock.method(globalThis, 'fetch', fetcher)
}
test('ZIP retains original bytes for duplicate filenames and mixed media', async t => {
  const progress = []
  mediaSetup(t, async url => new Response(url.endsWith('1') ? 'original JPEG' : 'original video'))
  const blob = await buildArchive([{ filename: 'same.jpg', url: '/photo/1', size: 13 }, { filename: 'same.jpg', url: '/photo/2', size: 14 }], { onProgress: p => progress.push(p.done) })
  const files = unzipSync(new Uint8Array(await blob.arrayBuffer()))
  assert.equal(strFromU8(files['same.jpg']), 'original JPEG')
  assert.equal(strFromU8(files['same (2).jpg']), 'original video')
  assert.equal(progress.at(-1), 2)
  assert.equal(blob.type, 'application/zip')
})
test('HTTP failure or cancellation rejects instead of saving an incomplete archive', async t => {
  mediaSetup(t, async () => new Response('failed', { status: 403 }))
  await assert.rejects(buildArchive([{ filename: 'photo.jpg', url: '/photo/1' }]), /403/)
  const controller = new AbortController(); controller.abort()
  await assert.rejects(buildArchive([{ filename: 'photo.jpg', url: '/photo/1' }], { signal: controller.signal }), { name: 'AbortError' })
})
test('oversized selections are rejected before fetching any originals', async t => {
  let fetched = false
  mediaSetup(t, async () => { fetched = true; return new Response('photo') })
  await assert.rejects(buildArchive([{ filename: 'big.mp4', url: '/photo/1', size: MAX_ARCHIVE_BYTES + 1 }]), /512 MB/)
  assert.equal(fetched, false)
})
