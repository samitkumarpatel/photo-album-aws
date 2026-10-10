import { useEffect, useRef, useState } from 'react'
import { mediaUrl, isVideo } from '../media.js'
export default function Slideshow({ items, presentation, Dialog, close }) {
  const [index, setIndex] = useState(0), [playing, setPlaying] = useState(true)
  const video = useRef(null)
  useEffect(() => {
    if (!video.current) return
    if (playing) video.current.play().catch(() => setPlaying(false))
    else video.current.pause()
  }, [playing, index])
  const next = () => setIndex(i => (i + 1) % items.length)
  const current = items[index]
  useEffect(() => {
    if (!playing || !current || isVideo(current)) return
    const timer = setTimeout(() => setIndex(i => (i + 1) % items.length), (presentation?.slideshowSeconds || 5) * 1000)
    return () => clearTimeout(timer)
  }, [playing, index, current, items.length, presentation?.slideshowSeconds])
  if (!current) return null
  return <Dialog title={presentation?.brandName || 'Slideshow'} close={close} wide><div className={'slideshow album-theme-' + (presentation?.theme || 'classic')}>
    {presentation?.logo && <img className="slideshow-logo" src={presentation.logo} alt="Brand logo" />}
    {isVideo(current) ? <video ref={video} key={current.id} src={mediaUrl(current, 'original')} controls playsInline autoPlay={playing} onEnded={() => { if (playing) next() }} onError={() => setPlaying(false)} /> : <img key={current.id} src={mediaUrl(current, 'display')} alt={current.filename} onError={() => setPlaying(false)} />}
    {presentation?.watermark && <span className="slideshow-watermark">{presentation.watermark}</span>}
  </div><div className="dialog-actions"><button className="button secondary" onClick={() => setIndex(i => (i + items.length - 1) % items.length)}>Previous</button><button className="button secondary" onClick={() => setPlaying(p => !p)}>{playing ? 'Pause' : 'Play'}</button><span role="status">{index + 1} / {items.length}</span><button className="button secondary" onClick={next}>Next</button></div></Dialog>
}
