import { useEffect, useState } from 'react'
import { RotateCcw } from 'lucide-react'
import { api } from '../api.js'
import CameraSpinner from '../CameraSpinner.jsx'
import './collage.css'

export default function VersionHistory({ item, Dialog, close, onRestored }) {
  const [versions, setVersions] = useState(null)
  const [chosen, setChosen] = useState(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [compare, setCompare] = useState(false)
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    const abort = new AbortController()
    api('/api/albums/' + item.albumId + '/photos/' + item.id + '/versions', { signal: abort.signal }).then(rows => { setVersions(rows); setChosen(rows[0]) })
      .catch(e => { if (e.name !== 'AbortError') setError(e.message) })
    return () => abort.abort()
  }, [item, retry])
  async function restore(version) {
    setBusy(true); setError('')
    try {
      await api('/api/albums/' + item.albumId + '/photos/' + item.id + '/versions/' + version.version + '/restore', { method: 'POST' })
      await onRestored()
    } catch (e) { setError(e.message); setBusy(false) }
  }
  const current = versions?.find(version => version.current), earliest = versions?.at(-1)
  return <Dialog title="Photo edit history" description={item.filename + ' · Restore any saved version as a new edit.'} close={close} busy={busy} wide>
    {!versions && !error && <p role="status"><CameraSpinner size={24} inherit decorative />Loading history…</p>}
    {chosen && <>
      <div className="collage-preview history-preview"><img src={(compare ? current : chosen)?.url} alt={compare ? 'Current photo' : 'Selected version preview'} /></div>
      <button className="button secondary" aria-pressed={compare} onClick={() => setCompare(value => !value)} disabled={chosen.current}>{compare ? 'Show selected version' : 'Compare with current'}</button>
      <div className="version-list" role="group" aria-label="Saved photo versions">{versions.map(version => <button key={version.version} className={'version-row' + (version.version === chosen.version ? ' selected' : '')} aria-pressed={version.version === chosen.version} disabled={busy} onClick={() => { setChosen(version); setCompare(false) }}>
        <strong>{version.version === 1 ? 'Original' : 'Version ' + version.version}{version.current ? ' · Current' : ''}</strong><span>{new Date(version.changedAt).toLocaleString()} · {version.hasRecipe ? 'Editable settings saved' : 'Saved image'}</span>
      </button>)}</div>
      {versions.length === 1 && <p className="dialog-description">Future saved edits will appear here. You can reopen the photo editor to adjust the current version.</p>}
    </>}
    {error && <p role="alert">{error}{!versions && <button className="button secondary" onClick={() => { setError(''); setRetry(n => n + 1) }}>Try again</button>}</p>}
    <div className="dialog-actions"><button className="button secondary" onClick={close} disabled={busy}>Close</button>
      {earliest && !earliest.current && <button className="button secondary" onClick={() => restore(earliest)} disabled={busy}><RotateCcw size={16} />{earliest.version === 1 ? 'Restore original' : 'Restore earliest version'}</button>}
      <button className="button primary" onClick={() => restore(chosen)} disabled={busy || !chosen || chosen.current}>{busy ? <CameraSpinner size={18} inherit decorative /> : <RotateCcw size={18} />}Restore selected</button>
    </div>
  </Dialog>
}
