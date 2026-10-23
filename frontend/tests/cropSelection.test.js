import test from 'node:test'
import assert from 'node:assert/strict'
import { drawnCrop } from '../src/ui/editor/cropSelection.js'

test('drawing in either direction selects the same rectangle', () => {
  const forward = drawnCrop(.2, .3, .8, .9)
  assert.deepEqual(drawnCrop(.8, .9, .2, .3), forward)
  assert.equal(forward.x, .2)
  assert.equal(forward.y, .3)
  assert.ok(Math.abs(forward.w - .6) < 1e-10)
  assert.ok(Math.abs(forward.h - .6) < 1e-10)
})

test('dragging beyond the photo stays inside its bounds', () => {
  assert.deepEqual(drawnCrop(-.5, -.2, 2, 3), { x: 0, y: 0, w: 1, h: 1 })
})

test('tiny selections at every edge retain a usable size within the image', () => {
  for (const x of [0, .5, 1]) for (const y of [0, .5, 1]) {
    const crop = drawnCrop(x, y, x, y)
    assert.equal(crop.w, .06)
    assert.equal(crop.h, .06)
    assert.ok(crop.x >= 0 && crop.y >= 0 && crop.x + crop.w <= 1 && crop.y + crop.h <= 1)
  }
})
