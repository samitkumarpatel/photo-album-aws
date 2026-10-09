import { useEffect, useRef, useState } from 'react'
import { ArrowLeft, ArrowRight, Save } from 'lucide-react'
import CameraSpinner from '../CameraSpinner.jsx'
import { mediaUrl } from '../media.js'
import { uploadToAlbum } from '../upload/uploads.js'
import { loadEditableImage } from './loadImage.js'
import { scaledCopy } from './imageOps.js'
import { renderCollage, collageCells } from './collageOps.js'
import './collage.css'

export default function CollageEditor({ items, albums, Dialog, close, onSaved }) {
  const [order, setOrder] = useState(items)
  const [images, setImages] = useState(null)
  const [resolution, setResolution] = useState(2400)
  const [selected, setSelected] = useState(items[0].id)
  const [frames, setFrames] = useState({})
  const drag = useRef(null)
  const [loaded, setLoaded] = useState(0)
  const [retry, setRetry] = useState(0)
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const [albumId, setAlbumId] = useState(items[0].albumId)
  const [name, setName] = useState('Collage')
  const [options, setOptions] = useState({ ratio: 1, layout: 'grid', spacing: 2, color: '#ffffff', fit: 'cover' })
  const canvas = useRef(null)
  const renderOptions = () => ({ ...options, frames: order.map(item => frames[item.id] || {}) })
  const updateFrame = patch => setFrames(v => ({ ...v, [selected]: { ...v[selected], ...patch } }))
  const update = patch => setOptions(previous => ({ ...previous, ...patch }))

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
    renderCollage(canvas.current, order.map(item => images.get(item.id)), renderOptions())
  }, [images, order, options, frames])

  function move(index, direction) {
    setOrder(previous => {
      const next = [...previous], target = index + direction
      ;[next[index], next[target]] = [next[target], next[index]]
      return next
    })
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

  return <Dialog title="Create a collage" description={items.length + ' photos · Arrange your photos and save a new image to an album.'} close={close} busy={saving} wide>
    <div className="collage-preview">
      {!images && !error && <span role="status"><CameraSpinner size={28} inherit decorative />Opening photos… {loaded}/{items.length}</span>}
      <canvas ref={canvas} aria-label="Collage preview" role="img" hidden={!images} style={{ touchAction: 'none', cursor: 'grab' }}
        onPointerDown={e => {
          if (saving || !images || options.fit !== 'cover') return
          const rect = e.currentTarget.getBoundingClientRect(), x = (e.clientX - rect.left) * e.currentTarget.width / rect.width, y = (e.clientY - rect.top) * e.currentTarget.height / rect.height
          const h = e.currentTarget.height * (options.title ? .91 : 1)
          const cells = collageCells(order.length, e.currentTarget.width, h, options.layout, options.spacing)
          const i = cells.findIndex(c => x >= c.x && x <= c.x + c.w && y >= c.y && y <= c.y + c.h)
          if (i < 0) return
          const id = order[i].id; setSelected(id); drag.current = { id, x: e.clientX, y: e.clientY, frame: frames[id] || {}, rect }; e.currentTarget.setPointerCapture(e.pointerId)
        }}
        onPointerMove={e => {
          const d = drag.current; if (!d) return
          setFrames(v => ({ ...v, [d.id]: { ...v[d.id], x: Math.max(0, Math.min(1, (d.frame.x ?? .5) - (e.clientX - d.x) / d.rect.width * 3)), y: Math.max(0, Math.min(1, (d.frame.y ?? .5) - (e.clientY - d.y) / d.rect.height * 3)) } }))
        }} onPointerUp={() => { drag.current = null }} onPointerCancel={() => { drag.current = null }} />
    </div>
    <div className="chip-row" role="group" aria-label="Collage templates">{[
      ['Clean grid', { layout: 'grid', spacing: 2, border: 0, radius: 0, color: '#ffffff', borderColor: '#ffffff' }],
      ['Postcard', { layout: 'hero-top', spacing: 3, border: 1, radius: 3, color: '#f9efdb', borderColor: '#ffffff' }],
      ['Dark gallery', { layout: 'feature', spacing: 2, border: .5, radius: 0, color: '#171717', borderColor: '#171717', textColor: '#ffffff' }],
      ['Soft frames', { layout: 'grid', spacing: 3, border: 1, radius: 10, color: '#e7eaf1', borderColor: '#ffffff', textColor: '#222222' }],
    ].map(([label, patch]) => <button key={label} className="chip" disabled={saving} onClick={() => update({ textColor: '#222222', ...patch })}>{label}</button>)}</div>
    <fieldset className="collage-controls" disabled={saving}>
      <legend className="visually-hidden">Collage settings</legend>
      <label className="field">Layout<select data-autofocus value={options.layout} onChange={e => update({ layout: e.target.value })}>
        <option value="grid">Grid</option><option value="mosaic">Mosaic</option><option value="hero-top">Featured photo above</option><option value="feature">Featured photo</option><option value="horizontal">Side by side</option><option value="vertical">Stacked</option>
      </select></label>
      <label className="field">Shape<select value={options.ratio} onChange={e => update({ ratio: Number(e.target.value) })}>
        <option value="1">Square</option><option value="0.8">Portrait · 4:5</option><option value="1.5">Landscape · 3:2</option>
      </select></label>
      <label className="field">Photo fit<select value={options.fit} onChange={e => update({ fit: e.target.value })}>
        <option value="cover">Fill each frame</option><option value="contain">Show full photos</option>
      </select></label>
      <label className="field">Background color<input type="color" value={options.color} onChange={e => update({ color: e.target.value })} /></label>
      <label className="field">Spacing · {options.spacing}%<input type="range" min="0" max="5" step="0.5" value={options.spacing} onChange={e => update({ spacing: Number(e.target.value) })} /></label>
      <label className="field">Border · {options.border || 0}%<input type="range" min="0" max="3" step=".25" value={options.border || 0} onChange={e => update({ border: Number(e.target.value) })} /></label>
      <label className="field">Border color<input type="color" value={options.borderColor || '#ffffff'} onChange={e => update({ borderColor: e.target.value })} /></label>
      <label className="field">Rounded corners<input type="range" min="0" max="25" value={options.radius || 0} onChange={e => update({ radius: Number(e.target.value) })} /></label>
      <label className="field">Title<input maxLength={90} value={options.title || ''} onChange={e => update({ title: e.target.value })} /></label>
      <label className="field">Caption color<input type="color" value={options.textColor || '#222222'} onChange={e => update({ textColor: e.target.value })} /></label>
      <label className="field">Export resolution<select value={resolution} onChange={e => setResolution(Number(e.target.value))}><option value="2400">2400 px</option><option value="4000">4000 px · high resolution</option></select></label>
    </fieldset>
    <fieldset className="collage-controls" disabled={saving}><legend>Photo framing · drag a photo in the preview</legend>
      <label className="field">Selected photo<select value={selected} onChange={e => setSelected(e.target.value)}>{order.map(item => <option key={item.id} value={item.id}>{item.filename}</option>)}</select></label>
      <label className="field">Zoom<input type="range" min="1" max="3" step=".05" disabled={options.fit !== 'cover'} value={frames[selected]?.zoom || 1} onChange={e => updateFrame({ zoom: Number(e.target.value) })} /></label>
      <label className="field">Caption<input maxLength={90} value={frames[selected]?.caption || ''} onChange={e => updateFrame({ caption: e.target.value })} /></label>
      <button className="button secondary" onClick={() => updateFrame({ x: .5, y: .5, zoom: 1 })}>Center photo</button>
    </fieldset>
    <ol className="collage-order" aria-label="Photo order">
      {order.map((item, i) => <li key={item.id}><img src={mediaUrl(item, 'thumbnail')} alt="" /><span>{i + 1}. {item.filename}</span>
        <button className="icon-button" disabled={saving || i === 0} aria-label={'Move ' + item.filename + ' earlier'} onClick={() => move(i, -1)}><ArrowLeft size={16} /></button>
        <button className="icon-button" disabled={saving || i === order.length - 1} aria-label={'Move ' + item.filename + ' later'} onClick={() => move(i, 1)}><ArrowRight size={16} /></button>
      </li>)}
    </ol>
    <label className="field">Name<input maxLength={90} value={name} onChange={e => setName(e.target.value)} disabled={saving} /></label>
    <label className="field">Save to album<select value={albumId} onChange={e => setAlbumId(e.target.value)} disabled={saving}>{albums.map(album => <option key={album.id} value={album.id}>{album.name}</option>)}</select></label>
    {error && <div role="alert" className="collage-error">{error}{!images && <button className="button secondary" onClick={() => setRetry(n => n + 1)}>Try again</button>}</div>}
    <div className="dialog-actions"><button className="button secondary" onClick={close} disabled={saving}>Cancel</button>
      <button className="button primary" onClick={save} disabled={!images || saving || !name.trim() || !albums.some(album => album.id === albumId)}>{saving ? <CameraSpinner size={20} inherit decorative /> : <Save size={18} />}{saving ? 'Saving…' : 'Save collage'}</button>
    </div>
  </Dialog>
}
