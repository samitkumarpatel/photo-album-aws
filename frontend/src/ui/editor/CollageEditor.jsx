import { useEffect, useRef, useState } from 'react'
import { ArrowLeft, ArrowLeftRight, ArrowRight, RotateCcw, Save } from 'lucide-react'
import CameraSpinner from '../CameraSpinner.jsx'
import { mediaUrl } from '../media.js'
import { uploadToAlbum } from '../upload/uploads.js'
import { loadEditableImage } from './loadImage.js'
import { scaledCopy } from './imageOps.js'
import { renderCollage, collageGeometry, framePlacement, layoutCells, layoutsFor } from './collageOps.js'
import './collage.css'

const SHAPES = [[1, 'Square'], [0.8, 'Portrait 4:5'], [0.5625, 'Story 9:16'], [1.5, 'Landscape 3:2'], [16 / 9, 'Wide 16:9']]
const STYLES = [
  ['Clean', { spacing: 2, border: 0, radius: 0, color: '#ffffff', borderColor: '#ffffff', textColor: '#222222', font: 'sans' }],
  ['Seamless', { spacing: 0, border: 0, radius: 0, color: '#ffffff', borderColor: '#ffffff', textColor: '#222222', font: 'sans' }],
  ['Postcard', { spacing: 3, border: 1, radius: 3, color: '#f9efdb', borderColor: '#ffffff', textColor: '#473522', font: 'serif' }],
  ['Dark gallery', { spacing: 2, border: 0, radius: 0, color: '#171717', borderColor: '#171717', textColor: '#ffffff', font: 'sans' }],
  ['Soft frames', { spacing: 3, border: 1, radius: 10, color: '#e7eaf1', borderColor: '#ffffff', textColor: '#222222', font: 'sans' }],
  ['Polaroid', { spacing: 4, border: 2.5, radius: 0, color: '#efe9e1', borderColor: '#ffffff', textColor: '#333333', font: 'serif' }],
]
const TABS = [['layout', 'Layout'], ['style', 'Style'], ['text', 'Text'], ['photo', 'Photo']]

function Segmented({ label, value, options, onChange }) {
  return <div className="segmented collage-segmented" role="group" aria-label={label}>
    {options.map(([id, text]) => <button key={id} type="button" className={value === id ? 'selected' : ''} aria-pressed={value === id} onClick={() => onChange(id)}>{text}</button>)}
  </div>
}

export default function CollageEditor({ items, albums, Dialog, close, onSaved }) {
  const [order, setOrder] = useState(items)
  const [images, setImages] = useState(null)
  const [resolution, setResolution] = useState(2400)
  const [selected, setSelected] = useState(items[0].id)
  const [swapping, setSwapping] = useState(false)
  const [tab, setTab] = useState('layout')
  const [frames, setFrames] = useState({})
  const drag = useRef(null)
  const [loaded, setLoaded] = useState(0)
  const [retry, setRetry] = useState(0)
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const [albumId, setAlbumId] = useState(items[0].albumId)
  const [name, setName] = useState('Collage')
  const [options, setOptions] = useState({ ratio: 1, layout: 'smart', fit: 'cover', ...STYLES[0][1] })
  const canvas = useRef(null)
  const [geometry, setGeometry] = useState(null)
  const renderOptions = () => ({ ...options, frames: order.map(item => frames[item.id] || {}) })
  const updateFrame = (id, patch) => setFrames(v => ({ ...v, [id]: { ...v[id], ...patch } }))
  const update = patch => setOptions(previous => ({ ...previous, ...patch }))
  // Stored dimensions let the layout picker reflect photo shapes before the originals finish loading.
  const aspectOf = item => { const image = images?.get(item.id); return image ? image.width / image.height : item.width > 0 && item.height > 0 ? item.width / item.height : 1 }
  const aspects = order.map(aspectOf)
  const selectedIndex = Math.max(0, order.findIndex(item => item.id === selected))
  const selectedItem = order[selectedIndex]

  useEffect(() => {
    const controller = new AbortController()
    setImages(null); setError(''); setLoaded(0)
    ;(async () => {
      const map = new Map()
      try {
        for (const item of items) {
          const image = await loadEditableImage(mediaUrl(item, 'original'), controller.signal)
          if (controller.signal.aborted) return
          map.set(item.id, scaledCopy(image, 4000)); setLoaded(n => n + 1)
        }
        setImages(map)
      } catch (e) { if (!controller.signal.aborted) setError(e.message || 'Some photos could not be opened.') }
    })()
    return () => controller.abort()
  }, [items, retry])

  useEffect(() => {
    if (!images || !canvas.current) return
    const output = renderCollage(canvas.current, order.map(item => images.get(item.id)), renderOptions())
    setGeometry({ ...collageGeometry(aspects, options, output.width, output.height), width: output.width, height: output.height })
  }, [images, order, options, frames])

  function move(index, direction) {
    setOrder(previous => {
      const next = [...previous], target = index + direction
      ;[next[index], next[target]] = [next[target], next[index]]
      return next
    })
  }

  function swapWith(index) {
    setOrder(previous => {
      const next = [...previous]
      ;[next[selectedIndex], next[index]] = [next[index], next[selectedIndex]]
      return next
    })
    setSwapping(false)
  }

  const cellAt = (e, element) => {
    const rect = element.getBoundingClientRect()
    const x = (e.clientX - rect.left) * element.width / rect.width, y = (e.clientY - rect.top) * element.height / rect.height
    return { rect, index: geometry?.cells.findIndex(c => x >= c.x && x <= c.x + c.w && y >= c.y && y <= c.y + c.h) ?? -1 }
  }

  function pointerDown(e) {
    if (saving || !images || !geometry) return
    const { rect, index } = cellAt(e, e.currentTarget)
    if (index < 0) return
    const item = order[index]
    const place = framePlacement(images.get(item.id), geometry.cells[index], options, frames[item.id], Math.min(geometry.width, geometry.height))
    drag.current = { index, id: item.id, x: e.clientX, y: e.clientY, frame: frames[item.id] || {}, rect, place, moved: false }
    e.currentTarget.setPointerCapture(e.pointerId)
  }

  function pointerMove(e) {
    const d = drag.current
    if (!d) return
    const dx = e.clientX - d.x, dy = e.clientY - d.y
    if (!d.moved && Math.hypot(dx, dy) < 6) return
    d.moved = true
    if (options.fit === 'contain' || swapping) return
    if (selected !== d.id) setSelected(d.id)
    // Drag follows the finger: convert screen pixels to canvas pixels, then to the photo's overflow range.
    const scale = geometry.width / d.rect.width
    const spareX = d.place.box.w - d.place.w, spareY = d.place.box.h - d.place.h
    const clamp = v => Math.max(0, Math.min(1, v))
    updateFrame(d.id, {
      x: spareX < -0.5 ? clamp((d.frame.x ?? .5) + dx * scale / spareX) : .5,
      y: spareY < -0.5 ? clamp((d.frame.y ?? .5) + dy * scale / spareY) : .5,
    })
  }

  function pointerUp() {
    const d = drag.current
    drag.current = null
    if (!d || d.moved) return
    if (swapping && d.index !== selectedIndex) swapWith(d.index)
    else { setSelected(d.id); setSwapping(false); setTab('photo') }
  }

  async function save() {
    if (!images || saving || !albumId || !name.trim()) return
    setSaving(true); setError('')
    try {
      const output = renderCollage(document.createElement('canvas'), order.map(item => images.get(item.id)), renderOptions(), resolution)
      const blob = await new Promise(resolve => output.toBlob(resolve, 'image/jpeg', 0.94))
      if (!blob) throw new Error('The collage couldn’t be created. Try again.')
      const filename = name.trim().replace(/\.(jpe?g|png|webp)$/i, '') + '.jpg'
      const photo = await uploadToAlbum(albumId, new File([blob], filename, { type: 'image/jpeg' }))
      await onSaved(photo, albumId)
    } catch (e) { setError(e.message); setSaving(false) }
  }

  const pct = (v, total) => (v / total * 100) + '%'
  const frame = frames[selected] || {}

  return <Dialog title="Create a collage" description={items.length + ' photos · Tap a photo to select it, drag to reposition.'} close={close} busy={saving} wide>
    <div className="collage-preview">
      {!images && !error && <span role="status"><CameraSpinner size={28} inherit decorative />Opening photos… {loaded}/{items.length}</span>}
      <div className="collage-stage" hidden={!images}>
        <canvas ref={canvas} aria-label="Collage preview" role="img" className={swapping ? 'swapping' : ''}
          onPointerDown={pointerDown} onPointerMove={pointerMove} onPointerUp={pointerUp} onPointerCancel={() => { drag.current = null }} />
        {geometry && geometry.cells.map((cell, i) => (i === selectedIndex || swapping) && <span key={i} aria-hidden="true"
          className={'collage-cell-mark' + (i === selectedIndex ? ' selected' : ' target')}
          style={{ left: pct(cell.x, geometry.width), top: pct(cell.y, geometry.height), width: pct(cell.w, geometry.width), height: pct(cell.h, geometry.height) }} />)}
      </div>
      {swapping && <p className="collage-hint" role="status">Tap another photo to swap places</p>}
    </div>

    <Segmented label="Collage settings" value={tab} options={TABS} onChange={setTab} />

    <fieldset className="collage-panel" disabled={saving}>
      <legend className="visually-hidden">{TABS.find(([id]) => id === tab)[1]}</legend>
      {tab === 'layout' && <>
        <div className="layout-picker" role="group" aria-label="Layout">
          {layoutsFor(order.length).map(([id, label]) => {
            const ratio = Math.min(1.5, Math.max(.7, options.ratio))
            return <button key={id} type="button" className={'layout-option' + (options.layout === id ? ' selected' : '')} aria-pressed={options.layout === id} onClick={() => update({ layout: id })}>
              <svg viewBox={`0 0 ${100 * ratio} 100`} aria-hidden="true">
                {layoutCells(id, aspects, ratio).map((c, i) => <rect key={i} x={c.x * 100 * ratio + 2.5} y={c.y * 100 + 2.5} width={Math.max(1, c.w * 100 * ratio - 5)} height={Math.max(1, c.h * 100 - 5)} rx="3" />)}
              </svg>
              <span>{label}</span>
            </button>
          })}
        </div>
        <div className="field">Shape
          <div className="chip-row" role="group" aria-label="Shape">{SHAPES.map(([ratio, label]) =>
            <button key={label} type="button" className={'chip' + (options.ratio === ratio ? ' selected' : '')} aria-pressed={options.ratio === ratio} onClick={() => update({ ratio })}>{label}</button>)}
          </div>
        </div>
      </>}

      {tab === 'style' && <>
        <div className="chip-row collage-styles" role="group" aria-label="Style presets">{STYLES.map(([label, patch]) =>
          <button key={label} type="button" className="chip" onClick={() => update(patch)}><span className="style-swatch" style={{ background: patch.color, borderColor: patch.borderColor }} />{label}</button>)}
        </div>
        <div className="field">Photo fit<Segmented label="Photo fit" value={options.fit} options={[['cover', 'Fill frames'], ['contain', 'Whole photos']]} onChange={fit => update({ fit })} /></div>
        <div className="collage-controls">
          <label className="field">Spacing · {options.spacing}%<input type="range" min="0" max="6" step="0.5" value={options.spacing} onChange={e => update({ spacing: Number(e.target.value) })} /></label>
          <label className="field">Rounded corners · {options.radius || 0}%<input type="range" min="0" max="25" value={options.radius || 0} onChange={e => update({ radius: Number(e.target.value) })} /></label>
          <label className="field">Border · {options.border || 0}%<input type="range" min="0" max="3" step=".25" value={options.border || 0} onChange={e => update({ border: Number(e.target.value) })} /></label>
          <label className="field">Border color<input type="color" value={options.borderColor || '#ffffff'} onChange={e => update({ borderColor: e.target.value })} /></label>
          <label className="field">Background<input type="color" value={options.color} onChange={e => update({ color: e.target.value })} /></label>
        </div>
      </>}

      {tab === 'text' && <>
        <label className="field">Title <span>Optional</span><input maxLength={90} value={options.title || ''} placeholder="Summer 2026" onChange={e => update({ title: e.target.value })} /></label>
        <div className="collage-controls">
          <div className="field">Title position<Segmented label="Title position" value={options.titlePosition || 'bottom'} options={[['top', 'Top'], ['bottom', 'Bottom']]} onChange={titlePosition => update({ titlePosition })} /></div>
          <div className="field">Font<Segmented label="Font" value={options.font || 'sans'} options={[['sans', 'Modern'], ['serif', 'Classic']]} onChange={font => update({ font })} /></div>
          <label className="field">Text color<input type="color" value={options.textColor || '#222222'} onChange={e => update({ textColor: e.target.value })} /></label>
        </div>
        <label className="field">Caption for photo {selectedIndex + 1} <span>Optional</span><input maxLength={90} value={frame.caption || ''} onChange={e => updateFrame(selected, { caption: e.target.value })} /></label>
      </>}

      {tab === 'photo' && <>
        <div className="collage-thumbs" role="group" aria-label="Choose a photo">
          {order.map((item, i) => <button key={item.id} type="button" className={item.id === selected ? 'selected' : ''} aria-pressed={item.id === selected}
            aria-label={'Photo ' + (i + 1) + ': ' + item.filename} onClick={() => swapping && i !== selectedIndex ? swapWith(i) : (setSelected(item.id), setSwapping(false))}>
            <img src={mediaUrl(item, 'thumbnail')} alt="" /><span>{i + 1}</span>
          </button>)}
        </div>
        <p className="collage-selected-name">Photo {selectedIndex + 1} · {selectedItem.filename}</p>
        <div className="collage-photo-actions">
          <button type="button" className={'button secondary' + (swapping ? ' active' : '')} aria-pressed={swapping} onClick={() => setSwapping(v => !v)}><ArrowLeftRight size={17} />{swapping ? 'Cancel swap' : 'Swap'}</button>
          <button type="button" className="button secondary" disabled={selectedIndex === 0} aria-label="Move earlier" onClick={() => move(selectedIndex, -1)}><ArrowLeft size={17} /></button>
          <button type="button" className="button secondary" disabled={selectedIndex === order.length - 1} aria-label="Move later" onClick={() => move(selectedIndex, 1)}><ArrowRight size={17} /></button>
          <button type="button" className="button secondary" onClick={() => updateFrame(selected, { x: .5, y: .5, zoom: 1 })}><RotateCcw size={17} />Reset</button>
        </div>
        <label className="field">Zoom · {Math.round((frame.zoom || 1) * 100)}%<input type="range" min="1" max="3" step=".05" disabled={options.fit !== 'cover'} value={frame.zoom || 1} onChange={e => updateFrame(selected, { zoom: Number(e.target.value) })} /></label>
        {options.fit !== 'cover' && <small className="collage-note">Zoom and drag work with “Fill frames” in Style.</small>}
      </>}
    </fieldset>

    <div className="collage-save">
      <label className="field">Name<input maxLength={90} value={name} onChange={e => setName(e.target.value)} disabled={saving} /></label>
      <label className="field">Save to album<select value={albumId} onChange={e => setAlbumId(e.target.value)} disabled={saving}>{albums.map(album => <option key={album.id} value={album.id}>{album.name}</option>)}</select></label>
      <label className="field">Quality<select value={resolution} onChange={e => setResolution(Number(e.target.value))} disabled={saving}><option value="2400">Standard · 2400 px</option><option value="4000">High · 4000 px</option></select></label>
    </div>
    {error && <div role="alert" className="collage-error">{error}{!images && <button className="button secondary" onClick={() => setRetry(n => n + 1)}>Try again</button>}</div>}
    <div className="dialog-actions"><button className="button secondary" onClick={close} disabled={saving}>Cancel</button>
      <button className="button primary" onClick={save} disabled={!images || saving || !name.trim() || !albums.some(album => album.id === albumId)}>{saving ? <CameraSpinner size={20} inherit decorative /> : <Save size={18} />}{saving ? 'Saving…' : 'Save collage'}</button>
    </div>
  </Dialog>
}
