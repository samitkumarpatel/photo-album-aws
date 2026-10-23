import { useEffect, useRef, useState } from 'react'

export function paintSegment(mask, from, to, size, erase = false) {
  const ctx = mask.getContext('2d')
  ctx.save(); ctx.globalCompositeOperation = erase ? 'destination-out' : 'source-over'
  ctx.strokeStyle = '#fff'; ctx.fillStyle = '#fff'; ctx.lineCap = 'round'; ctx.lineJoin = 'round'
  ctx.lineWidth = size * Math.min(mask.width, mask.height)
  ctx.beginPath(); ctx.moveTo(from.x * mask.width, from.y * mask.height); ctx.lineTo(to.x * mask.width, to.y * mask.height); ctx.stroke()
  ctx.beginPath(); ctx.arc(to.x * mask.width, to.y * mask.height, ctx.lineWidth / 2, 0, Math.PI * 2); ctx.fill()
  ctx.restore()
}

function replay(entry) {
  const ctx = entry.mask.getContext('2d')
  ctx.clearRect(0, 0, entry.mask.width, entry.mask.height)
  if (entry.base) ctx.drawImage(entry.base, 0, 0, entry.mask.width, entry.mask.height)
  for (const stroke of entry.strokes) for (let i = 0; i < stroke.points.length; i++) paintSegment(entry.mask, stroke.points[Math.max(0, i - 1)], stroke.points[i], stroke.size, stroke.erase)
}

export function usePaintMask(photo, mode) {
  const entries = useRef(new Map()), current = useRef(null), drag = useRef(null)
  const pendingFrame = useRef(0)
  const [mask, setMask] = useState(null), [revision, setRevision] = useState(0)
  const [canUndo, setCanUndo] = useState(false), [hasPaint, setHasPaint] = useState(false)
  const changed = () => {
    const entry = current.current
    setRevision(n => n + 1); setCanUndo(!!entry?.strokes.length)
    if (entry) {
      const data = entry.mask.getContext('2d').getImageData(0, 0, entry.mask.width, entry.mask.height).data
      let painted = false
      for (let i = 3; i < data.length; i += 4) { if (data[i] > 0) { painted = true; break } }
      setHasPaint(painted)
    }
  }
  const invalidate = () => {
    if (pendingFrame.current) return
    pendingFrame.current = requestAnimationFrame(() => { pendingFrame.current = 0; setRevision(n => n + 1) })
  }
  useEffect(() => () => cancelAnimationFrame(pendingFrame.current), [])
  useEffect(() => { entries.current = new Map() }, [photo])
  useEffect(() => {
    drag.current = null
    if (!photo) return
    if (!entries.current.has(mode)) {
      const canvas = document.createElement('canvas'); canvas.width = photo.width; canvas.height = photo.height
      entries.current.set(mode, { mask: canvas, base: null, strokes: [] })
    }
    current.current = entries.current.get(mode); setMask(current.current.mask); changed()
  }, [photo, mode])

  const point = e => {
    const r = e.currentTarget.getBoundingClientRect()
    return { x: Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)), y: Math.max(0, Math.min(1, (e.clientY - r.top) / r.height)) }
  }
  function start(e, size, erase) {
    if (!current.current || drag.current || e.button !== 0) return
    e.preventDefault(); e.currentTarget.setPointerCapture(e.pointerId)
    const p = point(e), stroke = { points: [p], size, erase }
    drag.current = { id: e.pointerId, stroke, entry: current.current }
    paintSegment(current.current.mask, p, p, size, erase); invalidate()
  }
  function move(e) {
    const d = drag.current
    if (!d || d.id !== e.pointerId || d.stroke.points.length >= 2000) return
    const next = point(e), previous = d.stroke.points.at(-1)
    d.stroke.points.push(next); paintSegment(d.entry.mask, previous, next, d.stroke.size, d.stroke.erase); invalidate()
  }
  function end(e) {
    const d = drag.current
    if (!d || d.id !== e.pointerId) return
    if (e.type !== 'pointercancel') {
      d.entry.strokes.push(d.stroke)
      // Retain the latest 50 undoable strokes without retaining pixel snapshots.
      if (d.entry.strokes.length > 50) {
        if (!d.entry.base) { d.entry.base = document.createElement('canvas'); d.entry.base.width = mask.width; d.entry.base.height = mask.height }
        const oldest = d.entry.strokes.shift()
        for (let i = 0; i < oldest.points.length; i++) paintSegment(d.entry.base, oldest.points[Math.max(0, i - 1)], oldest.points[i], oldest.size, oldest.erase)
      }
    } else replay(d.entry)
    drag.current = null; changed()
  }
  function replace(base) {
    const entry = current.current
    if (!entry) return
    entry.base = base; entry.strokes = []; replay(entry); changed()
  }
  return { mask, revision, canUndo, hasPaint, start, move, end,
    replace, clear: () => replace(null),
    undo: () => { const entry = current.current; if (entry?.strokes.length) { entry.strokes.pop(); replay(entry); changed() } },
  }
}
