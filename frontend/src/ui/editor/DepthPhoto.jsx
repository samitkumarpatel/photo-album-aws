import { useEffect, useRef, useState } from 'react'
import { loadEditableImage } from './loadImage.js'
import { mediaUrl } from '../media.js'
import { createDepthRenderer, depthDownloadHtml } from './depthRenderer.js'
import './depthPhoto.css'

export default function DepthPhoto({ item, Dialog, close }) {
  const canvas = useRef(null), renderer = useRef(null), position = useRef({ x: 0, y: 0 })
  const [result, setResult] = useState(null)
  const [attempt, setAttempt] = useState(0)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [strength, setStrength] = useState(0.6)
  const [tilt, setTilt] = useState(false)
  const [tiltError, setTiltError] = useState('')
  const tiltOrigin = useRef(null)

  useEffect(() => {
    if (!attempt) return
    const controller = new AbortController()
    let worker, stopped = false
    setError(''); setResult(null); setMessage('Loading your photo…')
    const fail = message => { if (!stopped) { setMessage(''); setError(message) }; worker?.terminate() }
    async function generate() {
      try {
        const image = await loadEditableImage(mediaUrl(item, 'display'), controller.signal)
        if (stopped) return
        const photo = document.createElement('canvas')
        const scale = Math.min(1, 1600 / Math.max(image.naturalWidth, image.naturalHeight))
        photo.width = Math.max(1, Math.round(image.naturalWidth * scale))
        photo.height = Math.max(1, Math.round(image.naturalHeight * scale))
        photo.getContext('2d').drawImage(image, 0, 0, photo.width, photo.height)
        const input = document.createElement('canvas')
        const inputScale = Math.min(1, 768 / Math.max(photo.width, photo.height))
        input.width = Math.max(1, Math.round(photo.width * inputScale))
        input.height = Math.max(1, Math.round(photo.height * inputScale))
        const context = input.getContext('2d')
        context.drawImage(photo, 0, 0, input.width, input.height)
        const pixels = context.getImageData(0, 0, input.width, input.height).data.buffer
        worker = new Worker(new URL('./depth.worker.js', import.meta.url), { type: 'module' })
        worker.onerror = () => fail('The depth tool could not start. Please try again in a browser with WebAssembly support.')
        worker.onmessage = ({ data }) => {
          if (stopped) return
          if (data.error) { fail(data.error); return }
          if (!data.done) { setMessage(data.message); return }
          try {
            const depth = document.createElement('canvas')
            depth.width = data.width; depth.height = data.height
            const ctx = depth.getContext('2d'), rgba = ctx.createImageData(data.width, data.height)
            for (let i = 0; i < data.width * data.height; i++) {
              rgba.data[i * 4] = rgba.data[i * 4 + 1] = rgba.data[i * 4 + 2] = data.pixels[i * data.channels]
              rgba.data[i * 4 + 3] = 255
            }
            ctx.putImageData(rgba, 0, 0)
            setResult({ photo, depth }); setMessage('')
            worker.terminate()
          } catch { fail('Could not prepare the 3D preview. Please try again.') }
        }
        worker.postMessage({ pixels, width: input.width, height: input.height }, [pixels])
      } catch (e) { if (!stopped) fail(e.message || 'Could not load the photo.') }
    }
    generate()
    return () => { stopped = true; controller.abort(); worker?.terminate() }
  }, [attempt, item])

  useEffect(() => {
    if (!result) return
    const node = canvas.current
    try {
      node.width = result.photo.width; node.height = result.photo.height
      renderer.current = createDepthRenderer(node, result.photo, result.depth)
      position.current = { x: 0, y: 0 }
      renderer.current.draw(0, 0, strength)
    } catch (e) { setError(e.message) }
    const lost = e => { e.preventDefault(); setError('The graphics connection was lost. Close this window and try again.') }
    node.addEventListener('webglcontextlost', lost)
    return () => { node.removeEventListener('webglcontextlost', lost); renderer.current?.dispose(); renderer.current = null }
  }, [result])

  useEffect(() => { renderer.current?.draw(position.current.x, position.current.y, strength) }, [strength])
  useEffect(() => {
    if (!tilt) return
    let frame = 0
    const orient = event => {
      if (!Number.isFinite(event.gamma) || !Number.isFinite(event.beta)) return
      if (!tiltOrigin.current) tiltOrigin.current = { gamma: event.gamma, beta: event.beta }
      cancelAnimationFrame(frame)
      frame = requestAnimationFrame(() => draw((event.gamma - tiltOrigin.current.gamma) / 30, (event.beta - tiltOrigin.current.beta) / 30))
    }
    window.addEventListener('deviceorientation', orient)
    return () => { cancelAnimationFrame(frame); window.removeEventListener('deviceorientation', orient) }
  }, [tilt, strength])
  function draw(x, y) {
    position.current = { x: Math.max(-1, Math.min(1, x)), y: Math.max(-1, Math.min(1, y)) }
    renderer.current?.draw(position.current.x, position.current.y, strength)
  }
  function download() {
    try {
      const html = depthDownloadHtml(result.photo.toDataURL('image/jpeg', 0.92), result.depth.toDataURL('image/png'), strength)
      const url = URL.createObjectURL(new Blob([html], { type: 'text/html' }))
      const link = document.createElement('a')
      link.href = url; link.download = (item.filename?.replace(/\.[^.]+$/, '') || 'photo') + '-3d.html'; link.click()
      setTimeout(() => URL.revokeObjectURL(url), 60_000)
    } catch { setError('Could not download the 3D photo. Please try again.') }
  }
  async function enableTilt() {
    setTiltError('')
    try {
      const Orientation = window.DeviceOrientationEvent
      if (!Orientation) throw new Error('This browser does not provide device motion. You can still drag the photo.')
      if (typeof Orientation.requestPermission === 'function' && await Orientation.requestPermission() !== 'granted')
        throw new Error('Motion access was not allowed. You can still drag the photo.')
      tiltOrigin.current = null; setTilt(true)
    } catch (error) { setTiltError(error.message || 'Could not enable motion. You can still drag the photo.') }
  }
  return <Dialog title="Create 3D photo" close={close} wide description="Give a normal photo a sense of depth. Your original stays unchanged.">
    {!result && <p>Generate an estimated depth map on your device. First use downloads an AI model and can take a few minutes. Photos are not uploaded to the AI service.</p>}
    {!attempt && <button className="button primary" onClick={() => setAttempt(1)}>Generate 3D photo</button>}
    {message && <p role="status" aria-live="polite">{message}</p>}
    {error && <p role="alert">{error}</p>}
    {error && !result && <button className="button secondary" onClick={() => setAttempt(value => value + 1)}>Try again</button>}
    {result && <>
      <div className="depth-preview"><canvas ref={canvas} tabIndex={0} role="img" aria-label={'3D preview of ' + item.filename} aria-describedby="depth-help"
        onPointerDown={e => { e.currentTarget.setPointerCapture(e.pointerId); e.currentTarget.focus() }}
        onPointerMove={e => {
          if (!e.currentTarget.hasPointerCapture(e.pointerId)) return
          const rect = e.currentTarget.getBoundingClientRect()
          draw((e.clientX - rect.left) / rect.width * 2 - 1, 1 - (e.clientY - rect.top) / rect.height * 2)
        }}
        onKeyDown={e => {
          if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home'].includes(e.key)) return
          e.preventDefault(); e.stopPropagation()
          const { x, y } = position.current
          if (e.key === 'Home') draw(0, 0)
          else draw(x + (e.key === 'ArrowRight' ? .1 : e.key === 'ArrowLeft' ? -.1 : 0), y + (e.key === 'ArrowUp' ? .1 : e.key === 'ArrowDown' ? -.1 : 0))
        }} /></div>
      <p id="depth-help">{tilt ? 'Tilt your phone to explore. Drag the photo or use arrow keys on a computer.' : 'Drag the photo or use arrow keys to explore. Fine edges can stretch; lower the depth for a gentler effect.'}</p>
      {!tilt && <button className="button secondary" onClick={enableTilt}>Use phone tilt</button>}
      {tiltError && <p role="status">{tiltError}</p>}
      <label className="depth-strength">Depth <input type="range" min="0" max="1" step="0.05" value={strength} onChange={e => setStrength(Number(e.target.value))} /><output>{Math.round(strength * 100)}%</output></label>
      <p>Download an interactive HTML file to open in a browser, including offline. This is a simulated depth effect, not an Apple spatial photo or a video.</p>
      <div className="depth-actions"><button className="button secondary" onClick={() => draw(0, 0)}>Reset view</button><button className="button primary" disabled={!!error} onClick={download}>Download 3D photo</button></div>
    </>}
    <div className="depth-actions"><button className="button secondary" onClick={close}>{message ? 'Cancel' : 'Close'}</button></div>
  </Dialog>
}
