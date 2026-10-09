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
  const cells = collageCells(images.length, canvas.width, canvas.height, layout, spacing)
  images.forEach((image, i) => {
    const cell = cells[i]
    const scale = (fit === 'contain' ? Math.min : Math.max)(cell.w / image.width, cell.h / image.height)
    const w = image.width * scale, h = image.height * scale
    ctx.save()
    ctx.beginPath(); ctx.rect(cell.x, cell.y, cell.w, cell.h); ctx.clip()
    ctx.drawImage(image, cell.x + (cell.w - w) / 2, cell.y + (cell.h - h) / 2, w, h)
    ctx.restore()
  })
  return canvas
}
