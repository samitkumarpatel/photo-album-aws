export function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url; link.download = filename; link.click()
  setTimeout(() => URL.revokeObjectURL(url), 60_000)
}

export function imageBlob(canvas, type = 'image/png') {
  return new Promise((resolve, reject) => canvas.toBlob(blob => blob ? resolve(blob) : reject(new Error('Could not export this image.')), type, .94))
}

export function videoFormat(Recorder = globalThis.MediaRecorder) {
  if (!Recorder) return null
  return ['video/mp4;codecs=avc1.42E01E', 'video/webm;codecs=vp8', 'video/webm', 'video/mp4'].find(type => Recorder.isTypeSupported(type)) || null
}

/** Records only the supplied canvas. No camera, microphone or screen permissions. */
export function recordCanvas(canvas, draw, { seconds = 6, signal, onProgress = () => {} } = {}) {
  const mimeType = videoFormat()
  if (!mimeType || !canvas.captureStream) return Promise.reject(new Error('Video export is unavailable in this browser. Try a current Chrome, Edge or Safari browser.'))
  if (signal?.aborted) return Promise.reject(new DOMException('Export cancelled', 'AbortError'))
  return new Promise((resolve, reject) => {
    let recorder, stream, frame, watchdog, finished = false, stopping = false
    const chunks = []
    const clean = () => {
      cancelAnimationFrame(frame); clearTimeout(watchdog)
      signal?.removeEventListener('abort', abort)
      document.removeEventListener('visibilitychange', visibility)
      stream?.getTracks().forEach(track => track.stop())
    }
    const fail = error => {
      if (finished) return
      finished = true
      if (recorder?.state === 'recording') recorder.stop()
      clean(); reject(error)
    }
    const abort = () => fail(new DOMException('Export cancelled', 'AbortError'))
    const visibility = () => { if (document.hidden) fail(new Error('Video export stopped when this tab was hidden. Keep it open and try again.')) }
    try {
      if (document.hidden) throw new Error('Keep this tab visible while exporting video.')
      draw(0)
      stream = canvas.captureStream(30)
      recorder = new MediaRecorder(stream, { mimeType, videoBitsPerSecond: 6_000_000 })
      recorder.ondataavailable = event => { if (event.data.size) chunks.push(event.data) }
      recorder.onerror = () => fail(new Error('Video recording failed. Try a shorter clip or another browser.'))
      recorder.onstop = () => {
        if (finished) return
        finished = true; clean()
        if (!chunks.length) { reject(new Error('The browser produced an empty video.')); return }
        const type = recorder.mimeType.split(';')[0]
        resolve(new Blob(chunks, { type }))
      }
      signal?.addEventListener('abort', abort, { once: true })
      document.addEventListener('visibilitychange', visibility)
      recorder.start(200)
      const started = performance.now()
      const tick = now => {
        if (finished || stopping) return
        try {
          const progress = Math.min(1, (now - started) / (seconds * 1000))
          draw(progress); onProgress(progress)
          if (progress >= 1) { stopping = true; recorder.stop() }
          else frame = requestAnimationFrame(tick)
        } catch (error) { fail(error) }
      }
      frame = requestAnimationFrame(tick)
      watchdog = setTimeout(() => fail(new Error('Video export timed out. Keep this tab open and try again.')), (seconds + 15) * 1000)
    } catch (error) { fail(error) }
  })
}

/** Every path returns to its initial pose, for a seamless looping clip. */
export function cinematicPose(progress, path = 'orbit') {
  const angle = progress * 2 * Math.PI
  if (path === 'pan') return { x: Math.sin(angle) * .8, y: 0, zoom: 1 }
  if (path === 'push') return { x: 0, y: Math.sin(angle) * .2, zoom: 1 + (1 - Math.cos(angle)) * .05 }
  return { x: Math.sin(angle) * .7, y: Math.cos(angle) * .45, zoom: 1 + (1 - Math.cos(angle)) * .025 }
}

export function generateDepth(photo, { signal, onProgress = () => {} } = {}) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) { reject(new DOMException('Cancelled', 'AbortError')); return }
    const worker = new Worker(new URL('./depth.worker.js', import.meta.url), { type: 'module' })
    const done = () => { worker.terminate(); signal?.removeEventListener('abort', abort) }
    const abort = () => { done(); reject(new DOMException('Cancelled', 'AbortError')) }
    signal?.addEventListener('abort', abort, { once: true })
    worker.onerror = () => { done(); reject(new Error('The depth tool could not start. Try again in a browser with WebAssembly support.')) }
    worker.onmessage = ({ data }) => {
      if (data.error) { done(); reject(new Error(data.error)); return }
      if (!data.done) { onProgress(data.message); return }
      try {
        const depth = document.createElement('canvas')
        depth.width = data.width; depth.height = data.height
        const ctx = depth.getContext('2d'), rgba = ctx.createImageData(data.width, data.height)
        for (let i = 0; i < data.width * data.height; i++) {
          rgba.data[i * 4] = rgba.data[i * 4 + 1] = rgba.data[i * 4 + 2] = data.pixels[i * data.channels]
          rgba.data[i * 4 + 3] = 255
        }
        ctx.putImageData(rgba, 0, 0); done(); resolve(depth)
      } catch (error) { done(); reject(error) }
    }
    try {
      const input = document.createElement('canvas'), scale = Math.min(1, 768 / Math.max(photo.width, photo.height))
      input.width = Math.max(1, Math.round(photo.width * scale)); input.height = Math.max(1, Math.round(photo.height * scale))
      const ctx = input.getContext('2d')
      ctx.drawImage(photo, 0, 0, input.width, input.height)
      const pixels = ctx.getImageData(0, 0, input.width, input.height).data.buffer
      worker.postMessage({ pixels, width: input.width, height: input.height }, [pixels])
    } catch (error) { done(); reject(error) }
  })
}

export function generatePersonMask(photo, { signal, onProgress = () => {} } = {}) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) { reject(new DOMException('Cancelled', 'AbortError')); return }
    const worker = new Worker(new URL('./background.worker.js', import.meta.url), { type: 'module' })
    const done = () => { worker.terminate(); signal?.removeEventListener('abort', abort) }
    const abort = () => { done(); reject(new DOMException('Cancelled', 'AbortError')) }
    signal?.addEventListener('abort', abort, { once: true })
    worker.onerror = () => { done(); reject(new Error('Automatic person selection could not start. You can select the subject with the brush.')) }
    worker.onmessage = ({ data }) => {
      if (data.status === 'error') { done(); reject(new Error(data.message)); return }
      if (data.status !== 'done') { onProgress(data.message); return }
      try {
        const mask = document.createElement('canvas'); mask.width = data.width; mask.height = data.height
        mask.getContext('2d').putImageData(new ImageData(new Uint8ClampedArray(data.pixels), data.width, data.height), 0, 0)
        done(); resolve(mask)
      } catch (error) { done(); reject(error) }
    }
    try {
      const input = document.createElement('canvas'), scale = Math.min(1, 1024 / Math.max(photo.width, photo.height))
      input.width = Math.max(1, Math.round(photo.width * scale)); input.height = Math.max(1, Math.round(photo.height * scale))
      const ctx = input.getContext('2d'); ctx.drawImage(photo, 0, 0, input.width, input.height)
      const pixels = ctx.getImageData(0, 0, input.width, input.height).data.buffer
      worker.postMessage({ pixels, width: input.width, height: input.height }, [pixels])
    } catch (error) { done(); reject(error) }
  })
}
