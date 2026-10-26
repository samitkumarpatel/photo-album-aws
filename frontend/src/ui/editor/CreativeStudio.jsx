import { useEffect, useMemo, useRef, useState } from 'react'
import { loadEditableImage } from './loadImage.js'
import { mediaUrl } from '../media.js'
import { scaledCopy } from './imageOps.js'
import { uploadToAlbum } from '../upload/uploads.js'
import { createDepthRenderer } from './depthRenderer.js'
import { cinematicPose, downloadBlob, generateDepth, generatePersonMask, imageBlob, recordCanvas, videoFormat } from './creativeMedia.js'
import { createFocusRenderer, createPopoutRenderer, createSelectiveColorRenderer, createLivingRenderer } from './creativeEffects.js'
import { usePaintMask } from './usePaintMask.js'
import ScrollHints from '../ScrollHints.jsx'
import './creativeStudio.css'

const EFFECTS = [['cinematic', 'Cinematic animation'], ['focus', 'Tap to focus'], ['popout', 'Pop-out photo'], ['selective', 'Selective color'], ['living', 'Living photo']]

export default function CreativeStudio({ item, Dialog, close, onSaved }) {
  const [mode, setMode] = useState('cinematic')
  const [photo, setPhoto] = useState(null), [depth, setDepth] = useState(null)
  const [error, setError] = useState(''), [message, setMessage] = useState('Opening photo…')
  const [busy, setBusy] = useState('load'), [retry, setRetry] = useState(0)
  const [playing, setPlaying] = useState(false), [path, setPath] = useState('orbit')
  const [strength, setStrength] = useState(.6), [seconds, setSeconds] = useState(6)
  const [clip, setClip] = useState(null), [clipUrl, setClipUrl] = useState(''), [saved, setSaved] = useState(false)
  const [focus, setFocus] = useState(.5), [focusPoint, setFocusPoint] = useState(null), [blur, setBlur] = useState(.6), [focusWidth, setFocusWidth] = useState(.12)
  const [imageSaved, setImageSaved] = useState(false)
  const [brushSize, setBrushSize] = useState(.08), [erase, setErase] = useState(false), [editMask, setEditMask] = useState(true)
  const [motion, setMotion] = useState(.04), [motionAxis, setMotionAxis] = useState('horizontal')
  const [saturation, setSaturation] = useState(0)
  const [frame, setFrame] = useState(.15), [frameColor, setFrameColor] = useState('#f4eee4'), [caption, setCaption] = useState(''), [captionColor, setCaptionColor] = useState('#242424')
  const painter = usePaintMask(photo, mode)
  const needsDepth = mode === 'cinematic' || mode === 'focus'
  const ready = !!photo && (!needsDepth || !!depth)
  const photoUrl = useMemo(() => photo?.toDataURL('image/jpeg', .9), [photo])
  const canvas = useRef(null), renderer = useRef(null), operation = useRef(null)
  const alive = useRef(true)

  useEffect(() => { alive.current = true; return () => { alive.current = false; operation.current?.abort() } }, [])
  useEffect(() => {
    const controller = new AbortController()
    setBusy('load'); setError(''); setMessage('Opening photo…')
    loadEditableImage(mediaUrl(item, 'display'), controller.signal).then(image => {
      if (!controller.signal.aborted) { setPhoto(scaledCopy(image, 1600)); setBusy(''); setMessage('') }
    }).catch(e => { if (!controller.signal.aborted) { setError(e.message); setBusy(''); setMessage('') } })
    return () => controller.abort()
  }, [item, retry])

  useEffect(() => {
    if (!clip) { setClipUrl(''); return }
    const url = URL.createObjectURL(clip.blob)
    setClipUrl(url)
    return () => URL.revokeObjectURL(url)
  }, [clip])

  useEffect(() => {
    if (!photo || !canvas.current) return
    const node = canvas.current
    node.width = photo.width; node.height = photo.height
    if (needsDepth && !depth) return
    try { renderer.current = mode === 'popout' ? createPopoutRenderer(node, photo) : mode === 'selective' ? createSelectiveColorRenderer(node, photo) : mode === 'living' ? createLivingRenderer(node, photo) : mode === 'focus' ? createFocusRenderer(node, photo, depth) : createDepthRenderer(node, photo, depth); renderer.current.draw() }
    catch (e) { setError(e.message) }
    const lost = e => { e.preventDefault(); operation.current?.abort(); setPlaying(false); setError('Graphics connection lost. Close Creative effects and reopen it to try again.') }
    node.addEventListener('webglcontextlost', lost)
    return () => { node.removeEventListener('webglcontextlost', lost); renderer.current?.dispose(); renderer.current = null }
  }, [photo, depth, mode])

  useEffect(() => {
    if (!renderer.current || busy === 'record' || !['cinematic', 'living'].includes(mode)) return
    let frame
    const start = performance.now()
    const tick = now => {
      const pose = cinematicPose(playing ? ((now - start) / (seconds * 1000)) % 1 : 0, path)
      if (mode === 'living') renderer.current?.draw(painter.mask, playing ? ((now - start) / (seconds * 1000)) % 1 : 0, motion, motionAxis, painter.revision)
      else renderer.current?.draw(pose.x, pose.y, strength, pose.zoom)
      if (playing) frame = requestAnimationFrame(tick)
    }
    tick(start)
    return () => cancelAnimationFrame(frame)
  }, [photo, depth, mode, playing, path, strength, seconds, busy, painter.mask, painter.revision, motion, motionAxis])

  useEffect(() => {
    if (mode === 'focus') renderer.current?.draw(focus, blur, focusWidth, focusPoint)
    setImageSaved(false)
    if (mode === 'popout') renderer.current?.draw(painter.mask, { frame, color: frameColor, caption, captionColor, editMask })
    if (mode === 'selective') renderer.current?.draw(painter.mask, { saturation, editMask })
    if (mode === 'living' && !playing && busy !== 'record') renderer.current?.draw(painter.mask, 0, motion, motionAxis, painter.revision)
  }, [photo, depth, mode, focus, blur, focusWidth, focusPoint, painter.mask, painter.revision, frame, frameColor, caption, captionColor, editMask, saturation, motion, motionAxis, playing, busy])

  function chooseFocus(event) {
    if (mode !== 'focus' || busy || !depth || !renderer.current) return
    const rect = event.currentTarget.getBoundingClientRect()
    const point = { x: (event.clientX - rect.left) / rect.width, y: (event.clientY - rect.top) / rect.height }
    setFocusPoint(point); setFocus(renderer.current.sample(point.x, point.y))
  }

  async function exportImage(save) {
    setBusy(save ? 'save' : 'image'); setError(''); setMessage(save ? 'Saving a new photo…' : 'Preparing image…')
    try {
      if (mode === 'focus') renderer.current.draw(focus, blur, focusWidth)
      else if (mode === 'selective') renderer.current.draw(painter.mask, { saturation })
      else if (mode === 'living') renderer.current.draw(painter.mask, 0, motion, motionAxis, painter.revision)
      else renderer.current.draw(painter.mask, { frame, color: frameColor, caption, captionColor })
      const blob = await imageBlob(canvas.current)
      const name = filename(mode + '.png')
      if (save) {
        const result = await uploadToAlbum(item.albumId, new File([blob], name, { type: 'image/png' }))
        setImageSaved(true); await onSaved?.(result)
      } else downloadBlob(blob, name)
    } catch (e) { if (alive.current) setError(e.message || 'Could not export the photo.') }
    finally { if (alive.current) {
      if (mode === 'focus') renderer.current?.draw(focus, blur, focusWidth, focusPoint)
      else if (mode === 'selective') renderer.current?.draw(painter.mask, { saturation, editMask })
      else if (mode === 'living') renderer.current?.draw(painter.mask, 0, motion, motionAxis, painter.revision)
      else renderer.current?.draw(painter.mask, { frame, color: frameColor, caption, captionColor, editMask })
      setBusy(''); setMessage('')
    } }
  }

  async function prepareDepth() {
    const controller = new AbortController(); operation.current = controller
    setBusy('depth'); setError(''); setMessage('Preparing depth…')
    try { const result = await generateDepth(photo, { signal: controller.signal, onProgress: setMessage }); if (!controller.signal.aborted) setDepth(result) }
    catch (e) { if (e.name !== 'AbortError' && alive.current) setError(e.message) }
    finally { if (alive.current) { setBusy(''); setMessage('') }; operation.current = null }
  }

  async function selectPerson() {
    const controller = new AbortController(); operation.current = controller
    setBusy('person'); setError(''); setMessage('Selecting the person…')
    try {
      const mask = await generatePersonMask(photo, { signal: controller.signal, onProgress: setMessage })
      if (!controller.signal.aborted) { painter.replace(mask); setEditMask(true) }
    } catch (e) { if (e.name !== 'AbortError' && alive.current) setError(e.message) }
    finally { if (alive.current) { setBusy(''); setMessage('') }; operation.current = null }
  }

  async function exportVideo() {
    if (!renderer.current) return
    const controller = new AbortController(); operation.current = controller
    setPlaying(false); setBusy('record'); setError(''); setMessage('Recording video… 0%')
    try {
      const blob = await recordCanvas(canvas.current, progress => {
        if (mode === 'living') renderer.current.draw(painter.mask, progress, motion, motionAxis, painter.revision)
        else { const pose = cinematicPose(progress, path); renderer.current.draw(pose.x, pose.y, strength, pose.zoom) }
      }, { seconds, signal: controller.signal, onProgress: progress => setMessage(`Recording video… ${Math.round(progress * 100)}%`) })
      if (!controller.signal.aborted && alive.current) { setClip({ blob, mode }); setSaved(false) }
    } catch (e) { if (e.name !== 'AbortError' && alive.current) setError(e.message) }
    finally { if (alive.current) { setBusy(''); setMessage('') }; operation.current = null }
  }

  const filename = suffix => (item.filename?.replace(/\.[^.]+$/, '') || 'photo') + '-' + suffix
  const clipName = clip ? filename(clip.mode + (clip.blob.type === 'video/mp4' ? '.mp4' : '.webm')) : ''
  async function saveClip() {
    setBusy('save'); setError(''); setMessage('Saving a new video to your album…')
    try {
      const result = await uploadToAlbum(item.albumId, new File([clip.blob], clipName, { type: clip.blob.type }))
      setSaved(true); await onSaved?.(result)
    } catch (e) { setError(e.message || 'Could not save the video.') }
    finally { setBusy(''); setMessage('') }
  }
  const requestClose = () => { if (busy === 'save') return; operation.current?.abort(); close() }
  return <Dialog title="Creative effects" description="Make something new from your photo. Your original stays unchanged." close={requestClose} busy={busy === 'save'} wide>
    <ScrollHints className="creative-modes" role="group" aria-label="Creative effects">{EFFECTS.map(([id, label]) => <button className={'button ' + (mode === id ? 'primary' : 'secondary')} aria-pressed={mode === id} disabled={!!busy} key={id} onClick={() => { setMode(id); setPlaying(false); setError('') }}>{label}</button>)}</ScrollHints>
    {error && <p role="alert">{error}</p>}
    {message && <p role="status" aria-live="polite">{message}</p>}
    {!photo && !busy && <button className="button secondary" onClick={() => setRetry(n => n + 1)}>Retry photo</button>}
    {photo && <div className="creative-workspace">
      <div className="creative-preview">
        {!ready && <img src={photoUrl} alt={item.filename} />}
        <canvas ref={canvas} key={mode} hidden={!ready} role="img" aria-label={mode === 'focus' ? 'Tap photo to choose focus' : mode === 'popout' ? 'Pop-out photo preview. Paint to select the subject.' : mode === 'selective' ? 'Paint the part of the photo to keep in color.' : mode === 'living' ? 'Paint the area to animate.' : 'Cinematic photo preview'}
          onPointerDown={e => { if (busy) return; if ((mode === 'popout' && editMask) || mode === 'selective' || mode === 'living') painter.start(e, brushSize, erase); else chooseFocus(e) }}
          onPointerMove={e => { if (!busy && ((mode === 'popout' && editMask) || mode === 'selective' || mode === 'living')) painter.move(e) }} onPointerUp={painter.end} onPointerCancel={painter.end} />
      </div>
      <div className="creative-settings">
      {needsDepth && !depth && <><p>Generate depth on your device for this effect. First use downloads an AI model; your photo stays on your device.</p><button className="button primary" disabled={!!busy} onClick={prepareDepth}>Generate depth</button></>}
      {depth && mode === 'cinematic' && <fieldset className="creative-controls" disabled={!!busy}><legend>Cinematic animation</legend>
        <label>Camera movement<select value={path} onChange={e => setPath(e.target.value)}><option value="orbit">Gentle orbit</option><option value="pan">Side to side</option><option value="push">Slow zoom</option></select></label>
        <label>Depth <input type="range" min="0" max="1" step=".05" value={strength} onChange={e => setStrength(Number(e.target.value))} /><output>{Math.round(strength * 100)}%</output></label>
        <label>Clip length<select value={seconds} onChange={e => setSeconds(Number(e.target.value))}>{[4, 6, 10].map(n => <option value={n} key={n}>{n} seconds</option>)}</select></label>
        <div className="creative-actions"><button className="button secondary" onClick={() => setPlaying(value => !value)}>{playing ? 'Pause preview' : 'Play preview'}</button><button className="button primary" disabled={!videoFormat()} onClick={exportVideo}>Create video</button></div>
        {!videoFormat() && <p>Video export is not supported in this browser. You can still preview the effect.</p>}
        <p>Keep this tab open while recording. Video downloads as MP4 or WebM, depending on your browser.</p>
      </fieldset>}
      {depth && mode === 'focus' && <fieldset className="creative-controls" disabled={!!busy}><legend>Tap to focus</legend>
        <p>Tap an object to keep its depth sharp. Adjust the sliders to soften nearer and farther areas.</p>
        <label>Focus distance <input type="range" min="0" max="1" step=".01" value={focus} onChange={e => { setFocus(Number(e.target.value)); setFocusPoint(null) }} /><output>{Math.round(focus * 100)}%</output></label>
        <label>Blur <input type="range" min="0" max="1" step=".05" value={blur} onChange={e => setBlur(Number(e.target.value))} /><output>{Math.round(blur * 100)}%</output></label>
        <label>Focus range <input type="range" min=".03" max=".4" step=".01" value={focusWidth} onChange={e => setFocusWidth(Number(e.target.value))} /></label>
        <div className="creative-actions"><button className="button secondary" onClick={() => exportImage(false)}>Download photo</button>{onSaved && <button className="button primary" disabled={imageSaved} onClick={() => exportImage(true)}>{imageSaved ? 'Saved to album' : 'Save photo as copy'}</button>}</div>
      </fieldset>}
      {mode === 'selective' && <fieldset className="creative-controls" disabled={!!busy}><legend>Selective color</legend>
        <p>Paint over the parts of the photo to keep in color. The rest becomes black and white.</p>
        <div className="creative-actions" role="group" aria-label="Color selection brush"><button className={'button ' + (!erase ? 'primary' : 'secondary')} aria-pressed={!erase} onClick={() => setErase(false)}>Keep color</button><button className={'button ' + (erase ? 'primary' : 'secondary')} aria-pressed={erase} onClick={() => setErase(true)}>Remove color</button><button className="button secondary" disabled={!painter.canUndo} onClick={painter.undo}>Undo brush</button><button className="button secondary" onClick={painter.clear}>Clear selection</button></div>
        <label>Brush size <input type="range" min=".01" max=".3" step=".01" value={brushSize} onChange={e => setBrushSize(Number(e.target.value))} /></label>
        <label>Background color <input type="range" min="0" max="1" step=".05" value={saturation} onChange={e => setSaturation(Number(e.target.value))} /><output>{Math.round(saturation * 100)}%</output></label>
        <div className="creative-actions"><button className="button secondary" onClick={() => exportImage(false)}>Download photo</button>{onSaved && <button className="button primary" disabled={imageSaved} onClick={() => exportImage(true)}>{imageSaved ? 'Saved to album' : 'Save photo as copy'}</button>}</div>
      </fieldset>}
      {mode === 'living' && <fieldset className="creative-controls" disabled={!!busy}><legend>Living photo</legend>
        <p>Paint over water, clouds, leaves or hair to add gentle looping motion. Preview the movement and create a short video.</p>
        <div className="creative-actions" role="group" aria-label="Motion brush"><button className={'button ' + (!erase ? 'primary' : 'secondary')} aria-pressed={!erase} onClick={() => setErase(false)}>Animate</button><button className={'button ' + (erase ? 'primary' : 'secondary')} aria-pressed={erase} onClick={() => setErase(true)}>Erase motion</button><button className="button secondary" disabled={!painter.canUndo} onClick={painter.undo}>Undo brush</button><button className="button secondary" onClick={painter.clear}>Clear motion</button></div>
        <label>Brush size <input type="range" min=".01" max=".3" step=".01" value={brushSize} onChange={e => setBrushSize(Number(e.target.value))} /></label>
        <label>Motion amount <input type="range" min=".01" max=".12" step=".005" value={motion} onChange={e => setMotion(Number(e.target.value))} /></label>
        <label>Direction<select value={motionAxis} onChange={e => setMotionAxis(e.target.value)}><option value="horizontal">Side to side</option><option value="vertical">Up and down</option></select></label>
        <div className="creative-actions"><button className="button secondary" disabled={!painter.hasPaint} onClick={() => setPlaying(value => !value)}>{playing ? 'Pause preview' : 'Play loop'}</button><button className="button primary" disabled={!painter.hasPaint || !videoFormat()} onClick={exportVideo}>Create video</button></div>
      </fieldset>}
      {mode === 'popout' && <fieldset className="creative-controls" disabled={!!busy}><legend>Pop-out photo</legend>
        <p>Select a person automatically, or paint any subject. The selected area will appear over the frame and caption. Increase the frame width until your subject crosses its edge.</p>
        <div className="creative-actions"><button className="button secondary" onClick={selectPerson}>Select person automatically</button><button className="button secondary" aria-pressed={editMask} onClick={() => setEditMask(v => !v)}>{editMask ? 'Preview pop-out' : 'Edit selection'}</button></div>
        <p>Automatic selection downloads a portrait model on first use. Photos stay on your device.</p>
        {editMask && <>
          <div className="creative-actions" role="group" aria-label="Selection brush"><button className={'button ' + (!erase ? 'primary' : 'secondary')} aria-pressed={!erase} onClick={() => setErase(false)}>Select</button><button className={'button ' + (erase ? 'primary' : 'secondary')} aria-pressed={erase} onClick={() => setErase(true)}>Erase</button><button className="button secondary" disabled={!painter.canUndo} onClick={painter.undo}>Undo brush</button><button className="button secondary" onClick={painter.clear}>Clear selection</button></div>
          <label>Brush size <input type="range" min=".01" max=".3" step=".01" value={brushSize} onChange={e => setBrushSize(Number(e.target.value))} /></label>
        </>}
        <label>Frame width <input type="range" min=".05" max=".35" step=".01" value={frame} onChange={e => setFrame(Number(e.target.value))} /></label>
        <label>Frame color <input type="color" value={frameColor} onChange={e => setFrameColor(e.target.value)} /></label>
        <label>Caption <input type="text" value={caption} maxLength={80} onChange={e => setCaption(e.target.value)} placeholder="Add a caption" /></label>
        <label>Caption color <input type="color" value={captionColor} onChange={e => setCaptionColor(e.target.value)} /></label>
        <div className="creative-actions"><button className="button secondary" disabled={!painter.hasPaint} onClick={() => exportImage(false)}>Download photo</button>{onSaved && <button className="button primary" disabled={!painter.hasPaint || imageSaved} onClick={() => exportImage(true)}>{imageSaved ? 'Saved to album' : 'Save photo as copy'}</button>}</div>
      </fieldset>}
      {clip && <section className="creative-result" aria-label="Created video"><h3>Your {clip.mode} video</h3>{clipUrl && <video src={clipUrl} controls loop playsInline />}
        <div className="creative-actions"><button className="button secondary" disabled={!!busy} onClick={() => downloadBlob(clip.blob, clipName)}>Download video</button>{onSaved && <button className="button primary" disabled={!!busy || saved} onClick={saveClip}>{saved ? 'Saved to album' : 'Save video to album'}</button>}</div>
      </section>}
      </div>
    </div>}
    <div className="creative-actions">{busy && busy !== 'load' && busy !== 'save' && <button className="button secondary" onClick={() => operation.current?.abort()}>Cancel {busy === 'record' ? 'recording' : 'generation'}</button>}<button className="button secondary" disabled={busy === 'save'} onClick={requestClose}>Close</button></div>
  </Dialog>
}
