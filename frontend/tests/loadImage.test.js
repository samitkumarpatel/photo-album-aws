import assert from 'node:assert/strict'
import test from 'node:test'
import { loadEditableImage } from '../src/ui/editor/loadImage.js'

function setup(t, { fail = false, pending = false } = {}) {
  const images = [], revoked = [], requests = []
  const originalImage = globalThis.Image, originalLocation = globalThis.location
  globalThis.location = { href: 'https://album.example/albums/1', origin: 'https://album.example' }
  globalThis.Image = class {
    naturalWidth = 800
    naturalHeight = 600
    constructor() { images.push(this) }
    decode() { throw new Error('Safari decode rejection must not prevent editing') }
    set src(value) {
      this.url = value
      if (!pending) queueMicrotask(() => fail ? this.onerror?.() : this.onload?.())
    }
    removeAttribute(name) { if (name === 'src') this.url = null }
  }
  t.after(() => {
    if (originalImage === undefined) delete globalThis.Image
    else globalThis.Image = originalImage
    if (originalLocation === undefined) delete globalThis.location
    else globalThis.location = originalLocation
  })
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    requests.push({ url, options })
    return new Response(new Blob(['photo'], { type: 'image/jpeg' }))
  })
  t.mock.method(URL, 'createObjectURL', () => 'blob:editable-photo')
  t.mock.method(URL, 'revokeObjectURL', url => revoked.push(url))
  return { images, revoked, requests }
}

test('a loaded image remains editable when decode() would reject', async t => {
  const { images, revoked } = setup(t)
  const image = await loadEditableImage('https://media.example/original.jpg')
  assert.equal(image, images[0])
  assert.equal(image.naturalWidth, 800)
  assert.deepEqual(revoked, ['blob:editable-photo'])
  assert.equal(image.onload, null)
})

test('cross-origin signed URLs are fetched with CORS and kept intact', async t => {
  const { requests } = setup(t)
  const src = 'https://media.example/original.jpg?signature=abc'
  await loadEditableImage(src)
  assert.equal(requests[0].url, src)
  assert.deepEqual(requests[0].options, { mode: 'cors', cache: 'no-store', credentials: 'omit', signal: undefined })
})

test('same-origin media also bypasses a non-CORS cached redirect', async t => {
  const { requests, images } = setup(t)
  await loadEditableImage('/api/albums/1/photos/2')
  assert.equal(requests[0].options.cache, 'no-store')
  assert.equal(requests[0].options.mode, 'cors')
  assert.equal(requests[0].options.credentials, 'same-origin')
  assert.equal(images[0].url, 'blob:editable-photo')
})

test('invalid image data rejects and releases the object URL', async t => {
  const { revoked } = setup(t, { fail: true })
  await assert.rejects(loadEditableImage('/broken.jpg'), /Could not open photo/)
  assert.deepEqual(revoked, ['blob:editable-photo'])
})

test('closing the editor cancels an image that is still loading', async t => {
  const { revoked, images } = setup(t, { pending: true })
  const controller = new AbortController()
  const loading = loadEditableImage('/photo.jpg', controller.signal)
  // Wait until the fetch body has been read and the image handlers are attached.
  await new Promise(resolve => setImmediate(resolve))
  controller.abort()
  await assert.rejects(loading, { name: 'AbortError' })
  assert.equal(images[0].url, null)
  assert.equal(images[0].onload, null)
  assert.deepEqual(revoked, ['blob:editable-photo'])
})

test('HTTP failures reject before allocating an object URL', async t => {
  const { revoked } = setup(t)
  t.mock.method(globalThis, 'fetch', async () => new Response('', { status: 403 }))
  await assert.rejects(loadEditableImage('/expired.jpg'), /403/)
  assert.deepEqual(revoked, [])
})
