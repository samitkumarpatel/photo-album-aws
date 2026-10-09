import { useEffect, useRef, useState } from 'react'
import { ArrowLeft, ArrowRight, Save } from 'lucide-react'
import CameraSpinner from '../CameraSpinner.jsx'
import { mediaUrl } from '../media.js'
import { uploadToAlbum } from '../upload/uploads.js'
import { loadEditableImage } from './loadImage.js'
import { scaledCopy } from './imageOps.js'
import { renderCollage } from './collageOps.js'
import './collage.css'

export default function CollageEditor({ items, albums, Dialog, close, onSaved }) {
  const [order, setOrder] = useState(items)
  const [images, setImages] = useState(null)
  const [loaded, setLoaded] = useState(0)
  const [retry, setRetry] = useState(0)
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const [albumId, setAlbumId] = useState(items[0].albumId)
  const [name, setName] = useState('Collage')
  const [options, setOptions] = useState({ ratio: 1, layout: 'grid', spacing: 2, color: '#ffffff', fit: 'cover' })
  const canvas = useRef(null)
  const update = patch => setOptions(previous => ({ ...previous, ...patch }))

  useEffect(() => {
    const controller = new AbortController()
    setImages(null); setError(''); setLoaded(0)
    Promise.allSettled(items.map(async item => {
      const image = await loadEditableImage(mediaUrl(item, 'original'), controller.signal)
      if (controller.signal.aborted) return null
      const copy = scaledCopy(image, 2400)
      setLoaded(n => n + 1)
      return [item.id, copy]
    })).then(results => {
      if (controller.signal.aborted) return
      if (results.some(result => result.status === 'rejected')) {
        setError('Some photos couldn’t be opened. Check your connection and try again, or select photos your browser can display.')
      } else setImages(new Map(results.map(result => result.value)))
    })
    return () => controller.abort()
  }, [items, retry])

  useEffect(() => {
    if (!images || !canvas.current) return
    renderCollage(canvas.current, order.map(item => images.get(item.id)), options)
  }, [images, order, options])

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
      const output = renderCollage(document.createElement('canvas'), order.map(item => images.get(item.id)), options, 2400)
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
      <canvas ref={canvas} aria-label="Collage preview" role="img" hidden={!images} />
    </div>
    <fieldset className="collage-controls" disabled={saving}>
      <legend className="visually-hidden">Collage settings</legend>
      <label className="field">Layout<select data-autofocus value={options.layout} onChange={e => update({ layout: e.target.value })}>
        <option value="grid">Grid</option><option value="feature">Featured photo</option><option value="horizontal">Side by side</option><option value="vertical">Stacked</option>
      </select></label>
      <label className="field">Shape<select value={options.ratio} onChange={e => update({ ratio: Number(e.target.value) })}>
        <option value="1">Square</option><option value="0.8">Portrait · 4:5</option><option value="1.5">Landscape · 3:2</option>
      </select></label>
      <label className="field">Photo fit<select value={options.fit} onChange={e => update({ fit: e.target.value })}>
        <option value="cover">Fill each frame</option><option value="contain">Show full photos</option>
      </select></label>
      <label className="field">Background color<input type="color" value={options.color} onChange={e => update({ color: e.target.value })} /></label>
      <label className="field">Spacing · {options.spacing}%<input type="range" min="0" max="5" step="0.5" value={options.spacing} onChange={e => update({ spacing: Number(e.target.value) })} /></label>
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
