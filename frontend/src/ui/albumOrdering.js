export function sortMedia(items, sort = 'newest', order = []) {
  const ranks = new Map(order.map((id, index) => [id, index]))
  const date = item => Date.parse((sort === 'captured' ? item.takenAt : null) || item.uploadedAt) || 0
  return [...items].sort((a, b) => {
    if (sort === 'custom') {
      const rank = (ranks.get(a.id) ?? Infinity) - (ranks.get(b.id) ?? Infinity)
      if (rank && !Number.isNaN(rank)) return rank
    }
    if (sort === 'filename') return a.filename.localeCompare(b.filename, undefined, { numeric: true, sensitivity: 'base' }) || a.id.localeCompare(b.id)
    return (sort === 'oldest' ? date(a) - date(b) : date(b) - date(a)) || a.id.localeCompare(b.id)
  })
}

export function moveItem(items, from, to) {
  if (from < 0 || to < 0 || from >= items.length || to >= items.length || from === to) return items
  const next = [...items]
  next.splice(to, 0, next.splice(from, 1)[0])
  return next
}
