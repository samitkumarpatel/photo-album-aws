import { useState } from 'react'
import { api } from '../api.js'
import { loadEditableImage } from './loadImage.js'
import { scaledCopy } from './imageOps.js'
import { isVideo, statusOf } from '../media.js'
export default function AlbumPresentation({ album, items, Dialog, close, onSaved }) {
  const [value, setValue] = useState({ theme: 'classic', coverPhotoId: null, logo: null, brandName: '', watermark: '', slideshowSeconds: 5, ...album.presentation })
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  const update = patch => setValue(v => ({ ...v, ...patch }))
  async function logo(file) {
    if (!file) return
    setBusy(true); setError('')
    const url = URL.createObjectURL(file)
    try {
      if (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type)) throw new Error('Choose a PNG, JPEG or WebP logo.')
      const image = await loadEditableImage(url)
      const data = scaledCopy(image, 320).toDataURL('image/png')
      if (data.length > 180000) throw new Error('This logo is too detailed. Choose a smaller image.')
      update({ logo: data })
    } catch (e) { setError(e.message) } finally { URL.revokeObjectURL(url); setBusy(false) }
  }
  async function save(e) {
    e.preventDefault(); setBusy(true); setError('')
    try { await api('/api/albums/' + album.id + '/presentation', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(value) }); await onSaved() }
    catch (e) { setError(e.message); setBusy(false) }
  }
  return <Dialog title="Album presentation" close={close} busy={busy}><form onSubmit={save}><fieldset disabled={busy} className="presentation-fields">
    <label className="field">Gallery theme<select value={value.theme} onChange={e => update({ theme: e.target.value })}>{['classic', 'dark', 'warm', 'minimal'].map(t => <option key={t}>{t}</option>)}</select></label>
    <label className="field">Cover photo<select value={value.coverPhotoId || ''} onChange={e => update({ coverPhotoId: e.target.value || null })}><option value="">Automatic</option>{items.filter(i => !isVideo(i) && statusOf(i) === 'READY').map(i => <option key={i.id} value={i.id}>{i.filename}</option>)}</select></label>
    <label className="field">Brand name<input maxLength={90} value={value.brandName || ''} onChange={e => update({ brandName: e.target.value })} /></label>
    <label className="field">Logo<input type="file" accept="image/png,image/jpeg,image/webp" onChange={e => logo(e.target.files[0])} /></label>
    {value.logo && <div className="album-brand"><img src={value.logo} alt="Logo preview" /><button type="button" className="button secondary" onClick={() => update({ logo: null })}>Remove logo</button></div>}
    <label className="field">Presentation watermark<input maxLength={90} value={value.watermark || ''} onChange={e => update({ watermark: e.target.value })} /><small>Shown over the slideshow. Downloaded originals keep their original pixels; use the photo or batch editor to stamp exported copies.</small></label>
    <label className="field">Seconds per photo<input type="number" min="3" max="30" value={value.slideshowSeconds} onChange={e => update({ slideshowSeconds: Number(e.target.value) })} /></label>
    </fieldset>{error && <p role="alert">{error}</p>}<div className="dialog-actions"><button type="button" className="button secondary" disabled={busy} onClick={close}>Cancel</button><button className="button primary" disabled={busy}>{busy ? 'Saving…' : 'Save presentation'}</button></div></form></Dialog>
}
