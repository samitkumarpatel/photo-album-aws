import { useEffect, useRef, useState } from 'react'
import { CheckCircle2, SlidersHorizontal } from 'lucide-react'
import CameraSpinner from '../CameraSpinner.jsx'
import { FILTERS, analyze, combineParams, exportBlob, outputFormat, render, scaledCopy } from './imageOps.js'
import { loadEditDocument } from './editDocument.js'
import { saveEditedPhoto } from './savePhoto.js'
import './collage.css'

export default function BatchEditor({ items, Dialog, close, onChanged }) {
  const [preset, setPreset] = useState('keep')
  const [maxSide, setMaxSide] = useState(0)
  const [watermark, setWatermark] = useState('')
  const [opacity, setOpacity] = useState(60)
  const [position, setPosition] = useState('bottom-right')
  const [mode, setMode] = useState('copy')
  const [preview, setPreview] = useState(null)
  const [error, setError] = useState('')
  const [jobs, setJobs] = useState(() => items.map(item => ({ item, status: 'pending', message: '' })))
  const [busy, setBusy] = useState(false)
  const stopRequested = useRef(false)
  const controller = useRef(null)
  const canvas = useRef(null)
  const optionsLocked = jobs.some(job => job.status === 'saved')
  const changed = preset !== 'keep' || maxSide > 0 || !!watermark.trim()
  const apply = original => ({ ...original,
    filter: preset === 'keep' ? original.filter : { id: preset, strength: 100 },
    export: { maxSide },
    watermark: watermark.trim() ? { ...original.watermark, value: watermark.trim(), opacity, position } : original.watermark,
  })
  useEffect(() => {
    const abort = new AbortController()
    loadEditDocument(items[0], abort.signal).then(value => { if (!abort.signal.aborted) setPreview(value) })
      .catch(e => { if (e.name !== 'AbortError') setError(e.message) })
    return () => { abort.abort(); controller.current?.abort() }
  }, [items])
  useEffect(() => {
    if (!preview || !canvas.current) return
    const edit = apply(preview.edit), sample = scaledCopy(preview.image, 900)
    render(canvas.current, sample, sample.width, sample.height, edit, combineParams(edit, analyze(preview.image)), 'final', preview.mask, preview.backgroundImage)
  }, [preview, preset, maxSide, watermark, opacity, position])
  const updateJob = (id, patch) => setJobs(previous => previous.map(job => job.item.id === id ? { ...job, ...patch } : job))
  async function run() {
    if (busy || !changed) return
    const abort = new AbortController(); controller.current = abort
    stopRequested.current = false; setBusy(true); setError('')
    for (const job of jobs.filter(job => job.status !== 'saved')) {
      if (abort.signal.aborted || stopRequested.current) break
      updateJob(job.item.id, { status: 'processing', message: '' })
      try {
        const loaded = await loadEditDocument(job.item, abort.signal)
        const edit = apply(loaded.edit), params = combineParams(edit, analyze(loaded.image))
        const { type, name, copyName } = outputFormat(job.item.contentType, job.item.filename, edit.background.on && edit.background.fill === 'transparent')
        const blob = await exportBlob(loaded.image, edit, params, type, loaded.mask, loaded.backgroundImage)
        const recipe = { ...edit, baseVersion: loaded.baseVersion, background: { ...edit.background, maskData: loaded.mask ? scaledCopy(loaded.mask, 1024).toDataURL('image/png') : '' } }
        await saveEditedPhoto(job.item, new File([blob], mode === 'copy' ? copyName : name, { type }), recipe, mode, abort.signal)
        updateJob(job.item.id, { status: 'saved', message: 'Saved' })
      } catch (e) {
        updateJob(job.item.id, { status: 'failed', message: e.name === 'AbortError' ? 'Canceled · ready to retry' : e.message })
        if (abort.signal.aborted || stopRequested.current) break
      }
    }
    controller.current = null; setBusy(false); try { await onChanged() } catch (e) { setError(e.message) }
  }
  const completed = jobs.filter(job => job.status === 'saved').length
  return <Dialog title="Batch edit photos" description={items.length + ' photos · Preview the first photo, then apply to the selection.'} close={close} busy={busy} wide>
    <div className="collage-preview">{!preview && !error && <span role="status"><CameraSpinner size={26} inherit decorative />Opening preview…</span>}<canvas ref={canvas} role="img" aria-label="Batch edit preview" hidden={!preview} /></div>
    <fieldset className="collage-controls" disabled={busy || optionsLocked}><legend className="visually-hidden">Batch settings</legend>
      <label className="field">Preset<select aria-label="Batch preset" data-autofocus value={preset} onChange={e => setPreset(e.target.value)}><option value="keep">Keep existing look</option>{FILTERS.map(filter => <option key={filter.id} value={filter.id}>{filter.label}</option>)}</select></label>
      <label className="field">Resize<select aria-label="Batch resize" value={maxSide} onChange={e => setMaxSide(Number(e.target.value))}><option value="0">Keep size · up to 16 MP</option>{[4096, 2400, 1600, 1080].map(size => <option key={size} value={size}>{size} px on longest side</option>)}</select></label>
      <label className="field">Watermark<input maxLength={90} value={watermark} onChange={e => setWatermark(e.target.value)} placeholder="Optional · © Your name" /></label>
      <label className="field">Watermark position<select aria-label="Batch watermark position" value={position} onChange={e => setPosition(e.target.value)}>{['bottom-right', 'bottom-left', 'top-right', 'top-left'].map(value => <option key={value} value={value}>{value.replace('-', ' ')}</option>)}</select></label>
      <label className="field">Opacity · {opacity}%<input aria-label="Batch watermark opacity" type="range" min="10" max="100" value={opacity} onChange={e => setOpacity(Number(e.target.value))} /></label>
      <label className="field">Save<select aria-label="Batch save mode" value={mode} onChange={e => setMode(e.target.value)}><option value="copy">Save new copies</option><option value="replace">Replace photos · keep version history</option></select></label>
    </fieldset>
    {optionsLocked && <p className="dialog-description">Settings stay fixed while retrying unfinished photos.</p>}
    <p role="status">{completed}/{items.length} photos saved{busy ? ' · Processing…' : ''}</p>
    <ul className="batch-jobs">{jobs.map(job => <li key={job.item.id}><span>{job.item.filename}</span><span className={job.status === 'failed' ? 'batch-failure' : ''}>{job.status === 'processing' ? <CameraSpinner size={16} inherit decorative /> : job.status === 'saved' ? <CheckCircle2 size={16} /> : null}{job.message || (job.status === 'pending' ? 'Waiting' : 'Processing…')}</span></li>)}</ul>
    {error && <p role="alert">{error}</p>}
    <div className="dialog-actions">{busy ? <button className="button secondary" onClick={() => { stopRequested.current = true }}>Stop after current photo</button> : <button className="button secondary" onClick={close}>{completed ? 'Done' : 'Cancel'}</button>}
      <button className="button primary" onClick={run} disabled={busy || !changed || completed === items.length}><SlidersHorizontal size={18} />{completed || jobs.some(job => job.status === 'failed') ? 'Retry unfinished photos' : 'Apply to ' + items.length + ' photos'}</button>
    </div>
  </Dialog>
}
