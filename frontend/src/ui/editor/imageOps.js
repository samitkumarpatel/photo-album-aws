// Pure image-processing helpers for the photo editor. No React here.

export const ADJUSTMENTS = [
  { key: 'exposure', label: 'Exposure', min: -100, max: 100 },
  { key: 'brightness', label: 'Brightness', min: -100, max: 100 },
  { key: 'contrast', label: 'Contrast', min: -100, max: 100 },
  { key: 'highlights', label: 'Highlights', min: -100, max: 100 },
  { key: 'shadows', label: 'Shadows', min: -100, max: 100 },
  { key: 'saturation', label: 'Saturation', min: -100, max: 100 },
  { key: 'vibrance', label: 'Vibrance', min: -100, max: 100 },
  { key: 'warmth', label: 'Warmth', min: -100, max: 100 },
  { key: 'tint', label: 'Tint', min: -100, max: 100 },
  { key: 'fade', label: 'Fade', min: 0, max: 100 },
  { key: 'clarity', label: 'Clarity', min: -100, max: 100 },
  { key: 'sharpen', label: 'Sharpen', min: 0, max: 100 },
  { key: 'denoise', label: 'Denoise', min: 0, max: 100 },
  { key: 'grain', label: 'Grain', min: 0, max: 100 },
  { key: 'vignette', label: 'Vignette', min: 0, max: 100 },
]

const ZERO = Object.fromEntries(ADJUSTMENTS.map(a => [a.key, 0]))
const LIMITS = Object.fromEntries(ADJUSTMENTS.map(a => [a.key, [a.min, a.max]]))

const GRAY = [0.2126, 0.7152, 0.0722, 0.2126, 0.7152, 0.0722, 0.2126, 0.7152, 0.0722]
const SEPIA = [0.393, 0.769, 0.189, 0.349, 0.686, 0.168, 0.272, 0.534, 0.131]

export const FILTERS = [
  { id: 'none', label: 'Original', adjust: {} },
  { id: 'vivid', label: 'Vivid', adjust: { saturation: 30, contrast: 15, vibrance: 25 } },
  { id: 'warm', label: 'Warm', adjust: { warmth: 35, saturation: 10, highlights: -10 } },
  { id: 'cool', label: 'Cool', adjust: { warmth: -35, tint: -5, contrast: 6 } },
  { id: 'film', label: 'Film', adjust: { fade: 25, warmth: 12, contrast: 12, saturation: -8, vignette: 20 } },
  { id: 'drama', label: 'Drama', adjust: { contrast: 40, shadows: -15, highlights: -25, vibrance: 25, vignette: 30 } },
  { id: 'fade', label: 'Faded', adjust: { fade: 45, saturation: -20, contrast: -10 } },
  { id: 'mono', label: 'Mono', adjust: { contrast: 10 }, matrix: GRAY },
  { id: 'noir', label: 'Noir', adjust: { contrast: 45, exposure: -10, vignette: 35 }, matrix: GRAY },
  { id: 'sepia', label: 'Sepia', adjust: { fade: 10 }, matrix: SEPIA },
]

export const ASPECTS = [
  { id: 'free', label: 'Free', ratio: null },
  { id: 'original', label: 'Original', ratio: 'original' },
  { id: 'square', label: 'Square', ratio: 1 },
  { id: '4:5', label: '4:5', ratio: 4 / 5 },
  { id: '3:4', label: '3:4', ratio: 3 / 4 },
  { id: '4:3', label: '4:3', ratio: 4 / 3 },
  { id: '16:9', label: '16:9', ratio: 16 / 9 },
  { id: '9:16', label: '9:16', ratio: 9 / 16 },
]

export const DEFAULT_EDIT = Object.freeze({
  adjust: ZERO,
  filter: { id: 'none', strength: 100 },
  auto: { on: false, strength: 100 },
  crop: { x: 0, y: 0, w: 1, h: 1 },
  aspect: 'free',
  quarter: 0,
  flipX: false,
  flipY: false,
  angle: 0,
})

export const sameEdit = (a, b) => JSON.stringify(a) === JSON.stringify(b)

/** Combine manual sliders, auto-enhance and the filter into one set of numbers. */
export function combineParams(edit, autoParams) {
  const filter = FILTERS.find(f => f.id === edit.filter.id) || FILTERS[0]
  const k = edit.filter.strength / 100
  const autoK = edit.auto.on && autoParams ? edit.auto.strength / 100 : 0
  const out = {}
  for (const key of Object.keys(ZERO)) {
    const value = edit.adjust[key] + (autoParams?.[key] || 0) * autoK + (filter.adjust[key] || 0) * k
    const [min, max] = LIMITS[key]
    out[key] = Math.max(min, Math.min(max, value))
  }
  out.matrix = filter.matrix || null
  out.matrixStrength = filter.matrix ? k : 0
  return out
}

/* ---------- geometry ---------- */

export function orientedSize(width, height, quarter) {
  return quarter % 2 ? { w: height, h: width } : { w: width, h: height }
}

/** Scale needed so a straightened image still covers its frame with no empty corners. */
function coverScale(w, h, angle) {
  const t = Math.abs(angle * Math.PI / 180)
  const c = Math.cos(t), s = Math.sin(t)
  return Math.max((w * c + h * s) / w, (w * s + h * c) / h)
}

function drawTransformed(ctx, source, width, height, edit, offsetX, offsetY) {
  const { w, h } = orientedSize(width, height, edit.quarter)
  ctx.save()
  ctx.translate(w / 2 - offsetX, h / 2 - offsetY)
  ctx.rotate(edit.angle * Math.PI / 180)
  ctx.scale(edit.flipX ? -1 : 1, edit.flipY ? -1 : 1)
  ctx.rotate(edit.quarter * Math.PI / 2)
  const s = coverScale(w, h, edit.angle)
  ctx.scale(s, s)
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(source, -width / 2, -height / 2, width, height)
  ctx.restore()
}

/* ---------- pixels ---------- */

const luma = (r, g, b) => 0.2126 * r + 0.7152 * g + 0.0722 * b

export function processPixels(image, p) {
  const d = image.data, width = image.width, height = image.height
  const exp = 2 ** (p.exposure / 100)
  const bri = p.brightness * 0.5
  const c = p.contrast / 100
  const cf = c >= 0 ? 1 + c * 1.2 : 1 + c * 0.8
  const sat = 1 + p.saturation / 100
  const vib = p.vibrance / 100
  const warm = p.warmth / 100 * 25
  const tint = p.tint / 100 * 25
  const hi = p.highlights / 100 * 90
  const sh = p.shadows / 100 * 90
  const fade = p.fade / 100
  const vig = p.vignette / 100 * 0.9
  const m = p.matrix, mk = p.matrixStrength
  const cx = width / 2, cy = height / 2, maxD = cx * cx + cy * cy
  const identity = !p.exposure && !p.brightness && !p.contrast && !p.saturation && !p.vibrance && !p.warmth && !p.tint && !p.highlights && !p.shadows && !fade && !vig && !mk
  if (!identity) {
    for (let y = 0, i = 0; y < height; y++) {
      const dy = y - cy
      for (let x = 0; x < width; x++, i += 4) {
        let r = d[i] * exp + bri, g = d[i + 1] * exp + bri, b = d[i + 2] * exp + bri
        r += warm + tint * 0.3; g -= tint; b += tint * 0.3 - warm
        if (hi || sh) {
          const l = Math.max(0, Math.min(1, luma(r, g, b) / 255))
          const delta = sh * (1 - l) * (1 - l) * (sh > 0 ? 1 : l * 2) + hi * l * l
          r += delta; g += delta; b += delta
        }
        r = (r - 128) * cf + 128; g = (g - 128) * cf + 128; b = (b - 128) * cf + 128
        if (sat !== 1 || vib) {
          const l = luma(r, g, b)
          let s = sat
          if (vib) {
            const mx = Math.max(r, g, b), mn = Math.min(r, g, b)
            s *= 1 + vib * (1 - Math.min(1, (mx - mn) / 255)) * 1.4
          }
          r = l + (r - l) * s; g = l + (g - l) * s; b = l + (b - l) * s
        }
        if (mk) {
          const nr = m[0] * r + m[1] * g + m[2] * b, ng = m[3] * r + m[4] * g + m[5] * b, nb = m[6] * r + m[7] * g + m[8] * b
          r += (nr - r) * mk; g += (ng - g) * mk; b += (nb - b) * mk
        }
        if (fade) { const f = 1 - 0.2 * fade, lift = 35 * fade; r = r * f + lift; g = g * f + lift; b = b * f + lift }
        if (vig) {
          const dx = x - cx, dist = (dx * dx + dy * dy) / maxD
          const v = 1 - vig * dist ** 1.3
          r *= v; g *= v; b *= v
        }
        d[i] = r; d[i + 1] = g; d[i + 2] = b
      }
    }
  }
  // Detail passes. Radii scale with image size so the preview matches the full-size export.
  const unit = Math.max(width, height) / 1000
  if (p.denoise > 0) denoise(image, p.denoise / 100, Math.max(1, Math.round(unit * 1.5)))
  if (p.clarity) clarity(image, p.clarity / 100, Math.max(2, Math.round(unit * 12)))
  if (p.sharpen > 0) sharpen(image, p.sharpen / 100 * 0.9)
  if (p.grain > 0) grain(image, p.grain / 100, Math.max(1, Math.round(unit)))
  return image
}

/** Separable box blur on RGB, returns a new buffer. */
function boxBlur(src, w, h, r) {
  const tmp = new Float32Array(src.length), out = new Uint8ClampedArray(src.length)
  const size = r * 2 + 1
  for (let y = 0; y < h; y++) {
    const row = y * w * 4
    for (let c = 0; c < 3; c++) {
      let acc = 0
      for (let k = -r; k <= r; k++) acc += src[row + Math.min(w - 1, Math.max(0, k)) * 4 + c]
      for (let x = 0; x < w; x++) {
        tmp[row + x * 4 + c] = acc / size
        acc += src[row + Math.min(w - 1, x + r + 1) * 4 + c] - src[row + Math.max(0, x - r) * 4 + c]
      }
    }
  }
  for (let x = 0; x < w; x++) {
    for (let c = 0; c < 3; c++) {
      let acc = 0
      for (let k = -r; k <= r; k++) acc += tmp[Math.min(h - 1, Math.max(0, k)) * w * 4 + x * 4 + c]
      for (let y = 0; y < h; y++) {
        out[y * w * 4 + x * 4 + c] = acc / size
        acc += tmp[Math.min(h - 1, y + r + 1) * w * 4 + x * 4 + c] - tmp[Math.max(0, y - r) * w * 4 + x * 4 + c]
      }
    }
  }
  return out
}

/** Edge-aware smoothing: blur flat areas, leave strong edges alone. */
function denoise(image, amount, r) {
  const d = image.data
  const blurred = boxBlur(d, image.width, image.height, r)
  const threshold = 18 + amount * 30
  for (let i = 0; i < d.length; i += 4) {
    const diff = (Math.abs(d[i] - blurred[i]) + Math.abs(d[i + 1] - blurred[i + 1]) + Math.abs(d[i + 2] - blurred[i + 2])) / 3
    const k = amount * Math.max(0, 1 - diff / threshold)
    d[i] += (blurred[i] - d[i]) * k; d[i + 1] += (blurred[i + 1] - d[i + 1]) * k; d[i + 2] += (blurred[i + 2] - d[i + 2]) * k
  }
}

/** Local contrast: boost (or soften) mid-frequency detail using a wide blur. */
function clarity(image, amount, r) {
  const d = image.data
  const blurred = boxBlur(d, image.width, image.height, r)
  const k = amount > 0 ? amount * 0.8 : amount * 0.6
  for (let i = 0; i < d.length; i += 4) {
    const l = (d[i] + d[i + 1] + d[i + 2]) / 765
    const midtones = 1 - Math.abs(l - 0.5) * 1.6
    const w = k * Math.max(0.15, midtones)
    d[i] += (d[i] - blurred[i]) * w; d[i + 1] += (d[i + 1] - blurred[i + 1]) * w; d[i + 2] += (d[i + 2] - blurred[i + 2]) * w
  }
}

/** Film grain from a stable hash, so the pattern does not flicker while sliding. */
function grain(image, amount, cell) {
  const { data: d, width: w, height: h } = image
  const strength = amount * 38
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const gx = (x / cell) | 0, gy = (y / cell) | 0
      let n = (gx * 374761393 + gy * 668265263) | 0
      n = Math.imul(n ^ (n >>> 13), 1274126177)
      const noise = (((n ^ (n >>> 16)) & 1023) / 1023 - 0.5) * strength
      const i = (y * w + x) * 4
      d[i] += noise; d[i + 1] += noise; d[i + 2] += noise
    }
  }
}

function sharpen(image, amount) {
  const { width: w, height: h, data: d } = image
  const src = new Uint8ClampedArray(d)
  const row = w * 4
  for (let y = 1; y < h - 1; y++) {
    for (let x = 1; x < w - 1; x++) {
      const i = y * row + x * 4
      for (let ch = 0; ch < 3; ch++) {
        const j = i + ch
        d[j] = src[j] * (1 + 4 * amount) - amount * (src[j - 4] + src[j + 4] + src[j - row] + src[j + row])
      }
    }
  }
}

/* ---------- rendering ---------- */

/**
 * Render the edited image.
 * mode "full" renders the whole (rotated/straightened) frame, used while cropping;
 * mode "final" applies the crop.
 */
export function render(canvas, source, width, height, edit, params, mode = 'final') {
  const frame = orientedSize(width, height, edit.quarter)
  const region = mode === 'final'
    ? { x: edit.crop.x * frame.w, y: edit.crop.y * frame.h, w: edit.crop.w * frame.w, h: edit.crop.h * frame.h }
    : { x: 0, y: 0, w: frame.w, h: frame.h }
  canvas.width = Math.max(1, Math.round(region.w))
  canvas.height = Math.max(1, Math.round(region.h))
  const ctx = canvas.getContext('2d', { willReadFrequently: true })
  ctx.clearRect(0, 0, canvas.width, canvas.height)
  drawTransformed(ctx, source, width, height, edit, region.x, region.y)
  if (params) {
    const image = ctx.getImageData(0, 0, canvas.width, canvas.height)
    ctx.putImageData(processPixels(image, params), 0, 0)
  }
  return canvas
}

/** Downscale an image into a canvas so slider previews stay fast. */
export function scaledCopy(image, maxSide) {
  const width = image.naturalWidth || image.width, height = image.naturalHeight || image.height
  const scale = Math.min(1, maxSide / Math.max(width, height))
  const canvas = document.createElement('canvas')
  canvas.width = Math.max(1, Math.round(width * scale))
  canvas.height = Math.max(1, Math.round(height * scale))
  const ctx = canvas.getContext('2d')
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(image, 0, 0, canvas.width, canvas.height)
  return canvas
}

/** Full-resolution export, capped to a pixel budget that mobile browsers can handle. */
export async function exportBlob(image, edit, params, type) {
  const MAX_PIXELS = 16_000_000
  const width = image.naturalWidth, height = image.naturalHeight
  const frame = orientedSize(width, height, edit.quarter)
  const area = frame.w * edit.crop.w * frame.h * edit.crop.h
  const source = area > MAX_PIXELS ? scaledCopy(image, Math.max(width, height) * Math.sqrt(MAX_PIXELS / area)) : image
  const canvas = render(document.createElement('canvas'), source, source.naturalWidth || source.width, source.naturalHeight || source.height, edit, params, 'final')
  const blob = await new Promise(resolve => canvas.toBlob(resolve, type, 0.92))
  if (!blob) throw new Error('Could not create the edited image.')
  return blob
}

/* ---------- auto enhance ---------- */

/** Look at the histogram and colour balance, and suggest corrections. */
export function analyze(image) {
  const sample = scaledCopy(image, 256)
  const { data } = sample.getContext('2d').getImageData(0, 0, sample.width, sample.height)
  const hist = new Uint32Array(256)
  let sr = 0, sg = 0, sb = 0, chroma = 0, dark = 0, bright = 0, n = 0
  for (let i = 0; i < data.length; i += 4) {
    if (data[i + 3] < 128) continue
    const r = data[i], g = data[i + 1], b = data[i + 2]
    const l = Math.round(luma(r, g, b))
    hist[l]++; n++
    sr += r; sg += g; sb += b
    chroma += (Math.max(r, g, b) - Math.min(r, g, b)) / 255
    if (l < 40) dark++
    if (l > 230) bright++
  }
  if (!n) return { ...ZERO }
  const percentile = q => { let acc = 0; for (let v = 0; v < 256; v++) { acc += hist[v]; if (acc >= q * n) return v } return 255 }
  const low = percentile(0.01), high = percentile(0.99), mid = percentile(0.5)
  const mean = (sr + sg + sb) / 3 / n
  const avgChroma = chroma / n
  const clamp = (v, a) => Math.round(Math.max(-a, Math.min(a, v)))
  const out = { ...ZERO }
  out.exposure = clamp(Math.log2(118 / Math.max(mid, 8)) * 70, 60)
  const spread = high - low
  if (spread < 230) out.contrast = clamp((230 - spread) / 230 * 90, 45)
  if (low > 12) out.brightness = clamp(-low * 0.35, 20)
  if (dark / n > 0.25) out.shadows = clamp(dark / n * 80, 40)
  if (bright / n > 0.08) out.highlights = clamp(-bright / n * 150, 45)
  const gray = mean || 1
  out.warmth = clamp((sb / n - sr / n) / gray * 120, 35)
  out.tint = clamp((sg / n - (sr / n + sb / n) / 2) / gray * 150, 25)
  out.vibrance = avgChroma < 0.15 ? 30 : 12
  out.sharpen = 15
  return out
}

/** Pick an output format the browser can encode, keeping the original when possible. */
export function outputFormat(contentType, filename) {
  const keep = ['image/jpeg', 'image/png', 'image/webp']
  const type = keep.includes(contentType) ? contentType : 'image/jpeg'
  const ext = { 'image/jpeg': 'jpg', 'image/png': 'png', 'image/webp': 'webp' }[type]
  const base = filename.replace(/\.[^.]+$/, '') || 'photo'
  const name = keep.includes(contentType) ? filename : base + '.' + ext
  return { type, name, copyName: base + '-edited.' + ext }
}
