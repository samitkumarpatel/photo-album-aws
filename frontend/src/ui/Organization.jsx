import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { ArrowDown, ArrowUp, Download, GripVertical, Play, RotateCcw, Trash2 } from 'lucide-react'
import { api } from './api.js'
import { isVideo, mediaUrl } from './media.js'
import { moveItem, sortMedia } from './albumOrdering.js'
import { archiveFilename, buildArchive, saveArchive } from './downloadArchive.js'

const json = body => ({ method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
const keyOf = item => item.albumId + '/' + item.id

export function DownloadDialog({ items, name, Dialog, close }) {
  const controller = useRef(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [progress, setProgress] = useState(null)
  useEffect(() => () => controller.current?.abort(), [])
  async function start() {
    controller.current = new AbortController(); setBusy(true); setError(''); setProgress(null)
    try {
      const blob = await buildArchive(items, { signal: controller.current.signal, onProgress: setProgress })
      saveArchive(blob, archiveFilename(name)); close()
    } catch (e) { setError(e.name === 'AbortError' ? 'Download cancelled. No incomplete ZIP was saved.' : e.message) }
    finally { setBusy(false); controller.current = null }
  }
  return <Dialog title="Download ZIP" close={close} busy={busy}>
    <p>{items.length} original {items.length === 1 ? 'file' : 'files'} from {name}. Filenames and original quality are retained.</p>
    {progress && <div className="operation-progress"><p role="status">{progress.done} of {progress.total} downloaded · {progress.filename}</p><progress value={progress.done} max={progress.total} /></div>}
    {error && <p className="operation-error" role="alert">{error}</p>}
    <div className="dialog-actions">{busy ? <button className="button secondary" onClick={() => controller.current?.abort()}>Cancel download</button>
      : <><button className="button secondary" onClick={close}>Close</button><button className="button primary" onClick={start}><Download size={18} />Download ZIP</button></>}</div>
  </Dialog>
}

export function BulkDialog({ mode, items, albums, Dialog, close, onChanged }) {
  const [destination, setDestination] = useState(''), [busy, setBusy] = useState(false), [results, setResults] = useState({}), [error, setError] = useState('')
  const stop = useRef(false), mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; stop.current = true } }, [])
  const verb = mode === 'trash' ? 'Delete' : mode === 'move' ? 'Move' : 'Copy'
  const done = Object.values(results).filter(r => r.ok).length
  async function run() {
    setBusy(true); setError(''); stop.current = false
    try {
      for (const item of items) {
        if (stop.current) break
        const key = keyOf(item)
        if (results[key]?.ok) continue
        let result
        try {
          if (mode === 'trash') await api('/api/albums/' + item.albumId + '/photos/' + item.id + '/trash', { method: 'POST' })
          else if (item.albumId !== destination) await api('/api/albums/' + item.albumId + '/photos/' + item.id + '/transfer', json({ destinationAlbumId: destination, mode, version: item.version || 1 }))
          result = { ok: true, message: mode === 'trash' ? 'Moved to Recently Deleted' : item.albumId === destination ? 'Already in destination' : mode === 'move' ? 'Moved' : 'Copied' }
        } catch (e) { result = { ok: false, message: e.message } }
        if (mounted.current) setResults(previous => ({ ...previous, [key]: result }))
      }
      await onChanged()
    } catch (e) { if (mounted.current) setError(e.message) }
    finally { if (mounted.current) setBusy(false) }
  }
  return <Dialog title={verb + ' selected items'} close={close} busy={busy} wide>
    <p>{mode === 'trash' ? 'Items will stay in Recently Deleted for 30 days and can be restored.' : 'Originals, saved edits, versions and favorites are retained. Items already in the destination are skipped.'}</p>
    {mode !== 'trash' && <label className="field"><span>Destination album</span><select aria-label="Destination album" value={destination} onChange={e => setDestination(e.target.value)} disabled={busy || done > 0}>
      <option value="">Choose an album</option>{albums.map(album => <option key={album.id} value={album.id}>{album.name}</option>)}
    </select></label>}
    <p role="status">{done} of {items.length} completed{busy ? ' · Working…' : stop.current ? ' · Stopped' : ''}</p>
    <ul className="operation-list">{items.map(item => <li key={keyOf(item)}><strong>{item.filename}</strong><span className={results[keyOf(item)]?.ok === false ? 'operation-error' : ''}>{results[keyOf(item)]?.message || 'Waiting'}</span></li>)}</ul>
    {error && <p className="operation-error" role="alert">{error}</p>}
    <div className="dialog-actions">{busy ? <button className="button secondary" onClick={() => { stop.current = true }}>Stop after current item</button>
      : <><button className="button secondary" onClick={close}>Close</button>{done < items.length && <button className="button primary" disabled={mode !== 'trash' && !destination} onClick={run}>{Object.keys(results).length ? 'Retry unfinished items' : mode === 'trash' ? 'Move to Recently Deleted' : verb + ' items'}</button>}</>}</div>
  </Dialog>
}

export function OrderDialog({ items, order, albumId, Dialog, close, onSaved }) {
  const [list, setList] = useState(() => sortMedia(items, 'custom', order)), [busy, setBusy] = useState(false), [error, setError] = useState('')
  const initial = useRef(list.map(item => item.id).join(',')), dragging = useRef(null)
  const changed = list.map(item => item.id).join(',') !== initial.current
  const move = (from, to) => setList(previous => moveItem(previous, from, to))
  async function save() {
    setBusy(true); setError('')
    try { await api('/api/albums/' + albumId + '/order', { ...json({ photoIds: list.map(item => item.id) }), method: 'PUT' }); await onSaved() }
    catch (e) { setError(e.message) }
    finally { setBusy(false) }
  }
  return <Dialog title="Arrange photos" description="Drag to reorder, or use the up and down buttons. The saved order also applies to shared albums and slideshows." close={close} busy={busy} wide>
    <ol className="order-list">{list.map((item, index) => <li key={item.id} draggable={!busy} onDragStart={() => { dragging.current = item.id }} onDragEnd={() => { dragging.current = null }} onDragOver={e => e.preventDefault()}
      onDrop={e => { e.preventDefault(); if (!busy) move(list.findIndex(p => p.id === dragging.current), index); dragging.current = null }}>
      <GripVertical size={18} aria-hidden="true" />{isVideo(item) ? <span className="order-thumb"><Play size={18} /></span> : <img className="order-thumb" src={mediaUrl(item, 'thumbnail')} alt="" loading="lazy" />}
      <span><small>{index + 1}</small><strong>{item.filename}</strong></span>
      <button className="icon-button" aria-label={'Move ' + item.filename + ' up'} disabled={busy || index === 0} onClick={() => move(index, index - 1)}><ArrowUp size={18} /></button>
      <button className="icon-button" aria-label={'Move ' + item.filename + ' down'} disabled={busy || index === list.length - 1} onClick={() => move(index, index + 1)}><ArrowDown size={18} /></button>
    </li>)}</ol>
    {error && <p className="operation-error" role="alert">{error}</p>}
    <div className="dialog-actions"><button className="button secondary" disabled={busy} onClick={close}>Cancel</button><button className="button primary" disabled={busy || !changed} onClick={save}>{busy ? 'Saving…' : 'Save order'}</button></div>
  </Dialog>
}

export function TrashPage({ Dialog, Hero, ErrorNotice, onChanged, notify }) {
  const [entries, setEntries] = useState([]), [loading, setLoading] = useState(true), [error, setError] = useState(''), [busy, setBusy] = useState(false), [removing, setRemoving] = useState(null)
  const reload = async () => {
    setError('')
    try { setEntries(await api('/api/trash', { cache: 'no-store' })) }
    catch (e) { setError(e.message) }
    finally { setLoading(false) }
  }
  useEffect(() => { reload() }, [])
  async function act(entry, permanent = false) {
    setBusy(true); setError('')
    try {
      await api('/api/trash/' + entry.id + (permanent ? '' : '/restore'), { method: permanent ? 'DELETE' : 'POST' })
      setRemoving(null); await reload(); await onChanged(); notify(permanent ? 'Permanently deleted' : 'Restored to your albums')
    } catch (e) { setError(e.message) }
    finally { setBusy(false) }
  }
  return <main id="main" className="page">
    <Hero title="Recently Deleted" description="Restore deleted photos and albums within 30 days."><Link to="/" className="button secondary">Back to albums</Link></Hero>
    <ErrorNotice message={error} retry={reload} />
    {loading ? <p role="status">Loading deleted items…</p> : !entries.length && !error ? <p className="empty">Nothing in Recently Deleted.</p>
      : <ul className="trash-list">{entries.map(entry => <li key={entry.id}><Trash2 size={22} aria-hidden="true" /><div><strong>{entry.name}</strong><span>{entry.kind === 'album' ? entry.count + ' items · Album' : entry.albumName} · {Math.max(1, Math.ceil((Date.parse(entry.expiresAt) - Date.now()) / 86400000))} days left</span></div>
        <button className="button secondary" disabled={busy} onClick={() => act(entry)}><RotateCcw size={16} />Restore</button><button className="button danger" disabled={busy} onClick={() => setRemoving(entry)}>Delete permanently</button>
      </li>)}</ul>}
    {removing && <Dialog title="Delete permanently?" description={'“' + removing.name + '” and its saved versions will be removed permanently. This cannot be undone.'} close={() => { if (!busy) setRemoving(null) }} busy={busy}>
      {error && <p className="operation-error" role="alert">{error}</p>}<div className="dialog-actions"><button className="button secondary" disabled={busy} onClick={() => setRemoving(null)}>Cancel</button><button className="button danger" disabled={busy} onClick={() => act(removing, true)}>Delete permanently</button></div>
    </Dialog>}
  </main>
}
