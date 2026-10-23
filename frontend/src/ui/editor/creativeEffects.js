import { createDepthRenderer } from './depthRenderer.js'

export const clamp01 = value => Math.max(0, Math.min(1, value))

export function focusOpacity(depth, focus, width) {
  const distance = Math.abs(depth - focus)
  const t = clamp01((distance - width) / Math.max(.03, width))
  return 1 - t * t * (3 - 2 * t)
}

export function createFocusRenderer(canvas, photo, depth) {
  canvas.width = photo.width; canvas.height = photo.height
  const ctx = canvas.getContext('2d'), mask = document.createElement('canvas'), sharp = document.createElement('canvas'), blurred = document.createElement('canvas')
  for (const c of [mask, sharp, blurred]) { c.width = photo.width; c.height = photo.height }
  const mc = mask.getContext('2d', { willReadFrequently: true }), sc = sharp.getContext('2d'), bc = blurred.getContext('2d')
  mc.drawImage(depth, 0, 0, mask.width, mask.height)
  const depths = mc.getImageData(0, 0, mask.width, mask.height).data
  const alpha = mc.createImageData(mask.width, mask.height)
  let lastBlur = -1
  return {
    sample(x, y) { return depths[(Math.min(mask.height - 1, Math.floor(clamp01(y) * mask.height)) * mask.width + Math.min(mask.width - 1, Math.floor(clamp01(x) * mask.width))) * 4] / 255 },
    draw(focus = .5, amount = .6, width = .12, point = null) {
      if (lastBlur !== amount) {
        bc.clearRect(0, 0, blurred.width, blurred.height)
        const radius = amount * Math.min(photo.width, photo.height) * .025
        if ('filter' in bc) {
          bc.filter = `blur(${radius}px)`
          bc.drawImage(photo, -radius * 2, -radius * 2, photo.width + radius * 4, photo.height + radius * 4)
          bc.filter = 'none'
        } else {
          const small = document.createElement('canvas')
          small.width = Math.max(12, Math.round(photo.width / (1 + radius * 2)))
          small.height = Math.max(12, Math.round(photo.height / (1 + radius * 2)))
          small.getContext('2d').drawImage(photo, 0, 0, small.width, small.height)
          bc.drawImage(small, 0, 0, photo.width, photo.height)
        }
        lastBlur = amount
      }
      for (let i = 0; i < depths.length; i += 4) {
        alpha.data[i] = alpha.data[i + 1] = alpha.data[i + 2] = 255
        alpha.data[i + 3] = Math.round(focusOpacity(depths[i] / 255, focus, width) * 255)
      }
      mc.putImageData(alpha, 0, 0)
      sc.clearRect(0, 0, sharp.width, sharp.height)
      sc.globalCompositeOperation = 'source-over'; sc.drawImage(photo, 0, 0)
      sc.globalCompositeOperation = 'destination-in'; sc.drawImage(mask, 0, 0)
      sc.globalCompositeOperation = 'source-over'
      ctx.clearRect(0, 0, canvas.width, canvas.height)
      ctx.drawImage(blurred, 0, 0); ctx.drawImage(sharp, 0, 0)
      if (point) {
        ctx.save(); ctx.beginPath(); ctx.arc(point.x * canvas.width, point.y * canvas.height, Math.min(canvas.width, canvas.height) * .025, 0, Math.PI * 2)
        ctx.strokeStyle = '#000'; ctx.lineWidth = 4; ctx.stroke(); ctx.strokeStyle = '#fff'; ctx.lineWidth = 2; ctx.stroke(); ctx.restore()
      }
    },
    dispose() {},
  }
}

export function createPopoutRenderer(canvas, photo) {
  canvas.width = photo.width; canvas.height = photo.height
  const ctx = canvas.getContext('2d'), subject = document.createElement('canvas'), overlay = document.createElement('canvas')
  for (const c of [subject, overlay]) { c.width = photo.width; c.height = photo.height }
  return {
    draw(mask, { frame = .15, color = '#f4eee4', caption = '', captionColor = '#242424', editMask = false } = {}) {
      ctx.clearRect(0, 0, canvas.width, canvas.height)
      if (editMask) {
        ctx.drawImage(photo, 0, 0)
        const oc = overlay.getContext('2d'); oc.clearRect(0, 0, overlay.width, overlay.height)
        oc.fillStyle = '#48d7cf'; oc.fillRect(0, 0, overlay.width, overlay.height)
        oc.globalCompositeOperation = 'destination-in'; if (mask) oc.drawImage(mask, 0, 0)
        oc.globalCompositeOperation = 'source-over'; ctx.globalAlpha = .5; ctx.drawImage(overlay, 0, 0); ctx.globalAlpha = 1
        return
      }
      ctx.fillStyle = color; ctx.fillRect(0, 0, canvas.width, canvas.height)
      const inset = Math.min(canvas.width, canvas.height) * frame
      ctx.save(); ctx.beginPath(); ctx.rect(inset, inset, canvas.width - inset * 2, canvas.height - inset * 2); ctx.clip(); ctx.drawImage(photo, 0, 0); ctx.restore()
      ctx.strokeStyle = '#fff'; ctx.lineWidth = Math.max(4, Math.min(canvas.width, canvas.height) * .015)
      ctx.strokeRect(inset, inset, canvas.width - inset * 2, canvas.height - inset * 2)
      if (caption) {
        ctx.fillStyle = captionColor; ctx.font = `bold ${Math.round(Math.min(canvas.width, canvas.height) * .07)}px sans-serif`
        ctx.textAlign = 'center'; ctx.textBaseline = 'middle'
        ctx.fillText(caption, canvas.width / 2, canvas.height - inset * .55, canvas.width - inset)
      }
      const sc = subject.getContext('2d'); sc.clearRect(0, 0, subject.width, subject.height)
      sc.globalCompositeOperation = 'source-over'; sc.drawImage(photo, 0, 0)
      sc.globalCompositeOperation = 'destination-in'; if (mask) sc.drawImage(mask, 0, 0)
      else sc.clearRect(0, 0, subject.width, subject.height)
      sc.globalCompositeOperation = 'source-over'; ctx.drawImage(subject, 0, 0)
    },
    dispose() {},
  }
}

export function createSelectiveColorRenderer(canvas, photo) {
  canvas.width = photo.width; canvas.height = photo.height
  const ctx = canvas.getContext('2d'), gray = document.createElement('canvas'), color = document.createElement('canvas'), overlay = document.createElement('canvas')
  for (const c of [gray, color, overlay]) { c.width = photo.width; c.height = photo.height }
  const gc = gray.getContext('2d', { willReadFrequently: true }), cc = color.getContext('2d'), oc = overlay.getContext('2d')
  gc.drawImage(photo, 0, 0)
  const pixels = gc.getImageData(0, 0, gray.width, gray.height), source = new Uint8ClampedArray(pixels.data)
  return {
    draw(mask, { saturation = 0, editMask = false } = {}) {
      const data = pixels.data
      for (let i = 0; i < data.length; i += 4) {
        const luminance = source[i] * .2126 + source[i + 1] * .7152 + source[i + 2] * .0722
        data[i] = luminance + (source[i] - luminance) * saturation
        data[i + 1] = luminance + (source[i + 1] - luminance) * saturation
        data[i + 2] = luminance + (source[i + 2] - luminance) * saturation
      }
      gc.putImageData(pixels, 0, 0)
      cc.clearRect(0, 0, color.width, color.height); cc.drawImage(photo, 0, 0)
      cc.globalCompositeOperation = 'destination-in'; if (mask) cc.drawImage(mask, 0, 0); else cc.clearRect(0, 0, color.width, color.height)
      cc.globalCompositeOperation = 'source-over'
      ctx.clearRect(0, 0, canvas.width, canvas.height); ctx.drawImage(gray, 0, 0); ctx.drawImage(color, 0, 0)
      if (editMask && mask) {
        oc.clearRect(0, 0, overlay.width, overlay.height); oc.fillStyle = '#48d7cf'; oc.fillRect(0, 0, overlay.width, overlay.height)
        oc.globalCompositeOperation = 'destination-in'; oc.drawImage(mask, 0, 0); oc.globalCompositeOperation = 'source-over'
        ctx.globalAlpha = .48; ctx.drawImage(overlay, 0, 0); ctx.globalAlpha = 1
      }
    },
    dispose() {},
  }
}

export function selectiveColorPixel(pixel, saturation, selected) {
  const luminance = pixel[0] * .2126 + pixel[1] * .7152 + pixel[2] * .0722
  return selected ? pixel.slice(0, 3) : pixel.slice(0, 3).map(value => Math.round(luminance + (value - luminance) * saturation))
}

export function createLivingRenderer(canvas, photo) {
  const transparentMask = document.createElement('canvas')
  transparentMask.width = photo.width; transparentMask.height = photo.height
  const gl = createDepthRenderer(canvas, photo, transparentMask, { alphaMask: true })
  let lastMask = null, lastRevision = -1
  return {
    draw(mask, phase = 0, amount = .04, axis = 'horizontal', revision = 0) {
      if (mask && (mask !== lastMask || revision !== lastRevision)) {
        gl.updateMask(mask); lastMask = mask; lastRevision = revision
      }
      const wave = Math.sin(phase * Math.PI * 2)
      gl.draw(axis === 'horizontal' ? wave : Math.sin(phase * Math.PI) * .65, axis === 'vertical' ? wave : Math.cos(phase * Math.PI) * .2, Math.min(1, amount * 9))
    },
    dispose: gl.dispose,
  }
}
