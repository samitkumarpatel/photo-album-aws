import { Suspense, createContext, lazy, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { Link, Navigate, Route, Routes, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import CameraSpinner, { PageLoader } from './CameraSpinner.jsx'
import {
  ArrowDownToLine, ArrowDownWideNarrow, ArrowLeft, ArrowUpNarrowWide, Camera, Check, CheckCircle2, ChevronLeft, ChevronRight,
  Clock3, Copy, Ellipsis, FolderOpen, FolderPlus, Image, ImageOff, ImagePlus, Images, Info, Play, Plus, Search, Share2, LayoutGrid,
  Minimize2, Monitor, Moon, Pencil, RotateCcw, SlidersHorizontal, Star, Sun, Trash2, TriangleAlert, Upload, X, ZoomIn, ZoomOut,
} from 'lucide-react'
import './editor/collage.css'
import { api } from './api.js'
import { aspectStyle, downloadOriginal, isBroken, isVideo, isViewable, mediaUrl, needsPolling, statusOf, withStaged } from './media.js'
import { completeUpload, createUpload, discardUpload, intentExpired, multipartUpload, putFile } from './upload/uploads.js'

/* ---------- helpers ---------- */

const UI = createContext(null)
const useUI = () => useContext(UI)
const PhotoEditor = lazy(() => import('./editor/PhotoEditor.jsx'))
const BatchEditor = lazy(() => import('./editor/BatchEditor.jsx'))
const VersionHistory = lazy(() => import('./editor/VersionHistory.jsx'))
const AlbumPresentation = lazy(() => import('./editor/AlbumPresentation.jsx'))
const Slideshow = lazy(() => import('./editor/Slideshow.jsx'))
const CollageEditor = lazy(() => import('./editor/CollageEditor.jsx'))
const matches = (text, query) => text.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase())
const plural = (count, word) => count + ' ' + word + (count === 1 ? '' : 's')
const sizeLabel = size => size < 1024 ? size + ' B' : size < 1048576 ? (size / 1024).toFixed(1) + ' KB' : (size / 1048576).toFixed(1) + ' MB'
const units = [['HOURS', 'Hours'], ['DAYS', 'Days'], ['WEEKS', 'Weeks'], ['MONTHS', 'Months'], ['YEARS', 'Years']]
const MAX_BYTES = 100 * 1048576
const UPLOAD_CONCURRENCY = 3
const activeShares = album => (album?.shares || []).filter(link => new Date(link.expiresAt).getTime() > Date.now())
const pendingKey = items => items.filter(needsPolling).map(item => item.id).join(',')

function countLabel(items) {
  const videos = items.filter(isVideo).length
  const photos = items.length - videos
  if (!items.length) return 'No photos yet'
  return [photos && plural(photos, 'photo'), videos && plural(videos, 'video')].filter(Boolean).join(' · ')
}

function trapFocus(node, event) {
  if (event.key !== 'Tab') return
  const elements = Array.from(node.querySelectorAll('button:not([disabled]), a[href], input:not([disabled]):not([tabindex="-1"]), select:not([disabled]), textarea:not([disabled]), video[controls], [tabindex="0"]')).filter(element => element.getClientRects().length)
  if (!elements.length) { event.preventDefault(); node.focus(); return }
  const first = elements[0], last = elements[elements.length - 1]
  if (event.shiftKey && (document.activeElement === first || document.activeElement === node)) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && (document.activeElement === last || document.activeElement === node)) { event.preventDefault(); first.focus() }
}

/* ---------- theme ---------- */

const themes = [['light', 'Light', Sun], ['dark', 'Dark', Moon], ['system', 'System', Monitor]]

function readTheme() {
  try { const value = localStorage.getItem('theme'); return value === 'light' || value === 'dark' ? value : 'system' } catch { return 'system' }
}

function applyTheme(theme) {
  const root = document.documentElement
  if (theme === 'system') root.removeAttribute('data-theme')
  else root.setAttribute('data-theme', theme)
  const dark = theme === 'dark' || (theme === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches)
  document.querySelectorAll('meta[name="theme-color"]').forEach(meta => { meta.removeAttribute('media'); meta.content = dark ? '#121211' : '#f6f4ef' })
}

function useTheme() {
  const [theme, setTheme] = useState(readTheme)
  useEffect(() => {
    applyTheme(theme)
    try { theme === 'system' ? localStorage.removeItem('theme') : localStorage.setItem('theme', theme) } catch {}
    if (theme !== 'system') return
    const query = window.matchMedia('(prefers-color-scheme: dark)')
    const change = () => applyTheme('system')
    query.addEventListener('change', change)
    return () => query.removeEventListener('change', change)
  }, [theme])
  return [theme, setTheme]
}

/* ---------- polling ---------- */

// Calls poll every 2 s, backing off to 10 s, while `key` (the ids still processing) is non-empty and the page is visible.
function usePolling(key, poll) {
  const pollRef = useRef(poll)
  pollRef.current = poll
  useEffect(() => {
    if (!key) return
    let delay = 2000, timer = null, running = false, stopped = false
    const schedule = () => { timer = setTimeout(tick, delay); delay = Math.min(10000, delay * 1.5) }
    async function tick() {
      timer = null
      if (document.hidden || stopped) return
      running = true
      try { await pollRef.current() } catch {}
      running = false
      if (!stopped) schedule()
    }
    const visibility = () => { if (!document.hidden && !timer && !running && !stopped) { delay = 2000; tick() } }
    schedule()
    document.addEventListener('visibilitychange', visibility)
    return () => { stopped = true; clearTimeout(timer); document.removeEventListener('visibilitychange', visibility) }
  }, [key])
}

/* ---------- popover menu ---------- */

function Menu({ label, icon, items, align = 'right', className = 'icon-button' }) {
  const [open, setOpen] = useState(false)
  const root = useRef(null)
  const button = useRef(null)
  useEffect(() => {
    if (!open) return
    const outside = e => { if (!root.current?.contains(e.target)) setOpen(false) }
    const key = e => { if (e.key === 'Escape') { setOpen(false); button.current?.focus() } }
    document.addEventListener('pointerdown', outside)
    document.addEventListener('keydown', key)
    root.current.querySelector('[role^="menuitem"]')?.focus()
    return () => { document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', key) }
  }, [open])
  function arrows(e) {
    if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return
    e.preventDefault()
    const options = Array.from(root.current.querySelectorAll('[role^="menuitem"]'))
    const index = options.indexOf(document.activeElement)
    options[(index + (e.key === 'ArrowDown' ? 1 : -1) + options.length) % options.length]?.focus()
  }
  return <div className="menu" ref={root}>
    <button ref={button} className={className} aria-label={typeof label === 'string' ? label : undefined} title={typeof label === 'string' ? label : undefined} aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen(value => !value)}>{icon}</button>
    {open && <div className={'menu-popover ' + align} role="menu" onKeyDown={arrows}>
      {items.map(item => <button key={item.key} role={item.checked === undefined ? 'menuitem' : 'menuitemradio'} aria-checked={item.checked} className={(item.danger ? 'danger-item' : '') + (item.checked ? ' checked' : '')}
        onClick={() => { setOpen(false); item.onSelect() }}>
        {item.icon}<span>{item.label}</span>{item.checked && <Check size={16} className="menu-check" />}
      </button>)}
    </div>}
  </div>
}

function ThemeMenu() {
  const [theme, setTheme] = useTheme()
  const Icon = themes.find(([key]) => key === theme)[2]
  return <Menu label={'Theme: ' + theme} icon={<Icon size={20} />}
    items={themes.map(([key, label, ItemIcon]) => ({ key, label, icon: <ItemIcon size={18} />, checked: theme === key, onSelect: () => setTheme(key) }))} />
}

/* ---------- app shell ---------- */

function App() {
  const { pathname } = useLocation()
  const navigate = useNavigate()
  const shared = pathname.startsWith('/share/')
  const currentAlbumId = pathname.match(/^\/albums\/([^/]+)/)?.[1]
  const [albums, setAlbums] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  const [query, setQuery] = useState('')
  const [createOpen, setCreateOpen] = useState(false)
  const [creationItems, setCreationItems] = useState([])
  const [collageItems, setCollageItems] = useState(null)
  const [batchItems, setBatchItems] = useState(null)
  const [upload, setUpload] = useState(null)
  const [dropActive, setDropActive] = useState(false)
  const [toast, setToast] = useState(null)
  const toastTimer = useRef(null)
  // Photos uploaded in this session, shown as tiles before the server lists them: id -> {albumId, photo, preview, completed, at}
  const [staged, setStaged] = useState(() => new Map())
  const fetchedAt = useRef(0)

  const notify = useCallback(message => {
    clearTimeout(toastTimer.current)
    setToast(message)
    toastTimer.current = setTimeout(() => setToast(null), 4500)
  }, [])
  useEffect(() => () => clearTimeout(toastTimer.current), [])

  const refresh = useCallback(async () => {
    const startedAt = Date.now()
    try {
      const list = await api('/api/albums')
      fetchedAt.current = startedAt
      setAlbums(list)
      setError('')
      setRevision(value => value + 1)
    } catch (e) { setError(e.message) }
    finally { setLoading(false) }
  }, [])
  useEffect(() => { if (!shared) refresh() }, [refresh, shared])

  const stage = useCallback((albumId, photo, preview, completed = false) => setStaged(previous => new Map(previous).set(photo.id, { albumId, photo, preview, completed, at: Date.now() })), [])
  const unstage = useCallback(id => setStaged(previous => { if (!previous.has(id)) return previous; const next = new Map(previous); next.delete(id); return next }), [])
  // Forget finished uploads once the server lists them as ready (or no longer lists them), and free their previews.
  useEffect(() => {
    const server = new Map(albums.flatMap(album => (album.photos || []).map(photo => [photo.id, photo])))
    setStaged(previous => {
      let next = previous
      previous.forEach((entry, id) => {
        const photo = server.get(id)
        const settled = photo ? statusOf(photo) === 'READY' || statusOf(photo) === 'FAILED' : entry.at < fetchedAt.current
        if (!entry.completed || !settled) return
        if (next === previous) next = new Map(previous)
        next.delete(id)
        setTimeout(() => URL.revokeObjectURL(entry.preview), 1000)
      })
      return next
    })
  }, [albums])

  // Albums and Photos pages poll the library while something is processing; the album page polls its own album.
  const libraryPending = useMemo(() => shared || currentAlbumId ? '' : pendingKey(albums.flatMap(album => withStaged(album.photos || [], staged, album.id))), [albums, staged, shared, currentAlbumId])
  usePolling(libraryPending, refresh)

  useEffect(() => {
    window.scrollTo({ top: 0, behavior: 'instant' })
    const main = document.getElementById('main')
    if (main) { main.tabIndex = -1; main.focus({ preventScroll: true }) }
  }, [pathname])

  // Drop files anywhere on the page to upload them (into the open album when there is one).
  const dropState = useRef({})
  dropState.current = { blocked: shared || !!upload || createOpen || !!collageItems || !!batchItems, albumId: currentAlbumId }
  useEffect(() => {
    let depth = 0
    const hasFiles = e => Array.from(e.dataTransfer?.types || []).includes('Files')
    const enter = e => { if (!hasFiles(e) || dropState.current.blocked) return; depth++; setDropActive(true) }
    const leave = e => { if (!hasFiles(e)) return; depth = Math.max(0, depth - 1); if (!depth) setDropActive(false) }
    const over = e => { if (hasFiles(e)) e.preventDefault() }
    const drop = e => {
      if (!hasFiles(e)) return
      e.preventDefault()
      depth = 0
      setDropActive(false)
      if (dropState.current.blocked) return
      setUpload({ albumId: dropState.current.albumId, files: Array.from(e.dataTransfer.files) })
    }
    window.addEventListener('dragenter', enter)
    window.addEventListener('dragleave', leave)
    window.addEventListener('dragover', over)
    window.addEventListener('drop', drop)
    return () => {
      window.removeEventListener('dragenter', enter)
      window.removeEventListener('dragleave', leave)
      window.removeEventListener('dragover', over)
      window.removeEventListener('drop', drop)
    }
  }, [])

  const context = {
    albums, loading, error, query, setQuery, refresh, revision, notify, staged,
    createAlbum: (items = []) => { setCreationItems(Array.isArray(items) ? items : []); setCreateOpen(true) },
    createCollage: setCollageItems, batchEdit: setBatchItems,
    uploadMedia: albumId => setUpload({ albumId }),
  }

  if (shared) return <Routes><Route path="/share/:token" element={<SharedAlbumPage />} /></Routes>

  return <UI.Provider value={context}>
    <a className="skip-link" href="#main">Skip to content</a>
    <TopBar onUpload={() => setUpload({ albumId: currentAlbumId })} />
    <Routes>
      <Route path="/" element={<Library />} />
      <Route path="/albums" element={<Navigate replace to="/" />} />
      <Route path="/sharing" element={<SharingPage />} />
      <Route path="/photos" element={<PhotoLibrary />} />
      <Route path="/albums/:albumId" element={<AlbumPage />} />
      <Route path="*" element={<NotFound />} />
    </Routes>
    <nav className="bottom-nav" aria-label="Main navigation">
      <LibraryNav />
      <button className="fab" aria-label="Upload photos or videos" onClick={() => setUpload({ albumId: currentAlbumId })}><Plus size={26} /></button>
    </nav>
    {dropActive && <div className="drop-overlay" aria-hidden="true"><div><ImagePlus size={44} /><strong>Drop to upload</strong><span>Photos and videos up to 100 MB</span></div></div>}
    {createOpen && <AlbumFormDialog items={creationItems}
      close={() => setCreateOpen(false)}
      onSaved={async album => {
        setAlbums(previous => [album, ...previous]); setLoading(false); setCreateOpen(false); setQuery('')
        navigate('/albums/' + album.id)
        notify(creationItems.length ? 'Album created with ' + plural(creationItems.length, 'item') : 'Album created. Add your first photos.')
        setCreationItems([])
        await refresh()
      }} />}
    {upload && <UploadDialog
      albums={albums} albumId={upload.albumId} initialFiles={upload.files}
      close={() => setUpload(null)}
      onCreate={() => { setUpload(null); setCreationItems([]); setCreateOpen(true) }}
      stage={stage} unstage={unstage}
      onUploaded={async count => { notify(plural(count, 'item') + ' uploaded'); await refresh() }} />}
    {collageItems && <Suspense fallback={<div className="editor-loading"><CameraSpinner size={64} inherit label="Opening collage editor" /></div>}>
      <CollageEditor items={collageItems} albums={albums} Dialog={Dialog} close={() => setCollageItems(null)}
        onSaved={async () => { setCollageItems(null); notify('Collage saved to your album'); await refresh() }} />
    </Suspense>}
    {batchItems && <Suspense fallback={<PageLoader label="Opening batch editor…" />}><BatchEditor items={batchItems} Dialog={Dialog} close={() => setBatchItems(null)} onChanged={refresh} /></Suspense>}
    {toast && <div role="status" className="toast"><CheckCircle2 size={18} /><span>{toast}</span><button className="icon-button" onClick={() => setToast(null)} aria-label="Dismiss notification"><X size={16} /></button></div>}
  </UI.Provider>
}

function TopBar({ onUpload }) {
  const { query, setQuery } = useUI()
  const [open, setOpen] = useState(false)
  const input = useRef(null)
  const searching = open || !!query
  useEffect(() => {
    const key = e => {
      if (e.key !== '/' || e.target.closest?.('input, textarea, select, [contenteditable], dialog')) return
      e.preventDefault(); setOpen(true); requestAnimationFrame(() => input.current?.focus())
    }
    window.addEventListener('keydown', key)
    return () => window.removeEventListener('keydown', key)
  }, [])
  const close = () => { setQuery(''); setOpen(false) }
  return <header className={'topbar' + (searching ? ' searching' : '')}>
    <div className="topbar-inner">
      <Link to="/" className="brand" aria-label="Stillroom home"><span className="brand-mark"><Camera size={19} /></span><span>Stillroom</span></Link>
      <nav className="tabs" aria-label="Library sections"><LibraryNav /></nav>
      <div className="topbar-actions">
        {searching
          ? <div className="search-field">
              <Search size={18} aria-hidden="true" />
              <input ref={input} autoFocus type="search" value={query} onChange={e => setQuery(e.target.value)}
                onKeyDown={e => { if (e.key === 'Escape') close() }}
                aria-label="Search albums and filenames" placeholder="Search albums & files" />
              <button className="icon-button small" aria-label="Close search" onClick={close}><X size={18} /></button>
            </div>
          : <button className="icon-button" aria-label="Search (press /)" title="Search  /" onClick={() => setOpen(true)}><Search size={20} /></button>}
        <span className="theme-slot"><ThemeMenu /></span>
        <button className="button primary topbar-upload" onClick={onUpload}><Upload size={17} />Upload</button>
      </div>
    </div>
  </header>
}

function LibraryNav() {
  const { pathname } = useLocation()
  const photosActive = pathname === '/photos'
  const sharingActive = pathname === '/sharing'
  const albumsActive = pathname === '/' || pathname.startsWith('/albums')
  return <>
    <Link to="/" aria-current={albumsActive ? 'page' : undefined} className={'nav-item' + (albumsActive ? ' active' : '')}><FolderOpen size={20} /><span>Albums</span></Link>
    <Link to="/photos" aria-current={photosActive ? 'page' : undefined} className={'nav-item' + (photosActive ? ' active' : '')}><Images size={20} /><span>Photos</span></Link>
    <Link to="/sharing" aria-current={sharingActive ? 'page' : undefined} className={'nav-item' + (sharingActive ? ' active' : '')}><Share2 size={20} /><span>Sharing</span></Link>
  </>
}

/* ---------- shared building blocks ---------- */

function Hero({ title, description, cover, back, children }) {
  return <section className={'hero' + (cover ? ' has-cover' : '')} style={cover ? { '--cover': 'url("' + cover + '")' } : undefined}>
    {back}
    <h1>{title}</h1>
    {description && <p>{description}</p>}
    {children && <div className="hero-actions">{children}</div>}
  </section>
}

function AlbumHeader({ album, items, back, children }) {
  const cover = coverOf(items, album.presentation)
  return <header className="album-header">
    {back && <div className="album-breadcrumb">{back}</div>}
    <div className="album-heading">
      {cover && <img className="album-heading-cover" src={cover} alt="" />}
      <div className="album-heading-copy"><h1>{album.name}</h1>
        <div className="album-meta"><span>{countLabel(items)}</span>{album.shares != null && <SharingBadge album={album} />}</div>
        {album.description && <p className="album-description">{album.description}</p>}
      </div>
    </div>
    {children && <div className="album-actions">{children}</div>}
    {(album.presentation?.brandName || album.presentation?.logo) && <Branding presentation={album.presentation} />}
  </header>
}

function GalleryControls({ selection, filters, items }) {
  return <>
    <div className="gallery-controls">{selection && !selection.enabled && <SelectionToolbar selection={selection} visible={filters.filtered} />}<MediaToolbar filters={filters} items={items} /></div>
    {selection?.enabled && <SelectionToolbar selection={selection} visible={filters.filtered} />}
  </>
}

function ErrorNotice({ message, retry }) {
  if (!message) return null
  return <div className="notice" role="alert"><span>{message}</span>{retry && <button onClick={retry}>Try again</button>}</div>
}

function Empty({ icon: Icon = Images, title, description, children }) {
  return <div className="empty"><span className="empty-icon"><Icon size={34} /></span><h2>{title}</h2><p>{description}</p>{children}</div>
}

function Segmented({ label, value, onChange, options }) {
  return <div className="segmented" role="group" aria-label={label}>
    {options.map(([key, text]) => <button key={key} aria-pressed={value === key} className={value === key ? 'selected' : ''} onClick={() => onChange(key)}>{text}</button>)}
  </div>
}

/* ---------- albums ---------- */

function Library() {
  const { albums, loading, error, query, setQuery, refresh, createAlbum } = useUI()
  const [sort, setSort] = useState('recent')
  const filtered = albums
    .filter(album => matches(album.name + ' ' + (album.description || '') + ' ' + (album.photos || []).map(item => item.filename).join(' '), query))
    .sort((a, b) => sort === 'name' ? a.name.localeCompare(b.name) : new Date(b.createdAt) - new Date(a.createdAt))
  const total = albums.reduce((sum, album) => sum + (album.photos?.length || 0), 0)
  return <main id="main" className="page">
    <Hero title="Albums" description={loading ? 'Loading your library…' : query ? plural(filtered.length, 'album') + ' match “' + query.trim() + '”' : plural(albums.length, 'album') + ' · ' + plural(total, 'moment')}>
      <button className="button primary" onClick={createAlbum}><FolderPlus size={18} />New album</button>
    </Hero>
    {albums.length > 1 && <div className="toolbar"><Segmented label="Sort albums" value={sort} onChange={setSort} options={[['recent', 'Recent'], ['name', 'A–Z']]} /></div>}
    <ErrorNotice message={error} retry={refresh} />
    {loading ? <PageLoader label="Loading your albums…" />
      : filtered.length ? <div className="album-grid">
          {filtered.map((album, i) => <AlbumCard key={album.id} album={album} index={i} />)}
          {!query && <button className="album-card new-album" onClick={createAlbum} style={{ '--i': Math.min(filtered.length, 12) }}>
            <span className="album-stack"><span className="album-cover"><Plus size={30} /><span>New album</span></span></span>
            <span className="album-title">Start a collection</span>
            <span className="album-meta">Trips, people, everyday life</span>
          </button>}
        </div>
      : error ? null
      : <Empty icon={FolderOpen} title={query ? 'No albums found' : 'Your first album starts here'} description={query ? 'Try another name or clear your search.' : 'Create an album for a trip, a celebration, or everyday life.'}>
          {query ? <button className="button secondary" onClick={() => setQuery('')}>Clear search</button>
            : <button className="button primary" onClick={createAlbum}><FolderPlus size={18} />Create an album</button>}
        </Empty>}
  </main>
}

function useSharingClock() {
  const [, tick] = useState(0)
  useEffect(() => { const timer = setInterval(() => tick(n => n + 1), 1000); return () => clearInterval(timer) }, [])
}

function SharingBadge({ album }) {
  useSharingClock()
  const links = activeShares(album)
  return <span className={'sharing-badge' + (links.length ? ' shared' : '')}><Share2 size={13} />{album.shares == null ? 'Sharing status unavailable' : links.length ? 'Shared · ' + plural(links.length, 'active link') : 'Private'}</span>
}

function useMediaSelection(items) {
  const [enabled, setEnabled] = useState(false)
  const [ids, setIds] = useState(() => new Set())
  const { createAlbum, createCollage, batchEdit } = useUI()
  const ready = items.filter(item => statusOf(item) === 'READY')
  const chosen = ready.filter(item => ids.has(item.id))
  const select = id => { setEnabled(true); setIds(previous => { const next = new Set(previous); if (next.has(id)) next.delete(id); else if (next.size < 100) next.add(id); return next }) }
  const cancel = () => { setEnabled(false); setIds(new Set()) }
  return { enabled, setEnabled, ids, chosen, cancel, select,
    toggle: id => setIds(previous => { const next = new Set(previous); if (next.has(id)) next.delete(id); else if (next.size < 100) next.add(id); return next }),
    selectAll: visible => setIds(new Set(visible.filter(item => statusOf(item) === 'READY').slice(0, 100).map(item => item.id))),
    create: () => createAlbum(chosen),
    collage: () => createCollage(chosen), batch: () => batchEdit(chosen),
  }
}

function SelectionToolbar({ selection, visible }) {
  if (!selection.enabled) return visible.some(item => statusOf(item) === 'READY') && <div className="selection-entry"><button className="button secondary" onClick={() => selection.setEnabled(true)}><CheckCircle2 size={18} />Select</button></div>
  const canCollage = selection.chosen.length >= 2 && selection.chosen.length <= 9 && selection.chosen.every(item => !isVideo(item))
  return <div className="selection-toolbar is-active" role="region" aria-label="Media selection">
    <span role="status">{plural(selection.chosen.length, 'item')} selected · up to 100</span>
    <button className="button secondary" onClick={() => selection.selectAll(visible)}>Select visible</button>
    <button className="button secondary" onClick={selection.cancel}>Cancel selection</button>
    <button className="button primary" disabled={!selection.chosen.length} onClick={selection.create}><FolderPlus size={18} />Create album</button>
    <button className="button secondary" disabled={!canCollage} onClick={selection.collage} aria-describedby={canCollage ? undefined : 'collage-selection-hint'}><LayoutGrid size={18} />Create collage</button>
    <button className="button secondary" disabled={!selection.chosen.length || selection.chosen.some(isVideo)} onClick={selection.batch}><SlidersHorizontal size={18} />Batch edit photos</button>
    {selection.cover && <button className="button secondary" disabled={selection.chosen.length !== 1 || !canBeCover(selection.chosen[0])} onClick={() => selection.cover(selection.chosen[0])} title="Select one photo to use as the album cover"><Star size={18} />Set as cover</button>}
    {!canCollage && <small id="collage-selection-hint" className="selection-hint">For a collage, select 2–9 photos.</small>}
  </div>
}

function AlbumCard({ album, index }) {
  const photos = album.photos || []
  const cover = photos.find(item => item.id === album.presentation?.coverPhotoId && statusOf(item) === 'READY') || photos.find(item => statusOf(item) === 'READY') || photos.find(isViewable)
  return <Link to={'/albums/' + album.id} className={'album-card palette-' + index % 4} style={{ '--i': Math.min(index, 12) }}>
    <span className="album-stack">
      <span className="album-cover">
        {cover ? <Thumbnail item={{ ...cover, albumId: album.id }} />
          : photos.length ? <span className="album-placeholder"><CameraSpinner size={30} inherit decorative /><span>Processing</span></span>
          : <span className="album-placeholder"><ImagePlus size={30} /><span>Empty album</span></span>}
      </span>
    </span>
    <span className="album-title">{album.name}</span>
    <SharingBadge album={album} />
    <span className="album-meta">{photos.length ? plural(photos.length, 'item') : 'Created ' + new Date(album.createdAt).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}</span>
  </Link>
}

/* ---------- media ---------- */

function useMediaFilters(items, query) {
  const [type, setType] = useState('all')
  const [sort, setSort] = useState('newest')
  const filtered = useMemo(() => items
    .filter(item => matches(item.filename + ' ' + (item.albumName || ''), query) && (type === 'all' || (type === 'videos' ? isVideo(item) : !isVideo(item))))
    .sort((a, b) => sort === 'newest' ? new Date(b.uploadedAt) - new Date(a.uploadedAt) : new Date(a.uploadedAt) - new Date(b.uploadedAt)), [items, query, type, sort])
  return { type, setType, sort, setSort, filtered }
}

function MediaToolbar({ filters, items }) {
  const videos = items.filter(isVideo).length
  const mixed = videos > 0 && videos < items.length
  if (items.length < 2) return null
  const newest = filters.sort === 'newest'
  return <div className="toolbar">
    {mixed && <Segmented label="Filter media" value={filters.type} onChange={filters.setType} options={[['all', 'All'], ['photos', 'Photos'], ['videos', 'Videos']]} />}
    <button className="pill" onClick={() => filters.setSort(newest ? 'oldest' : 'newest')} aria-label={'Sorted ' + (newest ? 'newest' : 'oldest') + ' first. Change order'}>
      {newest ? <ArrowDownWideNarrow size={16} /> : <ArrowUpNarrowWide size={16} />}{newest ? 'Newest' : 'Oldest'}
    </button>
  </div>
}

function PhotoLibrary() {
  const { albums, loading, error, query, setQuery, refresh, uploadMedia, staged, notify } = useUI()
  const items = useMemo(() => albums.flatMap(album => withStaged(album.photos || [], staged, album.id).map(item => ({ ...item, albumId: album.id, albumName: album.name }))), [albums, staged])
  const filters = useMediaFilters(items, query)
  const selection = useMediaSelection(items)
  const viewable = useMemo(() => filters.filtered.filter(isViewable), [filters.filtered])
  const [params, setParams] = useSearchParams()
  const [deleting, setDeleting] = useState(null)
  const selected = items.find(item => item.id === params.get('media') && isViewable(item))
  const close = () => setParams(previous => { previous.delete('media'); return previous }, { replace: true })
  const photoEdit = usePhotoEditing(items, setParams)
  async function deleteMedia() {
    await api('/api/albums/' + deleting.albumId + '/photos/' + deleting.id, { method: 'DELETE' })
    setDeleting(null)
    notify('Item deleted')
    await refresh()
  }
  return <main id="main" className="page">
    <Hero title="Photos" description={loading ? 'Loading your library…' : query ? plural(filters.filtered.length, 'match') + ' for “' + query.trim() + '”' : countLabel(items)}>
      <button className="button primary" onClick={() => uploadMedia()}><Upload size={18} />Upload</button>
    </Hero>
    <GalleryControls selection={selection} filters={filters} items={items} />
    <ErrorNotice message={error} retry={refresh} />
    {loading ? <PageLoader label="Loading your photos…" />
      : filters.filtered.length ? <MediaGroups items={filters.filtered} onOpen={item => setParams({ media: item.id })} onDelete={setDeleting} selection={selection} />
      : error ? null
      : <Empty title={items.length ? 'Nothing matches' : 'Make room for your moments'} description={items.length ? 'Try a different filter or search.' : 'Upload photos and videos, or drop them anywhere on this page.'}>
          <button className="button primary" onClick={items.length ? () => { filters.setType('all'); setQuery('') } : () => uploadMedia()}>{items.length ? 'Reset filters' : <><Upload size={18} />Upload photos</>}</button>
        </Empty>}
    {selected && <Viewer items={viewable.some(item => item.id === selected.id) ? viewable : items.filter(isViewable)} current={selected} onChange={item => setParams({ media: item.id }, { replace: true })} close={close} onEdit={() => photoEdit.edit(selected)} onHistory={() => photoEdit.history(selected)} />}
    {photoEdit.editor}
    {deleting && <DeleteDialog item={deleting} close={() => setDeleting(null)} onDelete={deleteMedia} />}
  </main>
}

// Grids use the thumbnail; while a new upload is on its way the local preview stands in.
function Thumbnail({ item }) {
  const status = statusOf(item)
  const src = item.preview && status !== 'READY' ? item.preview
    : status === 'UPLOADING' ? null
    : mediaUrl(item, isVideo(item) ? 'original' : 'thumbnail')
  return <ThumbnailMedia key={src || 'none'} item={item} src={src} quiet={status !== 'READY'} />
}

function ThumbnailMedia({ item, src, quiet }) {
  const [failed, setFailed] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const [loadVideo, setLoadVideo] = useState(false)
  const videoRef = useRef(null)
  const imgRef = useRef(null)
  const video = isVideo(item)
  // Cached images can finish before React sees the load event.
  useEffect(() => { if (imgRef.current?.complete && imgRef.current.naturalWidth) setLoaded(true) }, [])
  useEffect(() => {
    if (!video || !videoRef.current) return
    if (!window.IntersectionObserver) { setLoadVideo(true); return }
    const observer = new IntersectionObserver(entries => { if (entries.some(entry => entry.isIntersecting)) { setLoadVideo(true); observer.disconnect() } }, { rootMargin: '200px' })
    observer.observe(videoRef.current)
    return () => observer.disconnect()
  }, [video])
  const aspect = aspectStyle(item)
  if (!src) return <span className="thumbnail-placeholder" style={aspect} />
  if (failed) return <span className="thumbnail-failed"><ImageOff size={24} /><span>Preview unavailable</span></span>
  const spinner = !loaded && !quiet && <CameraSpinner size={30} delay decorative className="tile-spinner" />
  if (video) return <>
    {spinner}
    <video ref={videoRef} className={loaded ? 'loaded' : ''} src={loadVideo ? src + '#t=0.1' : undefined} muted playsInline preload="metadata" onLoadedData={() => setLoaded(true)} onError={() => setFailed(true)} aria-label={item.filename} />
    <span className="video-badge"><Play size={12} fill="currentColor" />Video</span>
  </>
  return <>{spinner}<img ref={imgRef} className={(loaded ? 'loaded' : '') + (aspect ? ' sized' : '')} style={aspect} src={src} alt={item.filename} loading="lazy" decoding="async" onLoad={() => setLoaded(true)} onError={() => setFailed(true)} /></>
}

function MediaTile({ item, index, onOpen, onDelete, selection, isCover }) {
  const status = statusOf(item)
  const style = { '--i': Math.min(index, 12) }
  if (isBroken(item)) {
    const label = status === 'FAILED' ? 'Couldn’t process this file' : 'Upload didn’t finish'
    return <div className="media-tile broken" style={{ ...style, ...aspectStyle(item) }} role="group" aria-label={item.filename + ': ' + label}>
      <TriangleAlert size={24} aria-hidden="true" />
      <strong>{label}</strong>
      <span className="broken-name">{item.filename}</span>
      {onDelete && <button className="button secondary broken-delete" onClick={() => onDelete(item)}><Trash2 size={16} />Delete</button>}
    </div>
  }
  const pending = status === 'UPLOADING' || status === 'PROCESSING'
  const open = isViewable(item)
  const state = status === 'UPLOADING' ? 'Uploading' : 'Processing'
  const selected = selection?.ids.has(item.id)
  const selectable = !!selection && status === 'READY'
  const label = selection?.enabled ? (selected ? 'Deselect ' : 'Select ') + item.filename + (!selectable ? ' (not ready)' : '') : 'View ' + item.filename + (pending ? ' (' + state.toLowerCase() + ')' : '')
  return <div className={'media-tile' + (pending ? ' pending' : '') + (selected ? ' media-selected' : '') + (selection?.enabled ? ' selection-mode' : '')} style={style}>
    <button className="media-open" disabled={selection?.enabled ? !selectable : !open} aria-label={label} aria-pressed={selection?.enabled ? !!selected : undefined}
      onClick={() => { if (selection?.enabled) { if (selectable) selection.toggle(item.id) } else if (open) onOpen(item) }}>
      <Thumbnail item={item} />
      {pending && <span className="tile-status" aria-hidden="true"><CameraSpinner size={30} inherit decorative /><span>{state}…</span></span>}
      <span className="media-label">{item.filename}</span>
      {isCover && <span className="cover-badge"><Star size={12} fill="currentColor" aria-hidden="true" />Cover</span>}
    </button>
    {selectable && <button className="tile-select" role="checkbox" aria-checked={!!selected} aria-label={'Select ' + item.filename} onClick={() => selection.select(item.id)}>
      <span className="selection-check" aria-hidden="true">{selected && <Check size={18} />}</span>
    </button>}
  </div>
}

function MediaGroups({ items, onOpen, onDelete, selection, coverId }) {
  const groups = items.reduce((all, item) => {
    const date = new Date(item.uploadedAt).toLocaleDateString(undefined, { weekday: 'short', month: 'long', day: 'numeric', year: 'numeric' })
    ;(all[date] ||= []).push(item)
    return all
  }, {})
  return Object.entries(groups).map(([date, media]) => <section className="date-group" key={date}>
    <h2 className="group-heading"><span>{date}</span></h2>
    <JustifiedGallery items={media} render={(item, i) => <MediaTile key={item.id} item={item} index={i} onOpen={onOpen} onDelete={onDelete} selection={selection} isCover={item.id === coverId} />} />
  </section>)
}

// Shape used for layout; extreme panoramas and tall strips are clamped so one item can't swallow a row.
const tileAspect = item => {
  if (isBroken(item) || !(item.width > 0 && item.height > 0)) return 0.8
  return Math.min(2.4, Math.max(0.55, item.width / item.height))
}

// Breaks items into rows of equal height that exactly fill the measured width, keeping each item's shape.
function justifyRows(items, width, targetHeight, gap) {
  const rows = []
  let row = [], sum = 0
  for (const item of items) {
    const aspect = tileAspect(item)
    row.push([item, aspect]); sum += aspect
    if (sum * targetHeight + gap * (row.length - 1) >= width) {
      rows.push({ items: row, height: (width - gap * (row.length - 1)) / sum, full: true })
      row = []; sum = 0
    }
  }
  // The last row keeps the target height instead of stretching a few items across the page.
  if (row.length) rows.push({ items: row, height: Math.min(targetHeight, rows.at(-1)?.height ?? targetHeight), full: false })
  return rows
}

// Measure the gallery itself so split windows and embedded layouts get suitable row heights.
function JustifiedGallery({ items, render }) {
  const ref = useRef(null)
  const [width, setWidth] = useState(0)
  useEffect(() => {
    const observer = new ResizeObserver(([entry]) => setWidth(Math.floor(entry.contentRect.width)))
    observer.observe(ref.current)
    return () => observer.disconnect()
  }, [])
  const gap = width >= 900 ? 12 : width >= 600 ? 10 : 6
  const target = width >= 1100 ? 260 : width >= 820 ? 220 : width >= 560 ? 180 : 140
  let index = 0
  return <div ref={ref} className="justified" style={{ '--gap': gap + 'px' }}>
    {width > 0 && justifyRows(items, width, target, gap).map(row => <div className="justified-row" key={row.items[0][0].id} style={{ height: Math.round(row.height) + 'px' }}>
      {row.items.map(([item, aspect]) => <div className="justified-cell" key={item.id}
        style={row.full ? { flex: aspect + ' 1 0' } : { width: Math.round(aspect * row.height) + 'px', flex: 'none' }}>
        {render(item, index++)}
      </div>)}
    </div>)}
  </div>
}

/* ---------- photo editing ---------- */

// Holds the editor state for a page and moves the viewer to a saved copy once it appears in the list.
function usePhotoEditing(items, setParams) {
  const { notify, refresh } = useUI()
  const [editing, setEditing] = useState(null)
  const [history, setHistory] = useState(null)
  const [pending, setPending] = useState(null)
  useEffect(() => {
    if (pending && items.some(item => item.id === pending)) { setParams({ media: pending }, { replace: true }); setPending(null) }
  }, [items, pending, setParams])
  const editor = editing && <Suspense fallback={<div className="editor-loading"><CameraSpinner size={64} inherit label="Opening editor" /></div>}>
    <PhotoEditor item={editing} src={mediaUrl(editing, 'original')} onClose={() => setEditing(null)} onSaved={async (photo, mode) => {
      setEditing(null)
      notify(mode === 'copy' ? 'Saved as a new copy' : 'Photo updated')
      if (mode === 'copy') setPending(photo.id)
      await refresh()
    }} />
  </Suspense>
  return { edit: setEditing, history: setHistory, editor: <>{editor}{history && <Suspense fallback={<PageLoader label="Opening history…" />}><VersionHistory item={history} Dialog={Dialog} close={() => setHistory(null)} onRestored={async () => { setHistory(null); notify('Photo version restored'); await refresh() }} /></Suspense>}</> }
}

/* ---------- album page ---------- */

function useAlbum(path, revision = 0) {
  const [record, setRecord] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    setError('')
    api(path, { signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) { setRecord({ path, album: value }); setLoading(false) } })
      .catch(e => { if (e.name !== 'AbortError') { setError(e.message); setLoading(false) } })
    return () => controller.abort()
  }, [path, revision, retry])
  // Quiet refetch for polling: no loading state, keeps the current album on errors.
  const reload = useCallback(async () => {
    const value = await api(path)
    setRecord(previous => previous?.path === path ? { path, album: value } : previous)
  }, [path])
  const invalidate = useCallback(message => { setRecord(null); setError(message); setLoading(false) }, [])
  const album = record?.path === path ? record.album : null
  return { album, loading: loading || (!album && !error), error, reload, invalidate, retry: () => setRetry(value => value + 1) }
}

const canBeCover = item => !isVideo(item) && statusOf(item) === 'READY'
// The chosen cover, or the first ready photo when none is chosen (or the chosen one was removed).
const coverPhotoOf = (items, presentation) => items.find(item => item.id === presentation?.coverPhotoId && canBeCover(item)) || items.find(canBeCover)
function coverOf(items, presentation) {
  const photo = coverPhotoOf(items, presentation)
  return photo ? mediaUrl(photo, 'thumbnail') : undefined
}
const PRESENTATION_DEFAULTS = { theme: 'classic', coverPhotoId: null, logo: null, brandName: '', watermark: '', slideshowSeconds: 5 }

function Branding({ presentation }) { return presentation && <div className="album-brand">{presentation.logo && <img src={presentation.logo} alt="Brand logo" />}{presentation.brandName && <strong>{presentation.brandName}</strong>}{presentation.watermark && <span className="album-watermark">{presentation.watermark}</span>}</div> }

function AlbumPage() {
  const { albumId } = useParams()
  const { revision, query, setQuery, refresh, notify, uploadMedia, staged } = useUI()
  const state = useAlbum('/api/albums/' + albumId, revision)
  const [params, setParams] = useSearchParams()
  const [shareOpen, setShareOpen] = useState(false)
  const [presentationOpen, setPresentationOpen] = useState(false)
  const [slideshowOpen, setSlideshowOpen] = useState(false)
  const [deleting, setDeleting] = useState(null)
  const [editing, setEditing] = useState(false)
  const [removingAlbum, setRemovingAlbum] = useState(false)
  const navigate = useNavigate()
  const items = useMemo(() => withStaged(state.album?.photos || [], staged, albumId).map(item => ({ ...item, albumId, albumName: state.album?.name })), [state.album, staged, albumId])
  const filters = useMediaFilters(items, query)
  const coverId = coverPhotoOf(items, state.album?.presentation)?.id
  const setCover = async item => {
    try {
      await api('/api/albums/' + albumId + '/presentation', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...PRESENTATION_DEFAULTS, ...state.album.presentation, coverPhotoId: item.id }) })
      notify('Album cover updated'); await state.reload(); refresh()
    } catch (e) { notify(e.message || 'Couldn’t update the album cover.') }
  }
  const selection = { ...useMediaSelection(items), cover: async chosen => { await setCover(chosen); selection.cancel() } }
  const viewable = useMemo(() => filters.filtered.filter(isViewable), [filters.filtered])
  const selected = items.find(item => item.id === params.get('media') && isViewable(item))
  // Poll the album while photos are processing; refresh the library once they have all settled.
  const pending = pendingKey(items)
  usePolling(pending, state.reload)
  const hadPending = useRef(false)
  useEffect(() => {
    if (pending) hadPending.current = true
    else if (hadPending.current) { hadPending.current = false; refresh() }
  }, [pending, refresh])
  const closeViewer = () => setParams(previous => { previous.delete('media'); return previous }, { replace: true })
  const photoEdit = usePhotoEditing(items, setParams)
  async function deleteMedia() {
    await api('/api/albums/' + albumId + '/photos/' + deleting.id, { method: 'DELETE' })
    closeViewer()
    setDeleting(null)
    notify('Item deleted')
    await refresh()
  }
  async function deleteAlbum() {
    await api('/api/albums/' + albumId, { method: 'DELETE' })
    const name = state.album.name
    setRemovingAlbum(false)
    navigate('/', { replace: true })
    notify('“' + name + '” deleted')
    await refresh()
  }
  const back = <Link className="back" to="/"><ArrowLeft size={17} />All albums</Link>
  return <main id="main" className={'page album-page album-theme-' + (state.album?.presentation?.theme || 'classic')}>
    {state.loading ? <><section className="hero">{back}</section><PageLoader label="Opening album…" /></>
      : state.album ? <>
          <AlbumHeader album={state.album} items={items} back={back}>
            <button className="button primary" onClick={() => uploadMedia(albumId)}><ImagePlus size={18} />Add photos</button>
            <button className="button secondary" aria-label={activeShares(state.album).length ? "Manage album sharing" : "Share album"} onClick={() => setShareOpen(true)}><Share2 size={17} />Share</button>
            {!!items.filter(isViewable).length && <button className="button secondary album-slideshow" onClick={() => setSlideshowOpen(true)}><Play size={18} /><span>Slideshow</span></button>}
            <Menu label="Album options" className="button secondary round" align="right" icon={<Ellipsis size={20} />} items={[
              ...(items.some(isViewable) ? [{ key: 'slideshow', label: 'Slideshow', icon: <Play size={18} />, onSelect: () => setSlideshowOpen(true) }] : []),
              { key: 'presentation', label: 'Album presentation', icon: <Image size={18} />, onSelect: () => setPresentationOpen(true) },
              { key: 'rename', label: 'Rename', icon: <Pencil size={18} />, onSelect: () => setEditing(true) },
              { key: 'delete', label: 'Delete album', icon: <Trash2 size={18} />, danger: true, onSelect: () => setRemovingAlbum(true) },
            ]} />
          </AlbumHeader>
          <ErrorNotice message={state.error} retry={state.retry} />
          <GalleryControls selection={selection} filters={filters} items={items} />
          {filters.filtered.length ? <MediaGroups items={filters.filtered} onOpen={item => setParams({ media: item.id })} onDelete={setDeleting} selection={selection} coverId={coverId} />
            : items.length ? <Empty icon={Search} title="Nothing matches" description="Try a different search or filter."><button className="button secondary" onClick={() => { setQuery(''); filters.setType('all') }}>Reset filters</button></Empty>
            : <button className="drop-hint" onClick={() => uploadMedia(albumId)}><ImagePlus size={34} /><strong>Add the first photos</strong><span>Tap to choose, or drag files anywhere on this page</span></button>}
        </>
      : <><Hero back={back} title="Album unavailable" /><ErrorNotice message={state.error} retry={state.retry} /></>}
    {selected && <Viewer items={viewable.some(item => item.id === selected.id) ? viewable : items.filter(isViewable)} current={selected} onChange={item => setParams({ media: item.id }, { replace: true })} close={closeViewer} onDelete={() => setDeleting(selected)} onEdit={() => photoEdit.edit(selected)} onHistory={() => photoEdit.history(selected)}
      onCover={canBeCover(selected) ? () => setCover(selected) : undefined} isCover={selected.id === coverId} />}
    {photoEdit.editor}
    {deleting && <DeleteDialog item={deleting} close={() => setDeleting(null)} onDelete={deleteMedia} />}
    {presentationOpen && <Suspense fallback={<PageLoader label="Opening presentation settings…" />}><AlbumPresentation album={state.album} items={items} Dialog={Dialog} close={() => setPresentationOpen(false)} onSaved={async () => { setPresentationOpen(false); notify('Album presentation saved'); await refresh() }} /></Suspense>}
    {slideshowOpen && <Suspense fallback={<PageLoader label="Opening slideshow…" />}><Slideshow items={items.filter(isViewable)} presentation={state.album.presentation} Dialog={Dialog} close={() => setSlideshowOpen(false)} /></Suspense>}
    {shareOpen && <ShareDialog album={state.album} close={() => setShareOpen(false)} notify={notify} onChanged={refresh} />}
    {editing && <AlbumFormDialog album={state.album} close={() => setEditing(false)} onSaved={async () => { setEditing(false); notify('Album updated'); await refresh() }} />}
    {removingAlbum && <DeleteAlbumDialog album={state.album} count={items.length} close={() => setRemovingAlbum(false)} onDelete={deleteAlbum} />}
  </main>
}

/* ---------- dialogs ---------- */

function Dialog({ title, description, close, busy = false, children, wide = false }) {
  const ref = useRef(null)
  useEffect(() => {
    const previous = document.activeElement
    const node = ref.current
    const overflow = document.documentElement.style.overflow
    document.documentElement.style.overflow = 'hidden'
    node.showModal()
    node.querySelector('[data-autofocus]')?.focus()
    // When the on-screen keyboard shrinks the viewport, bring the field being typed in back into view.
    const reveal = () => { const active = document.activeElement; if (node.contains(active) && active.matches('input, textarea, select')) requestAnimationFrame(() => active.scrollIntoView({ block: 'nearest' })) }
    const viewport = window.visualViewport || window
    viewport.addEventListener('resize', reveal)
    return () => { viewport.removeEventListener('resize', reveal); node.close(); document.documentElement.style.overflow = overflow; if (previous?.isConnected) previous.focus() }
  }, [])
  return <dialog ref={ref} tabIndex={-1} className={'dialog' + (wide ? ' dialog-wide' : '')} aria-label={title}
    onKeyDown={e => trapFocus(ref.current, e)}
    onCancel={e => { e.preventDefault(); if (!busy) close() }}
    onClick={e => { if (e.target === e.currentTarget && !busy) close() }}>
    <div className="dialog-body" onFocusCapture={event => {
      const body = event.currentTarget, preview = body.querySelector('.collage-preview:has(canvas)')
      if (!preview || preview.contains(event.target)) return
      const target = event.target.closest('label') || event.target
      if (!(preview.compareDocumentPosition(target) & Node.DOCUMENT_POSITION_FOLLOWING)) return
      requestAnimationFrame(() => {
        if (!target.isConnected) return
        const bounds = target.getBoundingClientRect(), image = preview.getBoundingClientRect()
        const bottom = Math.min(body.getBoundingClientRect().bottom, window.visualViewport?.height || window.innerHeight) - 12
        if (bounds.top < image.bottom + 12) body.scrollTop -= image.bottom + 12 - bounds.top
        else if (bounds.bottom > bottom) body.scrollTop += bounds.bottom - bottom
      })
    }}>
      <span className="sheet-handle" aria-hidden="true" />
      <div className="dialog-heading"><h2>{title}</h2><button className="icon-button" aria-label={'Close ' + title} onClick={close} disabled={busy}><X size={20} /></button></div>
      {description && <p className="dialog-description">{description}</p>}
      {children}
    </div>
  </dialog>
}

function AlbumFormDialog({ album, items = [], close, onSaved }) {
  const editing = !!album
  const [name, setName] = useState(album?.name || '')
  const [description, setDescription] = useState(album?.description || '')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const unchanged = editing && name.trim() === album.name && description.trim() === (album.description || '')
  async function submit(e) {
    e.preventDefault()
    if (!name.trim() || unchanged) return
    setBusy(true); setError('')
    try {
      const body = JSON.stringify({ name: name.trim(), description: description.trim(), ...(items.length ? { items: items.map(item => ({ albumId: item.albumId, photoId: item.id })) } : {}) })
      const headers = { 'Content-Type': 'application/json' }
      await onSaved(await api(editing ? '/api/albums/' + album.id : items.length ? '/api/albums/from-selection' : '/api/albums', { method: editing ? 'PATCH' : 'POST', headers, body }))
    }
    catch (e) { setError(e.message) }
    finally { setBusy(false) }
  }
  return <Dialog title={editing ? 'Rename album' : 'New album'} description={editing ? undefined : items.length ? countLabel(items) + ' will be copied into a new private album. Share it whenever you’re ready.' : 'Give your photos a place to belong.'} close={close} busy={busy}>
    <form onSubmit={submit}>
      <label className="field">Name<input data-autofocus required maxLength={90} value={name} onChange={e => setName(e.target.value)} onFocus={e => editing && e.target.select()} placeholder="Summer by the sea" /></label>
      <label className="field">Description <span>Optional</span><textarea rows={2} maxLength={240} value={description} onChange={e => setDescription(e.target.value)} placeholder="A little note about this collection" /></label>
      <ErrorNotice message={error} />
      <div className="dialog-actions">
        <button type="button" className="button secondary" onClick={close} disabled={busy}>Cancel</button>
        <button className="button primary" disabled={busy || !name.trim() || unchanged}>
          {busy ? <CameraSpinner size={20} inherit decorative /> : editing ? <Check size={18} /> : <FolderPlus size={18} />}
          {busy ? (editing ? 'Saving…' : 'Creating…') : editing ? 'Save' : 'Create album'}
        </button>
      </div>
    </form>
  </Dialog>
}

function DeleteAlbumDialog({ album, count, close, onDelete }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function remove() { setBusy(true); setError(''); try { await onDelete() } catch (e) { setError(e.message); setBusy(false) } }
  return <Dialog title="Delete this album?" close={close} busy={busy}
    description={'“' + album.name + '”' + (count ? ' and its ' + plural(count, 'item') : '') + ' will be permanently deleted. Any share links will stop working.'}>
    <ErrorNotice message={error} />
    <div className="dialog-actions">
      <button data-autofocus className="button secondary" onClick={close} disabled={busy}>Keep album</button>
      <button className="button danger" onClick={remove} disabled={busy}>{busy ? <CameraSpinner size={20} inherit decorative /> : <Trash2 size={18} />}{busy ? 'Deleting…' : 'Delete album'}</button>
    </div>
  </Dialog>
}

const ACTIVE_UPLOAD = new Set(['queued', 'preparing', 'uploading', 'finishing'])
const tileClass = { pending: 'pending', queued: 'pending', preparing: 'uploading', uploading: 'uploading', finishing: 'uploading', done: 'done', error: 'error', canceled: 'canceled' }

/*
 * Uploads run UPLOAD_CONCURRENCY at a time: presigned PUT straight to storage with per-file progress, or the older
 * multipart endpoint when the backend has no presigned flow. Each new photo is "staged" in the app so its tile shows
 * in the album right away, and per-file jobs remember the upload intent so a retry can resume where it failed.
 */
function UploadDialog({ albums, albumId, initialFiles, close, onCreate, onUploaded, stage, unstage }) {
  const [selected, setSelected] = useState(albumId || albums[0]?.id || '')
  const [queue, setQueue] = useState([])
  const [dragging, setDragging] = useState(false)
  const [validation, setValidation] = useState('')
  const input = useRef(null)
  const entries = useRef(queue)
  entries.current = queue
  const selectedRef = useRef(selected)
  selectedRef.current = selected
  const jobs = useRef(new Map())   // entry id -> { controller, intent, sent, legacy, albumId, completed }
  const waiting = useRef([])
  const running = useRef(0)
  const succeeded = useRef(0)
  const live = useRef(true)
  const busy = queue.some(entry => ACTIVE_UPLOAD.has(entry.status))

  useEffect(() => {
    live.current = true
    return () => {
      live.current = false
      // Deferred so React's development double-mount doesn't tear down a dialog that stays open.
      setTimeout(() => {
        if (live.current) return
        waiting.current = []
        jobs.current.forEach(job => {
          job.controller?.abort()
          if (job.intent && !job.completed) { discardUpload(job.albumId, job.intent.photoId); unstage(job.intent.photoId) }
        })
        // Previews of finished uploads now belong to their album tiles; the app frees them once processed.
        entries.current.forEach(entry => { if (!jobs.current.get(entry.id)?.completed) URL.revokeObjectURL(entry.preview) })
      })
    }
  }, [unstage])
  useEffect(() => { if (!selected && albums[0]) setSelected(albums[0].id) }, [albums, selected])

  function addFiles(files) {
    if (busy) return
    const rejected = []
    const keys = new Set(entries.current.map(entry => entry.key))
    const fresh = Array.from(files).filter(file => {
      if (!/^image\//.test(file.type) && !/^video\//.test(file.type)) { rejected.push(file.name + ' is not a photo or video.'); return false }
      if (!file.size || file.size > MAX_BYTES) { rejected.push(file.name + ' is larger than 100 MB.'); return false }
      const key = file.name + file.size + file.lastModified
      if (keys.has(key)) return false
      keys.add(key)
      return true
    })
    setValidation(rejected.join(' '))
    if (!fresh.length) return
    const added = fresh.map(file => ({ id: crypto.randomUUID(), key: file.name + file.size + file.lastModified, file, preview: URL.createObjectURL(file), status: 'pending', progress: 0 }))
    entries.current = [...entries.current, ...added]
    setQueue(previous => [...previous, ...added])
  }
  const initial = useRef(initialFiles)
  useEffect(() => { if (initial.current?.length) { addFiles(initial.current); initial.current = null } }, [])

  const update = (id, patch) => setQueue(previous => previous.map(item => item.id === id ? { ...item, ...patch } : item))

  function forget(job) {
    if (job?.intent && !job.completed) { discardUpload(job.albumId, job.intent.photoId); unstage(job.intent.photoId) }
    if (job) { job.intent = null; job.sent = false }
  }
  function remove(entry) {
    forget(jobs.current.get(entry.id))
    jobs.current.delete(entry.id)
    URL.revokeObjectURL(entry.preview)
    setQueue(previous => previous.filter(item => item.id !== entry.id))
  }
  function cancel(entry) {
    if (waiting.current.includes(entry.id)) { waiting.current = waiting.current.filter(id => id !== entry.id); update(entry.id, { status: 'canceled' }) }
    else jobs.current.get(entry.id)?.controller?.abort()
  }
  function cancelAll() {
    waiting.current.forEach(id => update(id, { status: 'canceled' }))
    waiting.current = []
    jobs.current.forEach(job => job.controller?.abort())
  }

  function enqueue(ids) {
    if (!selectedRef.current || !ids.length) return
    setValidation('')
    ids.forEach(id => update(id, { status: 'queued', progress: 0, error: '' }))
    waiting.current.push(...ids.filter(id => !waiting.current.includes(id)))
    pump()
  }
  function pump() {
    while (live.current && running.current < UPLOAD_CONCURRENCY && waiting.current.length) {
      const id = waiting.current.shift()
      running.current++
      run(id).finally(() => {
        running.current--
        if (!live.current) return
        pump()
        if (!running.current && !waiting.current.length && succeeded.current) { const count = succeeded.current; succeeded.current = 0; onUploaded(count) }
      })
    }
  }

  async function run(id) {
    const entry = entries.current.find(item => item.id === id)
    if (!entry) return
    const albumId = selectedRef.current
    const job = jobs.current.get(id) || {}
    jobs.current.set(id, job)
    if (job.intent && (job.albumId !== albumId || intentExpired(job.intent))) forget(job)
    job.albumId = albumId
    job.controller = new AbortController()
    const { signal } = job.controller
    const { file, preview } = entry
    const placeholder = { filename: file.name, contentType: file.type, size: file.size, uploadedAt: new Date().toISOString(), status: 'UPLOADING' }
    let phase = 'preparing', tileId = null, last = -1
    const progress = fraction => { const value = Math.min(99, Math.round(fraction * 100)); if (value !== last) { last = value; update(id, { status: 'uploading', progress: value }) } }
    try {
      let photo
      if (!job.intent && !job.legacy) {
        update(id, { status: 'preparing' })
        job.intent = await createUpload(albumId, file, signal)
        job.legacy = !job.intent
      }
      tileId = job.legacy ? 'local-' + id : job.intent.photoId
      stage(albumId, { ...placeholder, id: tileId }, preview)
      phase = 'uploading'
      if (job.legacy) {
        update(id, { status: 'uploading', progress: 0 })
        photo = await multipartUpload(albumId, file, progress, signal)
      } else {
        if (!job.sent) { update(id, { status: 'uploading', progress: 0 }); await putFile(job.intent, file, progress, signal); job.sent = true }
        phase = 'finishing'
        update(id, { status: 'finishing', progress: 100 })
        photo = await completeUpload(albumId, job.intent.photoId, signal)
      }
      job.completed = true
      if (tileId !== photo.id) unstage(tileId)
      stage(albumId, photo, preview, true)
      update(id, { status: 'done', progress: 100, error: '' })
      succeeded.current++
    } catch (e) {
      if (tileId && !job.completed) unstage(tileId)
      // A rejected or expired upload link, or a refused completion, needs a fresh intent on retry.
      if (phase !== 'preparing' && e.status >= 400 && e.status < 500) forget(job)
      if (e.name === 'AbortError') update(id, { status: 'canceled', error: '' })
      else update(id, { status: 'error', error: e.message })
    } finally { job.controller = null }
  }

  const done = queue.filter(item => item.status === 'done')
  const retryable = queue.filter(item => item.status === 'pending' || item.status === 'error' || item.status === 'canceled')
  const failed = queue.filter(item => item.status === 'error')
  const counted = queue.filter(item => ACTIVE_UPLOAD.has(item.status) || item.status === 'done')
  const totalBytes = counted.reduce((sum, item) => sum + item.file.size, 0)
  const sentBytes = counted.reduce((sum, item) => sum + item.file.size * (item.status === 'done' ? 1 : item.progress / 100), 0)
  const overall = totalBytes ? Math.round(sentBytes / totalBytes * 100) : 0
  const allDone = queue.length > 0 && done.length === queue.length
  const locked = busy || queue.some(item => jobs.current.get(item.id)?.intent || item.status === 'done')

  return <Dialog title="Upload" close={close} busy={busy} wide>
    {!albums.length ? <Empty icon={FolderPlus} title="Create an album first" description="Albums keep your photos and videos together.">
        <button className="button primary" onClick={onCreate}><FolderPlus size={18} />Create an album</button>
      </Empty> : <>
      <label className="field">Album
        <select data-autofocus value={selected} onChange={e => setSelected(e.target.value)} disabled={locked}>
          {albums.map(album => <option value={album.id} key={album.id}>{album.name}</option>)}
        </select>
      </label>
      <button type="button" className={'drop-zone' + (dragging ? ' dragging' : '') + (queue.length ? ' compact' : '')} disabled={busy}
        onClick={() => input.current.click()}
        onDragOver={e => { e.preventDefault(); e.stopPropagation(); if (!busy) setDragging(true) }}
        onDragLeave={() => setDragging(false)}
        onDrop={e => { e.preventDefault(); e.stopPropagation(); setDragging(false); addFiles(e.dataTransfer.files) }}>
        <ImagePlus size={queue.length ? 22 : 34} />
        <strong>{queue.length ? 'Add more' : 'Choose photos & videos'}</strong>
        {!queue.length && <span>Tap to browse or drop files here · up to 100 MB each</span>}
      </button>
      <input ref={input} className="visually-hidden" tabIndex={-1} type="file" accept="image/*,video/*" multiple onChange={e => { addFiles(e.target.files); e.target.value = '' }} aria-label="Choose photos and videos" />
      <ErrorNotice message={validation} />
      {!!queue.length && <>
        <div className="upload-progress">
          <div className="queue-status" role="status">
            {busy ? 'Uploading ' + done.length + ' of ' + counted.length + ' · ' + overall + '%'
              : allDone ? 'All ' + plural(queue.length, 'item') + ' uploaded'
              : failed.length ? plural(failed.length, 'item') + ' failed' + (done.length ? ' · ' + done.length + ' uploaded' : '')
              : plural(retryable.length, 'item') + ' ready'}
          </div>
          {(busy || done.length > 0) && <div className="progress-track" role="progressbar" aria-label="Overall upload progress" aria-valuemin={0} aria-valuemax={100} aria-valuenow={overall}><span style={{ '--p': overall }} /></div>}
        </div>
        <ul className="upload-grid">
          {queue.map(entry => {
            const active = ACTIVE_UPLOAD.has(entry.status)
            const name = entry.file.name
            return <li key={entry.id} className={'upload-item ' + tileClass[entry.status]} title={entry.error || name}>
              {entry.file.type.startsWith('video/')
                ? <video src={entry.preview + '#t=0.1'} muted playsInline preload="metadata" aria-hidden="true" />
                : <img src={entry.preview} alt="" />}
              <span className="visually-hidden">{name}, {sizeLabel(entry.file.size)}{entry.error ? ', failed: ' + entry.error : entry.status === 'done' ? ', uploaded' : ''}</span>
              {active && <span className="item-label" aria-hidden="true">
                {entry.status === 'queued' ? 'Waiting' : entry.status === 'uploading' ? entry.progress + '%' : <CameraSpinner size={24} inherit decorative />}
              </span>}
              {active && <span className="item-bar" role="progressbar" aria-label={'Uploading ' + name} aria-valuemin={0} aria-valuemax={100} aria-valuenow={entry.progress}><span style={{ '--p': entry.progress }} /></span>}
              {active && <button className="remove" aria-label={'Cancel ' + name} title="Cancel" onClick={() => cancel(entry)}><X size={14} strokeWidth={3} /></button>}
              {entry.status === 'done' && <span className="done-badge" aria-label="Uploaded"><Check size={18} strokeWidth={3} /></span>}
              {(entry.status === 'error' || entry.status === 'canceled') && <>
                <span className={'error-badge' + (entry.status === 'canceled' ? ' neutral' : '')}>{entry.status === 'error' ? 'Failed' : 'Canceled'}</span>
                <button className="item-retry" aria-label={'Retry ' + name} title="Retry" onClick={() => enqueue([entry.id])}><RotateCcw size={18} /></button>
              </>}
              {(entry.status === 'pending' || entry.status === 'error' || entry.status === 'canceled') && <button className="remove" aria-label={'Remove ' + name} title="Remove" onClick={() => remove(entry)}><X size={14} strokeWidth={3} /></button>}
            </li>
          })}
        </ul>
        {!!failed.length && <ErrorNotice message={failed.map(entry => entry.file.name + ': ' + entry.error).join(' ')} />}
      </>}
      <div className="dialog-actions">
        {busy ? <button className="button secondary" onClick={cancelAll}>Cancel uploads</button>
          : <button className="button secondary" onClick={close}>{done.length ? 'Done' : 'Cancel'}</button>}
        {!allDone && <button className="button primary" onClick={() => enqueue(retryable.map(entry => entry.id))} disabled={busy || !retryable.length || !selected}>
          {busy ? <CameraSpinner size={20} inherit decorative /> : failed.length ? <RotateCcw size={18} /> : <Upload size={18} />}
          {busy ? 'Uploading…' : failed.length ? 'Retry' + (retryable.length > 1 ? ' all' : '') : 'Upload' + (retryable.length ? ' ' + plural(retryable.length, 'item') : '')}
        </button>}
      </div>
    </>}
  </Dialog>
}

function DeleteDialog({ item, close, onDelete }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function remove() { setBusy(true); setError(''); try { await onDelete() } catch (e) { setError(e.message); setBusy(false) } }
  return <Dialog title="Delete this item?" description={'“' + item.filename + '” will be permanently removed from this album and its shared links.'} close={close} busy={busy}>
    <ErrorNotice message={error} />
    <div className="dialog-actions">
      <button data-autofocus className="button secondary" onClick={close} disabled={busy}>Keep it</button>
      <button className="button danger" onClick={remove} disabled={busy}>{busy ? <CameraSpinner size={20} inherit decorative /> : <Trash2 size={18} />}{busy ? 'Deleting…' : 'Delete'}</button>
    </div>
  </Dialog>
}

const presets = [['HOURS', '1 hour'], ['DAYS', '1 day'], ['WEEKS', '1 week'], ['MONTHS', '1 month']]

function SharingPage() {
  const { albums, refresh, notify, loading } = useUI()
  const [managing, setManaging] = useState(null)
  useSharingClock()
  const shared = albums.filter(a => activeShares(a).length)
  return <main id="main" className="page"><Hero title="Sharing" description="See active album links and manage their expiry or revoke access." />
    {loading ? <PageLoader label="Loading sharing…" /> : !shared.length ? <Empty icon={Share2} title="No active share links" description="Your albums are private. Open an album to create a link." /> : shared.map(a => <section className="album-sharing" key={a.id}><Link to={'/albums/' + a.id}>{a.name}</Link><SharingBadge album={a} /><button className="button secondary" onClick={() => setManaging(a.id)}>Manage links</button></section>)}
    {managing && albums.find(a => a.id === managing) && <ShareDialog album={albums.find(a => a.id === managing)} close={() => setManaging(null)} notify={notify} onChanged={refresh} />}
  </main>
}

function ShareLinkSettings({ link, album, onSaved }) {
  const localDate = iso => { const d = new Date(iso); return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0,16) }
  const [expiry, setExpiry] = useState(localDate(link.expiresAt)), [label, setLabel] = useState(link.label || '')
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  async function save(e) {
    e.preventDefault(); setBusy(true); setError('')
    try { await api('/api/albums/' + album.id + '/shares/' + link.token, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ expiresAt: new Date(expiry).toISOString(), label }) }); await onSaved() }
    catch (e) { setError(e.message) } finally { setBusy(false) }
  }
  return <details><summary>Edit expiry & label</summary><form onSubmit={save}><label className="field">Label<input maxLength={90} value={label} onChange={e => setLabel(e.target.value)} /></label><label className="field">Expires at<input required type="datetime-local" value={expiry} onChange={e => setExpiry(e.target.value)} /></label>{error && <p role="alert">{error}</p>}<button className="button secondary" disabled={busy}>Save link settings</button></form></details>
}

function ShareDialog({ album, close, notify, onChanged }) {
  useSharingClock()
  const [label, setLabel] = useState('')
  const [amount, setAmount] = useState(1)
  const [unit, setUnit] = useState('WEEKS')
  const [custom, setCustom] = useState(false)
  const [share, setShare] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [copied, setCopied] = useState(false)
  const [links, setLinks] = useState(null)
  const [revoking, setRevoking] = useState(null)
  const linkInput = useRef(null)
  const visibleLinks = (links || []).filter(l => new Date(l.expiresAt).getTime() > Date.now())
  const link = share ? location.origin + '/share/' + share.token : ''
  const loadLinks = useCallback(async () => {
    try { setLinks(await api('/api/albums/' + album.id + '/shares')) }
    catch (e) { setError(e.message) }
  }, [album.id])
  useEffect(() => { loadLinks() }, [loadLinks])
  async function create(e) {
    e.preventDefault(); setBusy(true); setError('')
    try { setShare(await api('/api/albums/' + album.id + '/shares', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ amount: Number(amount), unit, label }) })); setCopied(false); await loadLinks(); await onChanged?.() }
    catch (e) { setError(e.message) }
    finally { setBusy(false) }
  }
  async function revoke(token) {
    setRevoking(token); setError('')
    try { await api('/api/albums/' + album.id + '/shares/' + token, { method: 'DELETE' }); setLinks(ls => (ls || []).filter(l => l.token !== token)); if (share?.token === token) { setShare(null); setCopied(false) }; notify('Link revoked'); await onChanged?.() }
    catch (e) { setError(e.message) }
    finally { setRevoking(null) }
  }
  async function copy(value = link) {
    try { await navigator.clipboard.writeText(value); if (value === link) setCopied(true); notify('Link copied') }
    catch { linkInput.current?.focus(); linkInput.current?.select(); setError('Select the link and copy it manually.') }
  }
  async function nativeShare() {
    try { await navigator.share({ title: album.name, url: link }) }
    catch (e) { if (e.name !== 'AbortError') setError('Could not open sharing. Copy the link instead.') }
  }
  return <Dialog title={'Share “' + album.name + '”'} description="Anyone with the link can view this album. Revoke any link below to stop new visits. Media already opened may remain accessible for up to 5 minutes. Saved copies cannot be recalled." close={close} busy={busy || !!revoking}>
    {links === null && !error && <p role="status">Loading sharing status…</p>}
    {links !== null && visibleLinks.length === 0 && <p className="expiry-summary">Private album · No active share links</p>}
    {!!visibleLinks.length && <div className="share-links">
      <p className="field-label">Active links</p>
      <ul>
        {visibleLinks.map(l => <li key={l.token} className="share-link-row">
          <strong>{l.label || 'Share link'}</strong>
          <span><Clock3 size={14} />Expires {new Date(l.expiresAt).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })}</span>
          <a className="pill" href={location.origin + "/share/" + l.token} target="_blank" rel="noreferrer">Open link</a>
          <button type="button" className="button secondary" onClick={() => copy(location.origin + '/share/' + l.token)}><Copy size={16} />Copy</button>
          <button type="button" className="button secondary" aria-label="Revoke this link" disabled={!!revoking} onClick={() => revoke(l.token)}>
            {revoking === l.token ? <CameraSpinner size={16} inherit decorative /> : <Trash2 size={16} />}Revoke
          </button>
          <ShareLinkSettings link={l} album={album} onSaved={async () => { await loadLinks(); await onChanged?.() }} />
        </li>)}
      </ul>
    </div>}
    {!share ? <form onSubmit={create}>
      <label className="field">Link label<input maxLength={90} value={label} onChange={e => setLabel(e.target.value)} placeholder="Family, client, event…" /></label>
      <p className="field-label" id="expiry-label">Link expires after</p>
      <div className="chip-row" role="group" aria-labelledby="expiry-label">
        {presets.map(([value, label]) => {
          const on = !custom && Number(amount) === 1 && unit === value
          return <button type="button" key={value} className={'chip' + (on ? ' selected' : '')} aria-pressed={on} onClick={() => { setCustom(false); setAmount(1); setUnit(value) }}>{label}</button>
        })}
        <button type="button" className={'chip' + (custom ? ' selected' : '')} aria-pressed={custom} onClick={() => setCustom(true)}>Custom</button>
      </div>
      {custom && <fieldset className="duration-field">
        <legend className="visually-hidden">Custom duration</legend>
        <label><span className="visually-hidden">Amount</span><input required type="number" min="1" max="365" value={amount} onChange={e => setAmount(e.target.value)} /></label>
        <label><span className="visually-hidden">Unit</span><select value={unit} onChange={e => setUnit(e.target.value)}>{units.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
      </fieldset>}
      <ErrorNotice message={error} />
      <div className="dialog-actions">
        <button type="button" className="button secondary" disabled={busy} onClick={close}>Cancel</button>
        <button className="button primary" disabled={busy}>{busy ? <CameraSpinner size={20} inherit decorative /> : <Share2 size={18} />}{busy ? 'Creating…' : 'Create link'}</button>
      </div>
    </form> : <>
      <div className="link-box">
        <input ref={linkInput} value={link} readOnly onFocus={e => e.target.select()} aria-label="Share link" />
        <button className="button primary" onClick={() => copy()}>{copied ? <Check size={18} /> : <Copy size={18} />}{copied ? 'Copied' : 'Copy'}</button>
      </div>
      <div className="expiry-summary"><Clock3 size={16} /><span>Expires {new Date(share.expiresAt).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })}</span></div>
      <ErrorNotice message={error} />
      <div className="dialog-actions">
        {typeof navigator.share === 'function' && <button className="button secondary" onClick={nativeShare}><Share2 size={18} />Send…</button>}
        <button className="button secondary" onClick={close}>Done</button>
      </div>
    </>}
  </Dialog>
}

/* ---------- viewer ---------- */

function Viewer({ items, current, onChange, close, onDelete, onEdit, onHistory, onCover, isCover }) {
  const ref = useRef(null)
  const stage = useRef(null)
  const img = useRef(null)
  const strip = useRef(null)
  const gesture = useRef(null)
  const pointers = useRef(new Map())
  const pinch = useRef(null)
  const lastTap = useRef(null)
  const tapTimer = useRef(null)
  const [details, setDetails] = useState(false)
  const [chrome, setChrome] = useState(true)
  // view.s is the zoom relative to "fit to screen"; x/y is the pan in CSS pixels.
  const [view, setView] = useState({ s: 1, x: 0, y: 0, animate: false })
  const [natural, setNatural] = useState(null)
  const [hiRes, setHiRes] = useState(false)
  const [sharpening, setSharpening] = useState(false)
  const [videoReady, setVideoReady] = useState(false)
  const playback = useRef({ id: current.id, time: 0 })
  const [drag, setDrag] = useState({ x: 0, y: 0, active: false })
  const index = Math.max(0, items.findIndex(item => item.id === current.id))
  const video = isVideo(current)
  const zoomed = view.s > 1.01
  // The viewer shows the display rendition and swaps in the original once zooming needs more pixels than it has.
  const displaySrc = mediaUrl(current, 'display')
  const originalSrc = mediaUrl(current, 'original')
  const src = video ? originalSrc : hiRes ? originalSrc : displaySrc
  const full = !video && current.width > 0 && current.height > 0 ? { w: current.width, h: current.height } : null
  const move = useCallback(direction => { if (items.length > 1) onChange(items[(index + direction + items.length) % items.length]) }, [items, index, onChange])

  useEffect(() => {
    const previous = document.activeElement
    const overflow = document.documentElement.style.overflow
    document.documentElement.style.overflow = 'hidden'
    const node = ref.current; node.showModal(); node.focus()
    return () => { node.close(); document.documentElement.style.overflow = overflow; clearTimeout(tapTimer.current); if (previous?.isConnected) previous.focus() }
  }, [])

  // Reset zoom on navigation, keep the filmstrip centered, and warm up neighbours.
  useEffect(() => {
    setView({ s: 1, x: 0, y: 0, animate: false })
    setNatural(null)
    setHiRes(false)
    setSharpening(false)
    setVideoReady(false)
    playback.current = { id: current.id, time: 0 }
    setDrag({ x: 0, y: 0, active: false })
    strip.current?.querySelector('[aria-current="true"]')?.scrollIntoView({ inline: 'center', block: 'nearest', behavior: 'smooth' })
    for (const offset of [1, -1]) {
      const neighbour = items[(index + offset + items.length) % items.length]
      if (neighbour && !isVideo(neighbour)) new window.Image().src = mediaUrl(neighbour, 'display')
    }
  }, [current.id])

  // A resized window changes the fit size, so start again from "fit".
  useEffect(() => {
    const reset = () => setView({ s: 1, x: 0, y: 0, animate: false })
    window.addEventListener('resize', reset)
    return () => window.removeEventListener('resize', reset)
  }, [])

  /* ---- zoom maths ---- */
  // Scale at which one pixel of the original maps to one physical screen pixel ("actual size").
  // Uses the original's stored width when known, since the loaded display rendition may be smaller.
  const nativeScale = () => {
    const i = img.current
    if (!i || !natural || !i.offsetWidth) return 1
    return (full?.w || natural.w) / (i.offsetWidth * (window.devicePixelRatio || 1))
  }
  // Allow up to twice the actual size (or 2x fit for small photos); the readout warns past 100%.
  const maxScale = () => Math.max(nativeScale() * 2, 2)
  function clampView(next) {
    const i = img.current, st = stage.current
    if (!i || !st) return next
    const s = Math.min(maxScale(), Math.max(1, next.s))
    if (s <= 1.001) return { ...next, s: 1, x: 0, y: 0 }
    const w = i.offsetWidth * s, h = i.offsetHeight * s
    const cx = i.offsetLeft + i.offsetWidth / 2, cy = i.offsetTop + i.offsetHeight / 2
    const sw = st.clientWidth, sh = st.clientHeight
    const x = w <= sw ? 0 : Math.min(w / 2 - cx, Math.max(sw - w / 2 - cx, next.x))
    const y = h <= sh ? 0 : Math.min(h / 2 - cy, Math.max(sh - h / 2 - cy, next.y))
    return { ...next, s, x, y }
  }
  // Zoom to scale s while keeping the photo point under (clientX, clientY) in place.
  function zoomAt(s, clientX, clientY, animate = true, from = view) {
    const i = img.current, st = stage.current
    if (!i || !st || video) return
    const rect = st.getBoundingClientRect()
    const baseX = rect.left + i.offsetLeft + i.offsetWidth / 2, baseY = rect.top + i.offsetTop + i.offsetHeight / 2
    const px = (clientX ?? rect.left + rect.width / 2) - baseX, py = (clientY ?? rect.top + rect.height / 2) - baseY
    const target = Math.min(maxScale(), Math.max(1, s))
    const k = target / from.s
    setView(clampView({ s: target, x: px - (px - from.x) * k, y: py - (py - from.y) * k, animate }))
  }
  const zoomBy = factor => zoomAt(view.s * factor)
  const resetZoom = () => setView({ s: 1, x: 0, y: 0, animate: true })
  const actualSize = () => zoomAt(Math.max(1, nativeScale()))
  function toggleZoom(clientX, clientY) {
    if (zoomed) { resetZoom(); return }
    const native = nativeScale()
    zoomAt(native > 1.3 ? native : 2.5, clientX, clientY)
  }

  // Wheel / trackpad zoom needs a non-passive listener so the page does not scroll.
  const viewRef = useRef(view)
  viewRef.current = view
  const zoomAtRef = useRef(zoomAt)
  zoomAtRef.current = zoomAt
  useEffect(() => {
    const node = stage.current
    if (!node) return
    const wheel = e => {
      if (e.target.closest('video')) return
      e.preventDefault()
      const delta = e.deltaY * (e.deltaMode === 1 ? 16 : 1)
      const factor = Math.exp(-delta * (e.ctrlKey ? 0.01 : 0.0018))
      zoomAtRef.current(viewRef.current.s * factor, e.clientX, e.clientY, false)
    }
    node.addEventListener('wheel', wheel, { passive: false })
    return () => node.removeEventListener('wheel', wheel)
  }, [])

  /* ---- pointer gestures: pinch, pan, swipe, tap ---- */
  function startPinch() {
    const [a, b] = [...pointers.current.values()]
    pinch.current = { dist: Math.hypot(a.x - b.x, a.y - b.y) || 1, midX: (a.x + b.x) / 2, midY: (a.y + b.y) / 2, view }
    gesture.current = null
    setDrag({ x: 0, y: 0, active: false })
  }
  function onPointerDown(e) {
    if (e.target.closest('video, button')) return
    e.currentTarget.setPointerCapture?.(e.pointerId)
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY })
    if (pointers.current.size === 2 && !video) { startPinch(); return }
    if (pointers.current.size > 2) return
    gesture.current = { id: e.pointerId, x: e.clientX, y: e.clientY, moved: false, view }
  }
  function onPointerMove(e) {
    if (!pointers.current.has(e.pointerId)) return
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY })
    const p = pinch.current
    if (p && pointers.current.size >= 2) {
      const [a, b] = [...pointers.current.values()]
      const dist = Math.hypot(a.x - b.x, a.y - b.y), midX = (a.x + b.x) / 2, midY = (a.y + b.y) / 2
      const i = img.current, rect = stage.current.getBoundingClientRect()
      const baseX = rect.left + i.offsetLeft + i.offsetWidth / 2, baseY = rect.top + i.offsetTop + i.offsetHeight / 2
      const s = Math.min(maxScale(), Math.max(1, p.view.s * dist / p.dist))
      const k = s / p.view.s
      setView(clampView({ s, x: midX - baseX - (p.midX - baseX - p.view.x) * k, y: midY - baseY - (p.midY - baseY - p.view.y) * k, animate: false }))
      return
    }
    const g = gesture.current
    if (!g || g.id !== e.pointerId) return
    const dx = e.clientX - g.x, dy = e.clientY - g.y
    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) g.moved = true
    if (!g.moved) return
    if (g.view.s > 1.01) { setView(clampView({ ...g.view, x: g.view.x + dx, y: g.view.y + dy, animate: false })); return }
    if (video) return
    const vertical = Math.abs(dy) > Math.abs(dx) && dy > 0
    setDrag({ x: vertical ? 0 : dx, y: vertical ? dy : 0, active: true })
  }
  function onPointerUp(e) {
    pointers.current.delete(e.pointerId)
    if (pinch.current) {
      if (pointers.current.size < 2) pinch.current = null
      // Continue panning with the remaining finger.
      const [rest] = [...pointers.current.entries()]
      gesture.current = rest ? { id: rest[0], x: rest[1].x, y: rest[1].y, moved: true, view: viewRef.current } : null
      return
    }
    const g = gesture.current
    gesture.current = null
    if (!g || g.id !== e.pointerId) return
    const dx = e.clientX - g.x, dy = e.clientY - g.y
    if (g.moved) {
      setDrag({ x: 0, y: 0, active: false })
      if (g.view.s > 1.01 || video) return
      if (Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(dy)) move(dx < 0 ? 1 : -1)
      else if (dy > 110 && Math.abs(dy) > Math.abs(dx)) close()
      return
    }
    // Tap: single toggles the controls, double zooms.
    const now = Date.now()
    if (lastTap.current && now - lastTap.current.t < 300 && Math.hypot(e.clientX - lastTap.current.x, e.clientY - lastTap.current.y) < 40) {
      clearTimeout(tapTimer.current)
      lastTap.current = null
      toggleZoom(e.clientX, e.clientY)
    } else {
      lastTap.current = { t: now, x: e.clientX, y: e.clientY }
      clearTimeout(tapTimer.current)
      tapTimer.current = setTimeout(() => setChrome(value => !value), 300)
    }
  }
  function onPointerCancel(e) {
    pointers.current.delete(e.pointerId)
    if (pointers.current.size < 2) pinch.current = null
    gesture.current = null
    setDrag({ x: 0, y: 0, active: false })
  }

  function onKeyDown(e) {
    trapFocus(ref.current, e)
    if (e.target.tagName === 'VIDEO' || e.target.tagName === 'INPUT') return
    if (e.key === 'ArrowRight' && !zoomed) { e.preventDefault(); move(1) }
    else if (e.key === 'ArrowLeft' && !zoomed) { e.preventDefault(); move(-1) }
    else if (e.key.startsWith('Arrow') && zoomed) {
      e.preventDefault()
      const step = 80, d = { ArrowLeft: [step, 0], ArrowRight: [-step, 0], ArrowUp: [0, step], ArrowDown: [0, -step] }[e.key]
      setView(v => clampView({ ...v, x: v.x + d[0], y: v.y + d[1], animate: true }))
    }
    else if (e.key === 'i') setDetails(value => !value)
    else if (video) return
    else if (e.key === 'z') toggleZoom()
    else if (e.key === '+' || e.key === '=') zoomBy(1.5)
    else if (e.key === '-' || e.key === '_') zoomBy(1 / 1.5)
    else if (e.key === '0') resetZoom()
    else if (e.key === '1') actualSize()
  }

  // Load the original once the display rendition has fewer pixels than the screen shows at this zoom.
  const wantHiRes = !video && !hiRes && displaySrc !== originalSrc && natural?.w > 0 && !!img.current
    && view.s * img.current.offsetWidth * (window.devicePixelRatio || 1) > natural.w * 1.05
  useEffect(() => {
    if (!wantHiRes) return
    let cancelled = false
    const loader = new window.Image()
    loader.src = originalSrc
    setSharpening(true)
    loader.decode().then(() => { if (!cancelled) setHiRes(true) }).catch(() => {}).finally(() => { if (!cancelled) setSharpening(false) })
    return () => { cancelled = true; setSharpening(false) }
  }, [wantHiRes, originalSrc])

  const percent = Math.round(view.s / nativeScale() * 100)
  const enlarged = natural && percent > 102
  const imageStyle = drag.active
    ? { transform: 'translate(' + drag.x + 'px,' + drag.y + 'px) scale(' + (1 - Math.min(drag.y, 300) / 1500) + ')', transition: 'none' }
    : { transform: 'translate(' + view.x + 'px,' + view.y + 'px) scale(' + view.s + ')', transition: view.animate ? undefined : 'none' }
  const date = new Date(current.uploadedAt).toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric', year: 'numeric' })

  return <dialog ref={ref} tabIndex={-1} className={'viewer' + (chrome ? '' : ' immersive') + (zoomed ? ' zoomed' : '')} aria-label={'Viewer: ' + current.filename}
    style={drag.y ? { '--fade': 1 - Math.min(drag.y, 300) / 400 } : undefined}
    onCancel={e => { e.preventDefault(); if (zoomed) resetZoom(); else close() }} onKeyDown={onKeyDown}>
    <div className="viewer-toolbar">
      <button className="icon-button" aria-label="Close viewer" onClick={close}><X size={22} /></button>
      <div className="viewer-title"><strong>{current.filename}</strong><span>{date} · {index + 1} of {items.length}</span></div>
      <div className="viewer-actions">
        {onCover && <button className={'icon-button' + (isCover ? ' is-cover' : '')} aria-label={isCover ? 'This is the album cover' : 'Set as album cover'} title={isCover ? 'Album cover' : 'Set as album cover'} aria-pressed={isCover} disabled={isCover} onClick={onCover}><Star size={20} fill={isCover ? 'currentColor' : 'none'} /></button>}
        {onHistory && !video && <button className="icon-button" aria-label="Photo edit history" onClick={onHistory}><Clock3 size={20} /></button>}
        {onEdit && !video && <button className="icon-button" aria-label="Edit photo" title="Edit" onClick={onEdit}><SlidersHorizontal size={20} /></button>}
        {!video && <button className="icon-button hide-small" aria-label="Zoom in" title="Zoom in (+)" onClick={() => zoomBy(1.5)}><ZoomIn size={20} /></button>}
        <a className="icon-button" href={current.urls?.download || originalSrc} download={current.filename} aria-label="Download original" title="Download original"
          onClick={e => { e.preventDefault(); downloadOriginal(current).catch(() => window.open(originalSrc, '_blank', 'noopener')) }}><ArrowDownToLine size={20} /></a>
        <button className="icon-button" aria-label="Details" aria-pressed={details} onClick={() => setDetails(value => !value)}><Info size={20} /></button>
        {onDelete && <button className="icon-button" aria-label="Delete item" onClick={onDelete}><Trash2 size={20} /></button>}
      </div>
    </div>
    <div ref={stage} className={'viewer-stage' + (zoomed ? ' zoomed' : '')} onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerUp} onPointerCancel={onPointerCancel}>
      {video
        ? <video key={current.id} src={src} controls playsInline autoPlay
            onTimeUpdate={e => { if (e.currentTarget.readyState >= 2) playback.current = { id: current.id, time: e.currentTarget.currentTime } }}
            onLoadedMetadata={e => { if (playback.current.id === current.id && playback.current.time > 0) e.currentTarget.currentTime = playback.current.time }}
            onLoadedData={() => setVideoReady(true)} onError={() => setVideoReady(true)} />
        : <img key={current.id} ref={img} src={src} alt={current.filename} draggable={false} decoding="async" className={full ? 'sized' : undefined}
            onLoad={e => setNatural({ w: e.currentTarget.naturalWidth, h: e.currentTarget.naturalHeight })} onError={() => setNatural({ w: 0, h: 0 })}
            style={full ? { '--w': String(full.w), '--ar': String(full.w / full.h), ...imageStyle } : imageStyle} />}
      {(video ? !videoReady : !natural) && <div className="viewer-loading"><CameraSpinner size={60} inherit delay label={'Loading ' + current.filename} /></div>}
    </div>
    {!video && natural && <div className={'zoom-hud' + (zoomed ? ' visible' : '')} role="group" aria-label="Zoom">
      <button aria-label="Zoom out" title="Zoom out (−)" onClick={() => zoomBy(1 / 1.5)} disabled={!zoomed}><ZoomOut size={18} /></button>
      <button className={'zoom-level' + (enlarged ? ' enlarged' : '')} onClick={() => (Math.abs(percent - 100) <= 2 ? resetZoom() : actualSize())}
        title={enlarged ? 'Beyond the photo’s original resolution' : 'Show actual size (1) or fit (0)'}
        aria-label={'Zoom ' + percent + '% of actual size. ' + (Math.abs(percent - 100) <= 2 ? 'Fit to screen' : 'Show actual size')}>
        {percent}%{sharpening ? <small>Sharpening…</small> : Math.abs(percent - 100) <= 2 ? <small>Actual size</small> : enlarged ? <small>Enlarged</small> : null}
      </button>
      <button aria-label="Zoom in" title="Zoom in (+)" onClick={() => zoomBy(1.5)} disabled={view.s >= maxScale() - 0.01}><ZoomIn size={18} /></button>
      {zoomed && <button aria-label="Fit to screen" title="Fit (0)" onClick={resetZoom}><Minimize2 size={17} /></button>}
    </div>}
    {items.length > 1 && <>
      <button className="viewer-nav prev" aria-label="Previous" onClick={() => move(-1)}><ChevronLeft size={28} /></button>
      <button className="viewer-nav next" aria-label="Next" onClick={() => move(1)}><ChevronRight size={28} /></button>
      <div className="filmstrip" ref={strip} role="group" aria-label="All items">
        {items.map((item, i) => <button key={item.id} aria-current={item.id === current.id ? 'true' : undefined} aria-label={'Show ' + item.filename} onClick={() => onChange(item)}>
          {isVideo(item) ? <span className="film-video"><Play size={14} fill="currentColor" /></span> : <img src={mediaUrl(item, 'thumbnail')} alt="" loading="lazy" decoding="async" />}
        </button>)}
      </div>
    </>}
    {details && <div className="viewer-details">
      <div className="details-heading"><h3>Details</h3><button className="icon-button small" aria-label="Close details" onClick={() => setDetails(false)}><X size={18} /></button></div>
      <dl>
        <dt>File</dt><dd>{current.filename}</dd>
        {current.takenAt && <><dt>Taken</dt><dd>{new Date(current.takenAt).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })}</dd></>}
        <dt>Uploaded</dt><dd>{new Date(current.uploadedAt).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })}</dd>
        {current.width > 0 && current.height > 0 && <><dt>Dimensions</dt><dd>{current.width} × {current.height}</dd></>}
        {statusOf(current) === 'PROCESSING' && <><dt>Status</dt><dd>Processing</dd></>}
        <dt>Size</dt><dd>{sizeLabel(current.size)}</dd>
        <dt>Type</dt><dd>{current.contentType}</dd>
        {current.albumName && <><dt>Album</dt><dd>{current.albumName}</dd></>}
      </dl>
      {current.albumId && current.albumName && <Link className="button secondary viewer-album" to={'/albums/' + current.albumId}><FolderOpen size={17} />Open album</Link>}
    </div>}
  </dialog>
}

/* ---------- shared & misc pages ---------- */

function SharedAlbumPage() {
  const { token } = useParams()
  const state = useAlbum('/api/shared/' + token)
  const [selected, setSelected] = useState(null)
  const [slideshowOpen, setSlideshowOpen] = useState(false)
  useEffect(() => {
    const controller = new AbortController()
    let running = false
    const check = async (refresh = false) => {
      if (document.hidden || running || controller.signal.aborted) return
      running = true
      try {
        await api('/api/shared/' + token + '/status', { signal: controller.signal, cache: 'no-store' })
        if (refresh && !controller.signal.aborted) await state.reload()
      } catch (e) {
        if (!controller.signal.aborted && (e.status === 404 || e.status === 410)) {
          state.invalidate(e.status === 410 ? 'This share link has expired.' : 'This share link is no longer available.')
          setSelected(null)
        }
      } finally { running = false }
    }
    const statusTimer = setInterval(() => check(), 30000)
    const mediaTimer = setInterval(() => check(true), 240000)
    const visible = () => { if (!document.hidden) check(true) }
    document.addEventListener('visibilitychange', visible)
    return () => { controller.abort(); clearInterval(statusTimer); clearInterval(mediaTimer); document.removeEventListener('visibilitychange', visible) }
  }, [token, state.reload, state.invalidate])
  // Signed `urls` from the API win; `url` is the fallback for backends that stream through the share endpoint.
  const items = useMemo(() => (state.album?.photos || []).filter(isViewable).map(item => ({ ...item, url: '/api/shared/' + token + '/photos/' + item.id })), [state.album, token])
  usePolling(pendingKey(items), state.reload)
  const expired = state.error.includes('expired')
  return <div className="shared-shell">
    <header className="topbar"><div className="topbar-inner">
      <span className="brand"><span className="brand-mark"><Camera size={19} /></span>Stillroom</span>
      <span className="read-only"><Share2 size={14} />View only</span>
      <ThemeMenu />
    </div></header>
    <main id="main" className={'page album-page album-theme-' + (state.album?.presentation?.theme || 'classic')}>
      {state.loading ? <PageLoader label="Opening shared album…" />
        : state.album ? <>
            <AlbumHeader album={state.album} items={items}>{!!items.length && <button className="button secondary" onClick={() => setSlideshowOpen(true)}><Play size={18} />Slideshow</button>}</AlbumHeader>
            {items.length ? <MediaGroups items={items} onOpen={setSelected} /> : <Empty title="No moments here yet" description="The album owner hasn't added photos or videos yet." />}
          </>
        : <Empty icon={Clock3} title={expired ? 'This link has expired' : 'This album is unavailable'} description={expired ? 'Ask the album owner for a new share link.' : 'Check your connection or ask the album owner for a new link.'}>
            <button className="button secondary" onClick={state.retry}>Try again</button>
          </Empty>}
      {state.album && slideshowOpen && <Suspense fallback={<PageLoader label="Opening slideshow…" />}><Slideshow items={items} presentation={state.album.presentation} Dialog={Dialog} close={() => setSlideshowOpen(false)} /></Suspense>}
      {state.album && selected && <Viewer items={items} current={items.find(item => item.id === selected.id) || selected} onChange={setSelected} close={() => setSelected(null)} />}
    </main>
  </div>
}

function NotFound() {
  return <main id="main" className="page"><Empty icon={Image} title="We couldn't find that page" description="Head back to your albums to keep browsing.">
    <Link className="button primary" to="/">Go to albums</Link>
  </Empty></main>
}

export default App
