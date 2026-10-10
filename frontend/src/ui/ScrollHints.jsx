import { useEffect, useRef, useState } from 'react'
import { ChevronLeft, ChevronRight } from 'lucide-react'

/*
 * A horizontally scrolling row that shows arrows at both ends whenever it overflows, so people on phones can tell
 * there are more actions. The arrow toward hidden content is bright; the one at an end is dimmed but still shown,
 * which makes the row read as scrollable in both directions. Tapping an arrow scrolls most of a screen width.
 */
export default function ScrollHints({ as: Tag = 'div', className = '', children, ...props }) {
  const ref = useRef(null)
  const [state, setState] = useState({ overflow: false, start: true, end: true })
  useEffect(() => {
    const node = ref.current
    if (!node) return
    const measure = () => {
      const max = node.scrollWidth - node.clientWidth
      const next = { overflow: max > 2, start: node.scrollLeft <= 2, end: node.scrollLeft >= max - 2 }
      setState(previous => previous.overflow === next.overflow && previous.start === next.start && previous.end === next.end ? previous : next)
    }
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(node)
    for (const child of node.children) observer.observe(child)
    node.addEventListener('scroll', measure, { passive: true })
    return () => { observer.disconnect(); node.removeEventListener('scroll', measure) }
  }, [children])
  const scroll = direction => ref.current?.scrollBy({ left: direction * ref.current.clientWidth * .7, behavior: 'smooth' })
  const edges = state.overflow ? (state.start ? '' : ' fade-start') + (state.end ? '' : ' fade-end') : ''
  return <div className={'scroll-hints' + (state.overflow ? ' has-overflow' : '')}>
    <Tag ref={ref} className={className + edges} {...props}>{children}</Tag>
    {state.overflow && <>
      <button type="button" className="scroll-hint start" tabIndex={-1} aria-hidden="true" disabled={state.start} onClick={() => scroll(-1)}><ChevronLeft size={18} /></button>
      <button type="button" className="scroll-hint end" tabIndex={-1} aria-hidden="true" disabled={state.end} onClick={() => scroll(1)}><ChevronRight size={18} /></button>
    </>}
  </div>
}
