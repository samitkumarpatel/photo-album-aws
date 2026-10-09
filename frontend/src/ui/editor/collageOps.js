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

export const LAYOUTS = [
  ['smart', 'Smart'], ['grid', 'Grid'], ['mosaic', 'Mosaic'], ['feature', 'Feature left'],
  ['hero-top', 'Feature top'], ['horizontal', 'Side by side'], ['vertical', 'Stacked'],
]

/** Layouts that make sense for this many photos. */
export const layoutsFor = count => LAYOUTS.filter(([id]) =>
  id === 'smart' || id === 'grid' || (id === 'mosaic' && count > 2) || ((id === 'feature' || id === 'hero-top') && count > 2) || ((id === 'horizontal' || id === 'vertical') && count <= 4))

// Every way to split n photos, in order, into consecutive lines of at most `max` photos.
function splits(n, max) {
  if (n === 0) return [[]]
  const all = []
  for (let first = 1; first <= Math.min(max, n); first++) for (const rest of splits(n - first, max)) all.push([first, ...rest])
  return all
}

/*
 * Rows whose heights follow the photos' shapes: within a row every photo keeps its proportions, so the
 * only distortion is one shared stretch factor. The split (by rows or by columns) with the least stretch wins,
 * which means the least cropping.
 */
function smartCells(aspects, ratio) {
  const clamp = a => Math.min(2.2, Math.max(.45, a || 1))
  const best = (shapes, areaRatio) => {
    let winner = null
    for (const sizes of splits(shapes.length, 4)) {
      let i = 0
      const lines = sizes.map(size => { const line = shapes.slice(i, i + size); i += size; return line })
      const total = lines.reduce((sum, line) => sum + areaRatio / line.reduce((s, a) => s + a, 0), 0)
      const cost = Math.abs(Math.log(total)) + .06 * (Math.max(...sizes) - Math.min(...sizes)) + .02 * sizes.length
      if (!winner || cost < winner.cost) winner = { cost, lines, total }
    }
    const cells = []
    let y = 0
    for (const line of winner.lines) {
      const sum = line.reduce((s, a) => s + a, 0), h = areaRatio / sum / winner.total
      let x = 0
      for (const a of line) { cells.push({ x, y, w: a / sum, h }); x += a / sum }
      y += h
    }
    return { cells, cost: winner.cost }
  }
  const shapes = aspects.map(clamp)
  const rows = best(shapes, ratio)
  const columns = best(shapes.map(a => 1 / a), 1 / ratio)
  return rows.cost <= columns.cost ? rows.cells : columns.cells.map(c => ({ x: c.y, y: c.x, w: c.h, h: c.w }))
}

/** Cells in unit coordinates (0–1 across the photo area) for a layout. */
export function layoutCells(layout, aspects, ratio) {
  if (layout === 'smart') return smartCells(aspects, ratio)
  return collageCells(aspects.length, 1, 1, layout, 0)
}

/** Photo area, title band and pixel cells for a canvas of the given size. */
export function collageGeometry(aspects, options, width, height) {
  const titleH = options.title ? height * .1 : 0
  const top = options.title && options.titlePosition === 'top' ? titleH : 0
  const areaH = height - titleH
  const gap = Math.min(width, height) * options.spacing / 100
  const cells = layoutCells(options.layout, aspects, width / areaH).map(c => ({
    x: gap / 2 + c.x * (width - gap) + gap / 2,
    y: top + gap / 2 + c.y * (areaH - gap) + gap / 2,
    w: Math.max(1, c.w * (width - gap) - gap),
    h: Math.max(1, c.h * (areaH - gap) - gap),
  }))
  return { cells, title: { y: options.titlePosition === 'top' ? 0 : areaH, h: titleH } }
}

/** Where a photo is drawn inside its cell, after border, caption, fit, zoom and framing. */
export function framePlacement(image, cell, options, frame = {}, canvasSide) {
  const captionH = frame.caption ? cell.h * .13 : 0
  const border = (options.border || 0) * canvasSide / 100
  const box = { x: cell.x + border, y: cell.y + border, w: Math.max(1, cell.w - 2 * border), h: Math.max(1, cell.h - captionH - 2 * border) }
  const cover = options.fit !== 'contain'
  const scale = (cover ? Math.max : Math.min)(box.w / image.width, box.h / image.height) * (cover ? frame.zoom || 1 : 1)
  const w = image.width * scale, h = image.height * scale
  return { box, captionH, w, h, x: box.x + (box.w - w) * (frame.x ?? .5), y: box.y + (box.h - h) * (frame.y ?? .5) }
}

export const collageFont = style => style === 'serif' ? '"Fraunces Variable", Georgia, serif' : '"Inter Variable", system-ui, sans-serif'

export function renderCollage(canvas, images, options, maxSide = 900) {
  const { ratio, color } = options
  canvas.width = Math.round(ratio >= 1 ? maxSide : maxSide * ratio)
  canvas.height = Math.round(ratio >= 1 ? maxSide / ratio : maxSide)
  const ctx = canvas.getContext('2d')
  ctx.fillStyle = color
  ctx.fillRect(0, 0, canvas.width, canvas.height)
  ctx.imageSmoothingQuality = 'high'
  const side = Math.min(canvas.width, canvas.height)
  const font = collageFont(options.font)
  const { cells, title } = collageGeometry(images.map(image => image.width / image.height), options, canvas.width, canvas.height)
  images.forEach((image, i) => {
    const cell = cells[i], frame = options.frames?.[i] || {}
    const place = framePlacement(image, cell, options, frame, side)
    const radius = Math.min(place.box.w, place.box.h) * (options.radius || 0) / 100
    ctx.fillStyle = options.borderColor || '#ffffff'
    ctx.beginPath(); ctx.roundRect(cell.x, cell.y, cell.w, cell.h, (options.border ? radius : 0)); ctx.fill()
    ctx.save()
    ctx.beginPath(); ctx.roundRect(place.box.x, place.box.y, place.box.w, place.box.h, radius); ctx.clip()
    ctx.drawImage(image, place.x, place.y, place.w, place.h)
    ctx.restore()
    if (frame.caption) {
      ctx.fillStyle = options.textColor || '#222222'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle'
      ctx.font = Math.max(10, place.captionH * .45) + 'px ' + font
      ctx.fillText(frame.caption, cell.x + cell.w / 2, cell.y + cell.h - place.captionH / 2, cell.w * .9)
    }
  })
  if (options.title) {
    ctx.fillStyle = options.textColor || '#222222'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle'
    ctx.font = '600 ' + title.h * .42 + 'px ' + font
    ctx.fillText(options.title, canvas.width / 2, title.y + title.h / 2, canvas.width * .9)
  }
  return canvas
}
