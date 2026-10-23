// Pointer coordinates are normalized to the displayed, oriented photo.
export function drawnCrop(startX, startY, endX, endY) {
  const bound = value => Math.max(0, Math.min(1, value))
  const x1 = bound(startX), y1 = bound(startY), x2 = bound(endX), y2 = bound(endY)
  const w = Math.max(.06, Math.abs(x2 - x1)), h = Math.max(.06, Math.abs(y2 - y1))
  return { x: Math.min(Math.min(x1, x2), 1 - w), y: Math.min(Math.min(y1, y2), 1 - h), w, h }
}
