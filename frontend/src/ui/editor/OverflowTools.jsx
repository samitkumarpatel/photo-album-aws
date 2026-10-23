import { Children, isValidElement, useLayoutEffect, useRef, useState } from 'react'
function labelOf(node) {
  if (typeof node === 'string' || typeof node === 'number') return String(node)
  if (!isValidElement(node)) return ''
  return node.props['aria-label'] || Children.toArray(node.props.children).map(labelOf).join(' ').trim()
}
export default function OverflowTools({ as: Tag = 'div', children, className = '', ...props }) {
  const options = Children.toArray(children).filter(child => isValidElement(child) && child.type === 'button')
  const row = useRef(null), measure = useRef(null)
  const [count, setCount] = useState(0)
  useLayoutEffect(() => {
    const fit = () => {
      const widths = [...measure.current.children].map(node => { const style = getComputedStyle(node); return node.getBoundingClientRect().width + parseFloat(style.marginLeft) + parseFloat(style.marginRight) })
      const style = getComputedStyle(row.current)
      const available = row.current.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight)
      const gap = parseFloat(style.columnGap) || 0
      const total = widths.reduce((sum, width) => sum + width + gap, -gap)
      let visible = widths.length
      if (total > available) {
        let used = 0; visible = 0
        for (const width of widths) {
          if (used + width + gap + 132 > available) break
          used += width + gap; visible++
        }
      }
      setCount(previous => previous === visible ? previous : visible)
    }
    fit()
    const observer = new ResizeObserver(fit)
    observer.observe(row.current); observer.observe(measure.current)
    return () => observer.disconnect()
  }, [children])
  const hidden = options.slice(count)
  const selected = hidden.findIndex(option => option.props['aria-pressed'] || option.props.className?.split(' ').includes('selected'))
  return <Tag {...props} className={className + ' ed-overflow'} ref={row}>
    <div className="ed-overflow-measure" ref={measure} aria-hidden="true" inert>{options}</div>
    {options.slice(0, count)}
    {!!hidden.length && <select className={'ed-overflow-select' + (selected >= 0 ? ' selected' : '')} aria-label={'More ' + (props['aria-label'] || 'tools').toLowerCase()} value={selected >= 0 ? String(selected) : ''}
      onChange={event => hidden[Number(event.target.value)]?.props.onClick?.()}>
      <option value="" disabled>More ({hidden.length})</option>
      {hidden.map((option, index) => <option key={option.key} value={index} disabled={option.props.disabled}>{labelOf(option)}{option.props['aria-pressed'] ? ' ✓' : ''}</option>)}
    </select>}
  </Tag>
}
