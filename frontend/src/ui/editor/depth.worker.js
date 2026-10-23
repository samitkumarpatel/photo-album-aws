import { env, pipeline, RawImage } from '@huggingface/transformers'

env.allowLocalModels = false
env.backends.onnx.wasm.numThreads = 1

self.onmessage = async ({ data: { pixels, width, height } }) => {
  try {
    self.postMessage({ message: 'Downloading the depth tool… First use can take a few minutes.' })
    const estimator = await pipeline('depth-estimation', 'onnx-community/depth-anything-v2-small', {
      device: 'wasm', dtype: 'q8',
      progress_callback: event => {
        if (event.status === 'progress' && Number.isFinite(event.progress))
          self.postMessage({ message: `Downloading depth tool… ${Math.round(event.progress)}%` })
      },
    })
    self.postMessage({ message: 'Estimating the distance of objects in your photo…' })
    const { depth } = await estimator(new RawImage(new Uint8ClampedArray(pixels), width, height, 4))
    self.postMessage({ done: true, pixels: depth.data, width: depth.width, height: depth.height, channels: depth.channels })
    await estimator.dispose()
  } catch (error) {
    console.error('Depth estimation failed', error)
    self.postMessage({ error: 'Could not generate depth. Check your connection and try again. This tool needs WebAssembly and enough free device memory.' })
  }
}
