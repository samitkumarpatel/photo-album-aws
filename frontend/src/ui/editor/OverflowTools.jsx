import ScrollHints from '../ScrollHints.jsx'

/* Keep editor tools on one line and make any overflow discoverable on touch and desktop. */
export default function OverflowTools({ as: Tag = 'div', children, className = '', ...props }) {
  return <ScrollHints as={Tag} className={className + ' ed-overflow'} {...props}>
    {children}
  </ScrollHints>
}
