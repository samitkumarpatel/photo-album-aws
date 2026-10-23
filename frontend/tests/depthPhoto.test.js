import assert from 'node:assert/strict'
import test from 'node:test'
import vm from 'node:vm'
import { createDepthRenderer, depthDownloadHtml } from '../src/ui/editor/depthRenderer.js'

test('3D downloads reject script injection and external image URLs', () => {
  for (const input of ['https://example.com/photo.jpg', 'data:image/jpeg;base64,</script><script>alert(1)</script>']) {
    assert.throws(() => depthDownloadHtml(input, 'data:image/png;base64,AAAA', .6), /Invalid/)
  }
})

test('offline download script parses and contains its renderer without module dependencies', () => {
  const html = depthDownloadHtml('data:image/jpeg;base64,AAAA', 'data:image/png;base64,BBBB', 99)
  const script = html.match(/<script>([\s\S]*)<\/script>/)[1]
  assert.doesNotThrow(() => new vm.Script(script))
  assert.match(script, /renderer.draw\(x,y,1\)/)
  assert.doesNotMatch(script, /\bimport\b|\bfetch\(/)
})

test('unsupported graphics produces an actionable error', () => {
  assert.throws(() => createDepthRenderer({ getContext: () => null }, {}, {}), /needs WebGL/)
})
