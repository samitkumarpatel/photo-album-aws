import { env, pipeline, RawImage } from '@huggingface/transformers'

// Inference stays in a worker, so downloading/running the model does not block the editor.
env.allowLocalModels = false
env.backends.onnx.wasm.numThreads = 1
let remover

self.onmessage = async ({ data: { pixels, width, height } }) => {
  try {
    if (!remover) {
      self.postMessage({ status: 'loading', message: 'Downloading background tool… First use can take a moment.' })
      remover = await pipeline('background-removal', 'Xenova/modnet', {
        device: 'wasm', dtype: 'fp32',
        progress_callback: event => {
          if (event.status === 'progress' && Number.isFinite(event.progress))
            self.postMessage({ status: 'loading', message: 'Downloading background tool… ' + Math.round(event.progress) + '%' })
        },
      })
    }
    self.postMessage({ status: 'processing', message: 'Separating the person from the background…' })
    const output = await remover(new RawImage(new Uint8ClampedArray(pixels), width, height, 4))
    const result = Array.isArray(output) ? output[0] : output
    if (!result || result.channels !== 4) throw new Error('No foreground mask')
    // Only send the alpha mask back; the editor uses the full-resolution original for the subject.
    let foreground = 0
    for (let i = 0; i < result.data.length; i += 4) {
      foreground += result.data[i + 3]
      result.data[i] = result.data[i + 1] = result.data[i + 2] = 255
    }
    if (foreground / (255 * result.width * result.height) < 0.001) {
      self.postMessage({ status: 'error', message: 'No person could be found. Try a photo with a clearly visible person.' })
      return
    }
    self.postMessage({ status: 'done', pixels: result.data.buffer, width: result.width, height: result.height }, [result.data.buffer])
  } catch (error) {
    console.error('Background removal failed:', error)
    remover = null
    self.postMessage({ status: 'error', message: 'Background removal couldn’t finish. Check your connection and try again. This tool needs a browser with WebAssembly support.' })
  }
}
