// Self-contained so the same renderer can power an offline HTML download.
export function createDepthRenderer(canvas, photo, depth, { alphaMask = false } = {}) {
  const gl = canvas.getContext('webgl', { alpha: false, antialias: false, preserveDrawingBuffer: true })
  if (!gl) throw new Error('3D preview needs WebGL. Try another browser or enable graphics acceleration.')
  const shaders = [], textures = []
  const program = gl.createProgram()
  const buffer = gl.createBuffer()
  const dispose = () => {
    textures.forEach(texture => gl.deleteTexture(texture))
    shaders.forEach(shader => gl.deleteShader(shader))
    gl.deleteBuffer(buffer)
    gl.deleteProgram(program)
  }
  try {
    for (const [type, source] of [
      [gl.VERTEX_SHADER, 'attribute vec2 position; varying vec2 uv; void main(){uv=position*.5+.5; gl_Position=vec4(position,0.,1.);}'],
      [gl.FRAGMENT_SHADER, `precision mediump float;
        varying vec2 uv; uniform sampler2D photo; uniform sampler2D depth;
        uniform vec2 offset; uniform float strength; uniform float zoom; uniform float alphaMask;
        void main(){
          vec2 base=(uv-.5)*.90/zoom+.5;
          vec2 sampleUV=base;
          for(int i=0;i<5;i++) {
            vec4 depthPixel=texture2D(depth,sampleUV);
            float d=alphaMask>.5?depthPixel.a:depthPixel.r;
            sampleUV=base-offset*(alphaMask>.5?d:d-.5)*strength*.065;
          }
          gl_FragColor=texture2D(photo,clamp(sampleUV,vec2(.001),vec2(.999)));
        }`],
    ]) {
      const shader = gl.createShader(type)
      shaders.push(shader)
      gl.shaderSource(shader, source)
      gl.compileShader(shader)
      if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) throw new Error('Your browser could not prepare the 3D effect.')
      gl.attachShader(program, shader)
    }
    gl.linkProgram(program)
    if (!gl.getProgramParameter(program, gl.LINK_STATUS)) throw new Error('Your browser could not start the 3D effect.')
    gl.useProgram(program)
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1, 1,-1, -1,1, -1,1, 1,-1, 1,1]), gl.STATIC_DRAW)
    const position = gl.getAttribLocation(program, 'position')
    gl.enableVertexAttribArray(position)
    gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0)
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true)
    for (const [i, source] of [photo, depth].entries()) {
      const texture = gl.createTexture()
      textures.push(texture)
      gl.activeTexture(gl.TEXTURE0 + i)
      gl.bindTexture(gl.TEXTURE_2D, texture)
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR)
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, source)
      gl.uniform1i(gl.getUniformLocation(program, i ? 'depth' : 'photo'), i)
    }
    const offset = gl.getUniformLocation(program, 'offset'), strength = gl.getUniformLocation(program, 'strength'), zoom = gl.getUniformLocation(program, 'zoom')
    gl.uniform1f(gl.getUniformLocation(program, 'alphaMask'), alphaMask ? 1 : 0)
    return {
      draw(x = 0, y = 0, amount = 0.6, scale = 1) {
        gl.viewport(0, 0, canvas.width, canvas.height)
        gl.uniform2f(offset, Math.max(-1, Math.min(1, x)), Math.max(-1, Math.min(1, y)))
        gl.uniform1f(strength, Math.max(0, Math.min(1, amount)))
        gl.uniform1f(zoom, Math.max(1, Math.min(1.2, scale)))
        gl.drawArrays(gl.TRIANGLES, 0, 6)
      },
      updateMask(source) {
        if (!alphaMask) return
        gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, textures[1])
        gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true)
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, source)
      },
      dispose,
    }
  } catch (error) { dispose(); throw error }
}

export function depthDownloadHtml(photo, depth, strength) {
  // Only generated image data URLs enter script context; filenames never do.
  if (!/^data:image\/jpeg;base64,[A-Za-z0-9+/=]+$/.test(photo) || !/^data:image\/png;base64,[A-Za-z0-9+/=]+$/.test(depth)) throw new Error('Invalid 3D image data')
  const amount = Math.max(0, Math.min(1, Number(strength) || 0))
  return `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>3D photo</title>
<style>body{margin:0;background:#151719;color:#fff;font:16px system-ui;text-align:center}canvas{display:block;max-width:96vw;max-height:80vh;width:auto;height:auto;margin:20px auto;touch-action:none}button{padding:10px}</style>
<h1>3D photo</h1><p id="hint">Drag the photo or use arrow keys to explore. Press Home to reset.</p><canvas tabindex="0" role="img" aria-label="Interactive 3D photo"></canvas><button id="reset">Reset view</button> <button id="tilt">Use phone tilt</button>
<script>const createDepthRenderer=${createDepthRenderer.toString()};
const photo=new Image(),depth=new Image(),canvas=document.querySelector('canvas');
photo.src=${JSON.stringify(photo)};depth.src=${JSON.stringify(depth)};
Promise.all([photo.decode(),depth.decode()]).then(()=>{
canvas.width=photo.width;canvas.height=photo.height;
const renderer=createDepthRenderer(canvas,photo,depth);let x=0,y=0;
const draw=()=>renderer.draw(x,y,${amount});draw();
canvas.onpointerdown=e=>{canvas.setPointerCapture(e.pointerId);canvas.focus()};
canvas.onpointermove=e=>{if(!canvas.hasPointerCapture(e.pointerId))return;const r=canvas.getBoundingClientRect();x=(e.clientX-r.left)/r.width*2-1;y=1-(e.clientY-r.top)/r.height*2;draw()};
canvas.onkeydown=e=>{if(!['ArrowLeft','ArrowRight','ArrowUp','ArrowDown','Home'].includes(e.key))return;e.preventDefault();if(e.key==='Home'){x=y=0}else{x=Math.max(-1,Math.min(1,x+(e.key==='ArrowRight'?.1:e.key==='ArrowLeft'?-.1:0)));y=Math.max(-1,Math.min(1,y+(e.key==='ArrowUp'?.1:e.key==='ArrowDown'?-.1:0)))}draw()};
document.querySelector('#reset').onclick=()=>{x=y=0;draw()};
document.querySelector('#tilt').onclick=async()=>{const hint=document.querySelector('#hint');try{const Orientation=window.DeviceOrientationEvent;if(!Orientation)throw Error('Device tilt is unavailable in this browser.');if(typeof Orientation.requestPermission==='function'&&await Orientation.requestPermission()!=='granted')throw Error('Motion access was not allowed.');let origin=null;window.addEventListener('deviceorientation',e=>{if(!Number.isFinite(e.gamma)||!Number.isFinite(e.beta))return;if(!origin)origin={gamma:e.gamma,beta:e.beta};x=Math.max(-1,Math.min(1,(e.gamma-origin.gamma)/30));y=Math.max(-1,Math.min(1,(e.beta-origin.beta)/30));draw()});hint.textContent='Tilt your phone to explore. Drag the photo to adjust.'}catch(e){hint.textContent=e.message+' You can still drag the photo.'}};
canvas.addEventListener('webglcontextlost',()=>{document.querySelector('#hint').textContent='Graphics connection lost. Reload this file to try again.'});
}).catch(()=>{document.querySelector('#hint').textContent='This browser could not display the 3D photo. Try a browser with WebGL enabled.'});</script></html>`
}
