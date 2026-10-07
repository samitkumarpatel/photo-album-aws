import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Aperture, Check, CircleDot, CloudFog, Contrast, Copy, Crop, Droplet, Droplets, Eye, FlipHorizontal2, FlipVertical2,
  Moon, Pipette, Redo2, RotateCcw, Save, SlidersHorizontal, Sparkles, Sun, Sunrise, Thermometer, Triangle,
  Undo2, WandSparkles, X, Blend, Focus, Waves, Film,
} from 'lucide-react'
import { ADJUSTMENTS, ASPECTS, DEFAULT_EDIT, FILTERS, analyze, combineParams, exportBlob, orientedSize, outputFormat, render, sameEdit, scaledCopy } from './imageOps.js'
import CameraSpinner from '../CameraSpinner.jsx'
import { apiFetch, errorMessage } from '../api.js'
import { isCrossOrigin } from '../media.js'
import { uploadToAlbum } from '../upload/uploads.js'
import './editor.css'

const ICONS = {
  exposure: Aperture, brightness: Sun, contrast: Contrast, highlights: Sunrise, shadows: Moon, saturation: Droplet,
  vibrance: Droplets, warmth: Thermometer, tint: Pipette, fade: CloudFog, sharpen: Triangle, vignette: CircleDot,
  clarity: Focus, denoise: Waves, grain: Film,
}
const TABS = [['auto', 'Auto', WandSparkles], ['adjust', 'Adjust', SlidersHorizontal], ['filters', 'Filters', Blend], ['crop', 'Crop', Crop]]
const PREVIEW_SIDE = 1600
const clamp = (v, min, max) => Math.min(max, Math.max(min, v))

/*
 * Loads the original so drawing it doesn't taint the canvas. Cross-origin URLs (CloudFront / S3) are fetched with CORS
 * and no cache, because an earlier non-CORS <img> load of the same URL may sit in the HTTP cache without
 * Access-Control-Allow-Origin. Same-origin URLs use crossOrigin="anonymous" in case they redirect to the CDN.
 */
async function loadEditableImage(src, signal) {
  const image = new Image()
  image.decoding = 'async'
  let objectUrl = null
  if (isCrossOrigin(src)) {
    const response = await fetch(src, { mode: 'cors', cache: 'no-store', credentials: 'omit', signal })
    if (!response.ok) throw new Error('Could not load photo')
    objectUrl = URL.createObjectURL(await response.blob())
    image.src = objectUrl
  } else {
    image.crossOrigin = 'anonymous'
    image.src = src
  }
  try { await image.decode() } finally { if (objectUrl) URL.revokeObjectURL(objectUrl) }
  return image
}

/* ---------- crop math (all values are fractions of the frame) ---------- */

function fitAspect(ratio) {
  if (!ratio) return { x: 0, y: 0, w: 1, h: 1 }
  let w = 1, h = 1 / ratio
  if (h > 1) { h = 1; w = ratio }
  return { x: (1 - w) / 2, y: (1 - h) / 2, w, h }
}

function resizeCrop(c0, handle, dx, dy, ratio) {
  const MIN = 0.06
  if (handle === 'move') return { ...c0, x: clamp(c0.x + dx, 0, 1 - c0.w), y: clamp(c0.y + dy, 0, 1 - c0.h) }
  let l = c0.x, t = c0.y, r = c0.x + c0.w, b = c0.y + c0.h
  if (handle.includes('w')) l = clamp(l + dx, 0, r - MIN)
  if (handle.includes('e')) r = clamp(r + dx, l + MIN, 1)
  if (handle.includes('n')) t = clamp(t + dy, 0, b - MIN)
  if (handle.includes('s')) b = clamp(b + dy, t + MIN, 1)
  if (!ratio) return { x: l, y: t, w: r - l, h: b - t }
  const ax = handle.includes('w') ? r : handle.includes('e') ? l : c0.x + c0.w / 2
  const ay = handle.includes('n') ? b : handle.includes('s') ? t : c0.y + c0.h / 2
  let w = r - l, h = b - t
  if (handle === 'n' || handle === 's') w = h * ratio
  else if (handle === 'e' || handle === 'w') h = w / ratio
  else if (w / h > ratio) h = w / ratio
  else w = h * ratio
  const maxW = handle.includes('w') ? ax : handle.includes('e') ? 1 - ax : 2 * Math.min(ax, 1 - ax)
  const maxH = handle.includes('n') ? ay : handle.includes('s') ? 1 - ay : 2 * Math.min(ay, 1 - ay)
  if (w > maxW) { w = maxW; h = w / ratio }
  if (h > maxH) { h = maxH; w = h * ratio }
  const x = handle.includes('w') ? ax - w : handle.includes('e') ? ax : ax - w / 2
  const y = handle.includes('n') ? ay - h : handle.includes('s') ? ay : ay - h / 2
  return { x, y, w, h }
}

/* ---------- small UI pieces ---------- */

function Slider({ label, value, min, max, onChange, format = v => (v > 0 ? '+' : '') + v, onReset }) {
  const zero = min < 0 ? (0 - min) / (max - min) * 100 : 0
  const pos = (value - min) / (max - min) * 100
  const from = Math.min(zero, pos), to = Math.max(zero, pos)
  return <div className="ed-slider">
    <div className="ed-slider-head">
      <span>{label}</span>
      <output>{format(value)}</output>
      {onReset && <button className="ed-text-button" onClick={onReset} disabled={value === 0}>Reset</button>}
    </div>
    <input type="range" min={min} max={max} value={value} aria-label={label} onChange={e => onChange(Number(e.target.value))}
      onDoubleClick={onReset}
      style={{ '--from': from + '%', '--to': to + '%', '--zero': zero + '%' }} className={min < 0 ? 'bipolar' : ''} />
  </div>
}

/* ---------- editor ---------- */

export default function PhotoEditor({ item, src, onClose, onSaved }) {
  const dialog = useRef(null)
  const stage = useRef(null)
  const canvas = useRef(null)
  const full = useRef(null)      // full-resolution HTMLImageElement
  const preview = useRef(null)   // downscaled canvas for fast previews
  const drag = useRef(null)
  const [status, setStatus] = useState('loading')
  const [error, setError] = useState('')
  const [edit, setEdit] = useState(DEFAULT_EDIT)
  const [history, setHistory] = useState({ stack: [DEFAULT_EDIT], index: 0 })
  const [tab, setTab] = useState('adjust')
  const [active, setActive] = useState('brightness')
  const [autoParams, setAutoParams] = useState(null)
  const [thumbs, setThumbs] = useState({})
  const [comparing, setComparing] = useState(false)
  const [stageSize, setStageSize] = useState({ w: 0, h: 0 })
  const [display, setDisplay] = useState({ w: 0, h: 0 })
  const [saveMenu, setSaveMenu] = useState(false)
  const [saving, setSaving] = useState(null)
  const [confirmDiscard, setConfirmDiscard] = useState(false)

  const params = useMemo(() => combineParams(edit, autoParams), [edit, autoParams])
  const dirty = !sameEdit(edit, DEFAULT_EDIT)
  const cropping = tab === 'crop'

  /* open as a modal and load the image */
  useEffect(() => {
    const previous = document.activeElement
    const node = dialog.current
    const overflow = document.documentElement.style.overflow
    document.documentElement.style.overflow = 'hidden'
    node.showModal()
    node.focus()
    return () => { node.close(); document.documentElement.style.overflow = overflow; if (previous?.isConnected) previous.focus() }
  }, [])

  useEffect(() => {
    let cancelled = false
    const controller = new AbortController()
    loadEditableImage(src, controller.signal).then(image => {
      if (cancelled) return
      full.current = image
      preview.current = scaledCopy(image, PREVIEW_SIDE)
      setAutoParams(analyze(image))
      const tiny = scaledCopy(image, 160)
      const next = {}
      for (const filter of FILTERS) {
        const c = render(document.createElement('canvas'), tiny, tiny.width, tiny.height, DEFAULT_EDIT, combineParams({ ...DEFAULT_EDIT, filter: { id: filter.id, strength: 100 } }, null))
        next[filter.id] = c.toDataURL('image/jpeg', 0.8)
      }
      setThumbs(next)
      setStatus('ready')
    }).catch(() => { if (!cancelled) { setStatus('error'); setError('This photo couldn’t be opened for editing. Check your connection, or the format may not be supported in the browser.') } })
    return () => { cancelled = true; controller.abort() }
  }, [src])

  /* keep track of the space available for the preview */
  useEffect(() => {
    const node = stage.current
    if (!node) return
    const observer = new ResizeObserver(([entry]) => setStageSize({ w: entry.contentRect.width, h: entry.contentRect.height }))
    observer.observe(node)
    return () => observer.disconnect()
  }, [])

  /* render the preview (throttled to animation frames) */
  useEffect(() => {
    if (status !== 'ready' || !canvas.current) return
    const frame = requestAnimationFrame(() => {
      const p = preview.current
      if (comparing) render(canvas.current, p, p.width, p.height, DEFAULT_EDIT, null, 'final')
      else render(canvas.current, p, p.width, p.height, edit, params, cropping ? 'full' : 'final')
      const cw = canvas.current.width, ch = canvas.current.height
      const scale = Math.min(stageSize.w / cw, stageSize.h / ch, 1.5)
      const next = { w: Math.floor(cw * scale), h: Math.floor(ch * scale) }
      setDisplay(prev => prev.w === next.w && prev.h === next.h ? prev : next)
    })
    return () => cancelAnimationFrame(frame)
  }, [status, edit, params, cropping, comparing, stageSize])

  /* history: commit a snapshot shortly after changes settle */
  useEffect(() => {
    const timer = setTimeout(() => setHistory(h => {
      if (sameEdit(h.stack[h.index], edit)) return h
      const stack = [...h.stack.slice(0, h.index + 1), edit].slice(-60)
      return { stack, index: stack.length - 1 }
    }), 350)
    return () => clearTimeout(timer)
  }, [edit])
  const canUndo = history.index > 0 || !sameEdit(history.stack[history.index], edit)
  const canRedo = history.index < history.stack.length - 1
  const undo = useCallback(() => {
    setHistory(h => {
      const pending = !sameEdit(h.stack[h.index], edit)
      const index = pending ? h.index : Math.max(0, h.index - 1)
      setEdit(h.stack[index])
      return { ...h, index }
    })
  }, [edit])
  const redo = useCallback(() => {
    setHistory(h => { if (h.index >= h.stack.length - 1) return h; setEdit(h.stack[h.index + 1]); return { ...h, index: h.index + 1 } })
  }, [])

  /* helpers that change the edit */
  const update = patch => setEdit(e => ({ ...e, ...(typeof patch === 'function' ? patch(e) : patch) }))
  const setAdjust = (key, value) => update(e => ({ adjust: { ...e.adjust, [key]: value } }))
  const frame = preview.current ? orientedSize(preview.current.width, preview.current.height, edit.quarter) : { w: 1, h: 1 }
  const ratioFor = (aspectId, f = frame) => {
    const aspect = ASPECTS.find(a => a.id === aspectId)
    if (!aspect?.ratio) return null
    return aspect.ratio === 'original' ? 1 : aspect.ratio * f.h / f.w
  }
  const chooseAspect = id => update({ aspect: id, crop: fitAspect(ratioFor(id)) })
  const rotateLeft = () => update(e => {
    const quarter = (e.quarter + (e.flipX !== e.flipY ? 1 : 3)) % 4
    const f = orientedSize(preview.current.width, preview.current.height, quarter)
    return { quarter, crop: fitAspect(ratioFor(e.aspect, f)) }
  })
  const flipX = () => update(e => ({ flipX: !e.flipX, crop: { ...e.crop, x: 1 - e.crop.x - e.crop.w } }))
  const flipY = () => update(e => ({ flipY: !e.flipY, crop: { ...e.crop, y: 1 - e.crop.y - e.crop.h } }))
  const resetCrop = () => update({ crop: DEFAULT_EDIT.crop, aspect: 'free', quarter: 0, flipX: false, flipY: false, angle: 0 })
  const geometryChanged = !sameEdit({ c: edit.crop, q: edit.quarter, x: edit.flipX, y: edit.flipY, a: edit.angle }, { c: DEFAULT_EDIT.crop, q: 0, x: false, y: false, a: 0 })

  /* crop dragging */
  function startDrag(e, handle) {
    e.preventDefault(); e.stopPropagation()
    e.currentTarget.setPointerCapture?.(e.pointerId)
    drag.current = { handle, x: e.clientX, y: e.clientY, crop: edit.crop, id: e.pointerId }
  }
  function moveDrag(e) {
    const d = drag.current
    if (!d || d.id !== e.pointerId || !display.w) return
    const crop = resizeCrop(d.crop, d.handle, (e.clientX - d.x) / display.w, (e.clientY - d.y) / display.h, ratioFor(edit.aspect))
    update({ crop })
  }
  const endDrag = () => { drag.current = null }

  /* closing & saving */
  const requestClose = () => { if (saving) return; if (dirty) setConfirmDiscard(true); else onClose() }
  async function save(mode) {
    setSaveMenu(false); setSaving(mode); setError('')
    try {
      const { type, name, copyName } = outputFormat(item.contentType, item.filename)
      const blob = await exportBlob(full.current, edit, params, type)
      const file = new File([blob], mode === 'copy' ? copyName : name, { type })
      // A copy is a normal upload (presigned PUT when available). Replacing still goes through the API as multipart;
      // apiFetch encodes it by hand so the CloudFront OAC body hash covers the exact bytes.
      if (mode === 'copy') { onSaved(await uploadToAlbum(item.albumId, file), mode); return }
      const data = new FormData()
      data.append('file', file)
      const response = await apiFetch('/api/albums/' + item.albumId + '/photos/' + item.id, { method: 'PUT', body: data })
      if (!response.ok) {
        const body = await response.json().catch(() => ({}))
        throw new Error(response.status === 413 ? 'The edited photo is too large to upload.' : errorMessage(response.status, body, 'Saving failed. Please try again.'))
      }
      onSaved(await response.json(), mode)
    } catch (e) { setError(e.message); setSaving(null) }
  }

  function onKeyDown(e) {
    e.stopPropagation()
    const mod = e.metaKey || e.ctrlKey
    if (mod && e.key.toLowerCase() === 'z') { e.preventDefault(); e.shiftKey ? redo() : undo() }
    else if (mod && e.key.toLowerCase() === 'y') { e.preventDefault(); redo() }
  }

  const activeDef = ADJUSTMENTS.find(a => a.key === active)
  const filterActive = edit.filter.id !== 'none'

  return <dialog ref={dialog} className="editor" aria-label={'Edit ' + item.filename} tabIndex={-1}
    onKeyDown={onKeyDown} onCancel={e => { e.preventDefault(); if (saveMenu) setSaveMenu(false); else if (confirmDiscard) setConfirmDiscard(false); else requestClose() }}>
    <header className="ed-top">
      <button className="ed-text-button" onClick={requestClose} disabled={!!saving}>Cancel</button>
      <div className="ed-history">
        <button className="ed-icon" aria-label="Undo" title="Undo (Ctrl+Z)" onClick={undo} disabled={!canUndo || !!saving}><Undo2 size={20} /></button>
        <button className="ed-icon" aria-label="Redo" title="Redo (Ctrl+Shift+Z)" onClick={redo} disabled={!canRedo || !!saving}><Redo2 size={20} /></button>
        <button className={'ed-icon' + (comparing ? ' on' : '')} aria-label="Hold to compare with original" title="Hold to compare" disabled={!dirty || status !== 'ready'}
          onPointerDown={() => setComparing(true)} onPointerUp={() => setComparing(false)} onPointerLeave={() => setComparing(false)} onPointerCancel={() => setComparing(false)}
          onKeyDown={e => { if (e.key === ' ' || e.key === 'Enter') { e.preventDefault(); setComparing(true) } }} onKeyUp={() => setComparing(false)}
          onContextMenu={e => e.preventDefault()}><Eye size={20} /></button>
      </div>
      <div className="ed-save">
        <button className="ed-primary" onClick={() => setSaveMenu(v => !v)} disabled={!dirty || !!saving || status !== 'ready'} aria-haspopup="menu" aria-expanded={saveMenu}>
          {saving ? <CameraSpinner size={19} inherit decorative /> : <Check size={17} />}{saving ? 'Saving…' : 'Save'}
        </button>
        {saveMenu && <div className="ed-menu" role="menu">
          <button role="menuitem" onClick={() => save('copy')} autoFocus><Copy size={18} /><span><strong>Save as copy</strong><small>Keeps the original untouched</small></span></button>
          <button role="menuitem" onClick={() => save('replace')}><Save size={18} /><span><strong>Replace original</strong><small>Overwrites this photo</small></span></button>
        </div>}
      </div>
    </header>

    <div className="ed-stage" ref={stage} onPointerDown={() => saveMenu && setSaveMenu(false)}>
      {status === 'loading' && <div className="ed-status" role="status"><CameraSpinner size={64} inherit decorative /><span>Opening photo…</span></div>}
      {status === 'error' && <div className="ed-status"><span>{error}</span><button className="ed-primary" onClick={onClose}>Close</button></div>}
      <div className="ed-canvas-wrap" style={{ width: display.w, height: display.h, visibility: status === 'ready' ? 'visible' : 'hidden' }}>
        <canvas ref={canvas} aria-label={comparing ? 'Original photo' : 'Edited photo preview'} role="img" />
        {comparing && <span className="ed-badge">Original</span>}
        {cropping && !comparing && <div className="ed-crop-layer" onPointerMove={moveDrag} onPointerUp={endDrag} onPointerCancel={endDrag}>
          <div className="ed-crop" style={{ left: edit.crop.x * 100 + '%', top: edit.crop.y * 100 + '%', width: edit.crop.w * 100 + '%', height: edit.crop.h * 100 + '%' }}
            onPointerDown={e => startDrag(e, 'move')}>
            <span className="ed-grid" />
            {['nw', 'ne', 'sw', 'se', 'n', 's', 'e', 'w'].map(h => <span key={h} className={'ed-handle ' + h} onPointerDown={e => startDrag(e, h)} />)}
          </div>
        </div>}
      </div>
      {error && status === 'ready' && <div className="ed-error" role="alert">{error}</div>}
    </div>

    <section className="ed-panel" aria-label="Editing tools">
      <div className="ed-tool">
        {tab === 'auto' && <div className="ed-auto">
          <button className={'ed-auto-toggle' + (edit.auto.on ? ' on' : '')} aria-pressed={edit.auto.on} onClick={() => update(e => ({ auto: { ...e.auto, on: !e.auto.on } }))} disabled={!autoParams}>
            <Sparkles size={20} /><span><strong>Auto-enhance</strong><small>{edit.auto.on ? 'Balanced light, contrast and colour' : 'One tap to fix light and colour'}</small></span>
            <span className="ed-switch" aria-hidden="true" />
          </button>
          {edit.auto.on && <Slider label="Strength" value={edit.auto.strength} min={0} max={100} format={v => v + '%'} onChange={v => update(e => ({ auto: { ...e.auto, strength: v } }))} />}
        </div>}

        {tab === 'adjust' && <>
          <Slider key={active} label={activeDef.label} value={edit.adjust[active]} min={activeDef.min} max={activeDef.max} onChange={v => setAdjust(active, v)} onReset={() => setAdjust(active, 0)} />
          <div className="ed-strip" role="group" aria-label="Adjustments">
            {ADJUSTMENTS.map(a => {
              const Icon = ICONS[a.key]
              const value = edit.adjust[a.key]
              return <button key={a.key} className={'ed-chip' + (active === a.key ? ' selected' : '') + (value ? ' changed' : '')} aria-pressed={active === a.key} onClick={() => setActive(a.key)}>
                <span className="ed-chip-icon"><Icon size={19} /></span><span>{a.label}</span>
              </button>
            })}
          </div>
        </>}

        {tab === 'filters' && <>
          {filterActive ? <Slider label={FILTERS.find(f => f.id === edit.filter.id).label + ' intensity'} value={edit.filter.strength} min={0} max={100} format={v => v + '%'} onChange={v => update(e => ({ filter: { ...e.filter, strength: v } }))} />
            : <p className="ed-hint">Pick a look. You can fine-tune it afterwards.</p>}
          <div className="ed-strip" role="group" aria-label="Filters">
            {FILTERS.map(f => <button key={f.id} className={'ed-filter' + (edit.filter.id === f.id ? ' selected' : '')} aria-pressed={edit.filter.id === f.id} onClick={() => update({ filter: { id: f.id, strength: edit.filter.id === f.id ? edit.filter.strength : 100 } })}>
              {thumbs[f.id] ? <img src={thumbs[f.id]} alt="" /> : <span className="ed-filter-ph" />}<span>{f.label}</span>
            </button>)}
          </div>
        </>}

        {tab === 'crop' && <>
          <Slider label="Straighten" value={edit.angle} min={-45} max={45} format={v => (v > 0 ? '+' : '') + v + '°'} onChange={v => update({ angle: v })} onReset={() => update({ angle: 0 })} />
          <div className="ed-strip" role="group" aria-label="Crop tools">
            <button className="ed-chip" onClick={rotateLeft}><span className="ed-chip-icon"><RotateCcw size={19} /></span><span>Rotate</span></button>
            <button className="ed-chip" onClick={flipX}><span className="ed-chip-icon"><FlipHorizontal2 size={19} /></span><span>Flip</span></button>
            <button className="ed-chip" onClick={flipY}><span className="ed-chip-icon"><FlipVertical2 size={19} /></span><span>Flip V</span></button>
            <span className="ed-divider" aria-hidden="true" />
            {ASPECTS.map(a => <button key={a.id} className={'ed-ratio' + (edit.aspect === a.id ? ' selected' : '')} aria-pressed={edit.aspect === a.id} onClick={() => chooseAspect(a.id)}>{a.label}</button>)}
            <span className="ed-divider" aria-hidden="true" />
            <button className="ed-ratio" onClick={resetCrop} disabled={!geometryChanged}>Reset</button>
          </div>
        </>}
      </div>

      <nav className="ed-tabs" aria-label="Tool">
        {TABS.map(([key, label, Icon]) => {
          const marked = key === 'auto' ? edit.auto.on : key === 'adjust' ? Object.values(edit.adjust).some(Boolean) : key === 'filters' ? filterActive : geometryChanged
          return <button key={key} aria-pressed={tab === key} className={tab === key ? 'selected' : ''} onClick={() => setTab(key)}>
            <span className="ed-tab-icon"><Icon size={21} />{marked && <i className="ed-dot" />}</span><span>{label}</span>
          </button>
        })}
      </nav>
    </section>

    {confirmDiscard && <div className="ed-confirm" role="alertdialog" aria-label="Discard changes?">
      <div>
        <strong>Discard your edits?</strong>
        <p>Your changes to this photo will be lost.</p>
        <div className="ed-confirm-actions">
          <button className="ed-secondary" onClick={() => setConfirmDiscard(false)} autoFocus>Keep editing</button>
          <button className="ed-danger" onClick={onClose}><X size={17} />Discard</button>
        </div>
      </div>
    </div>}
  </dialog>
}
