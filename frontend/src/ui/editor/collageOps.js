/** Cells fill each row; the final row expands to avoid empty slots. */
export function collageCells(count, width, height, layout, spacing) {
  const gap = Math.min(width, height) * spacing / 100
  const grid = (n, x, y, w, h, columns) => {
    const rows = Math.ceil(n / columns)
    const cellH = (h - gap * (rows - 1)) / rows
    return Array.from({ length: n }, (_, i) => {
      const row = Math.floor(i / columns), col = i % columns
      const inRow = Math.min(columns, n - row * columns)
      const cellW = (w - gap * (inRow - 1)) / inRow
      return { x: x + col * (cellW + gap), y: y + row * (cellH + gap), w: cellW, h: cellH }
    })
  }
  const innerW = width - 2 * gap, innerH = height - 2 * gap
  if (layout === 'feature' && count > 1) {
    const heroW = (innerW - gap) * 0.55
    return [{ x: gap, y: gap, w: heroW, h: innerH }, ...grid(count - 1, gap * 2 + heroW, gap, innerW - heroW - gap, innerH, count > 4 ? 2 : 1)]
  }
  if (layout === 'hero-top' && count > 1) {
    const heroH = (innerH - gap) * .55
    return [{ x: gap, y: gap, w: innerW, h: heroH }, ...grid(count - 1, gap, gap * 2 + heroH, innerW, innerH - heroH - gap, Math.min(3, count - 1))]
  }
  if (layout === 'mosaic' && count > 2) {
    const upper = Math.ceil(count / 2), rowH = (innerH - gap) / 2
    return [...grid(upper, gap, gap, innerW, rowH, upper), ...grid(count - upper, gap, gap * 2 + rowH, innerW, rowH, count - upper)]
  }
  const cols = layout === 'horizontal' ? count : layout === 'vertical' ? 1 : Math.ceil(Math.sqrt(count))
  return grid(count, gap, gap, innerW, innerH, cols)
}

export function renderCollage(canvas, images, options, maxSide = 900) {
  const { ratio, layout, spacing, color, fit } = options
  canvas.width = Math.round(ratio >= 1 ? maxSide : maxSide * ratio)
  canvas.height = Math.round(ratio >= 1 ? maxSide / ratio : maxSide)
  const ctx = canvas.getContext('2d')
  ctx.fillStyle = color
  ctx.fillRect(0, 0, canvas.width, canvas.height)
  ctx.imageSmoothingQuality = 'high'
  const titleH = options.title ? canvas.height * .09 : 0
  const cells = collageCells(images.length, canvas.width, canvas.height - titleH, layout, spacing)
  images.forEach((image, i) => {
    const cell = cells[i]
    const frame = options.frames?.[i] || {}
    const captionH = frame.caption ? cell.h * .13 : 0
    const photoH = cell.h - captionH
    const border = (options.border || 0) * Math.min(canvas.width, canvas.height) / 100
    const box = { x: cell.x + border, y: cell.y + border, w: Math.max(1, cell.w - 2 * border), h: Math.max(1, photoH - 2 * border) }
    ctx.fillStyle = options.borderColor || '#ffffff'; ctx.fillRect(cell.x, cell.y, cell.w, cell.h)
    const scale = (fit === 'contain' ? Math.min : Math.max)(box.w / image.width, box.h / image.height) * (fit === 'cover' ? (frame.zoom || 1) : 1)
    const w = image.width * scale, h = image.height * scale
    ctx.save()
    ctx.beginPath(); ctx.roundRect(box.x, box.y, box.w, box.h, Math.min(box.w, box.h) * (options.radius || 0) / 100); ctx.clip()
    ctx.drawImage(image, box.x + (box.w - w) * (frame.x ?? .5), box.y + (box.h - h) * (frame.y ?? .5), w, h)
    ctx.restore()
    if (frame.caption) {
      ctx.fillStyle = options.textColor || '#222222'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle'
      ctx.font = Math.max(10, captionH * .45) + 'px sans-serif'
      ctx.fillText(frame.caption, cell.x + cell.w / 2, cell.y + photoH + captionH / 2, cell.w * .9)
    }
  })
  if (options.title) { ctx.fillStyle = options.textColor || '#222222'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle'; ctx.font = canvas.height * .035 + 'px sans-serif'; ctx.fillText(options.title, canvas.width / 2, canvas.height - titleH / 2, canvas.width * .9) }
  return canvas
}
