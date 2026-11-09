// Les charges de travail du banc d'essai. Chaque mesure est la médiane de trois passes.
(() => {
  const median = (values) => values.slice().sort((a, b) => a - b)[Math.floor(values.length / 2)];
  const timed = (fn) => { const start = performance.now(); fn(); return performance.now() - start; };
  const three = (fn) => Math.round(median([timed(fn), timed(fn), timed(fn)]) * 10) / 10;
  const asyncThree = async (fn) => {
    const out = [];
    for (let i = 0; i < 3; i++) { const start = performance.now(); await fn(); out.push(performance.now() - start); }
    return Math.round(median(out) * 10) / 10;
  };
  let sink = 0;

  // Module WebAssembly : une boucle sur des entiers et des flottants, assemblée ici pour avoir les bonnes longueurs.
  const WASM = (() => {
    const code = [0x44, 0, 0, 0, 0, 0, 0, 0, 0, 0x21, 2, 0x03, 0x40, 0x20, 2, 0x20, 1, 0xb7, 0x44, 0, 0, 0, 0, 0, 0, 0xe0, 0x3f, 0xa2, 0xa0, 0x21, 2, 0x20, 1, 0x41, 1, 0x6a, 0x22, 1, 0x20, 0, 0x48, 0x0d, 0, 0x0b, 0x20, 2, 0x0b];
    const body = [2, 1, 0x7f, 1, 0x7c, ...code];
    return new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0, 1, 6, 1, 0x60, 1, 0x7f, 1, 0x7c, 3, 2, 1, 0, 7, 5, 1, 1, 102, 0, 0, 10, body.length + 2, 1, body.length, ...body]);
  })();

  const tests = {
    // Sensible au compilateur : quelques dizaines de ms avec JIT, plus d'une seconde sans.
    boucleEntiers: () => { let s = 0; for (let i = 0; i < 50000000; i++) s = (s + i) | 0; sink += s; },
    boucleFlottants: () => { let x = 0.5; for (let i = 0; i < 12000000; i++) x = (x * 1.000001 + i % 7) % 1000.5; sink += x; },
    crible: () => { const n = 3000000; const s = new Uint8Array(n); let c = 0; for (let i = 2; i < n; i++) { if (!s[i]) { c++; for (let j = i * 2; j < n; j += i) s[j] = 1; } } sink += c; },
    objets: () => { const list = []; for (let i = 0; i < 300000; i++) list.push({ a: i, b: { c: i * 2 }, d: 'x' }); let t = 0; for (const o of list) t += o.a + o.b.c; sink += t; },
    chaines: () => { let parts = []; for (let i = 0; i < 150000; i++) parts.push('valeur' + i); const joined = parts.join(','); sink += joined.split(',').map((p) => p.toUpperCase()).length; },
    regex: () => { const text = 'lorem ipsum dolor 12345 sit amet, consectetur 67890 '.repeat(4000); sink += (text.match(/\b\d+\b/g) || []).length + text.replace(/[aeiou]/g, '*').length; },
    json: () => { const data = Array.from({ length: 30000 }, (_, i) => ({ id: i, nom: 'n' + i, liste: [i, i + 1], ok: i % 2 === 0 })); sink += JSON.parse(JSON.stringify(data)).length; },
    tri: () => { const values = Array.from({ length: 300000 }, (_, i) => (i * 7919) % 100003); values.sort((a, b) => a - b); sink += values[10]; },
    dom: () => { const root = document.createElement('div'); document.body.append(root); for (let i = 0; i < 3000; i++) { const e = document.createElement('div'); e.className = 'c' + (i % 7); e.style.cssText = 'padding:2px;width:' + (i % 300) + 'px'; e.textContent = 'ligne ' + i; root.append(e); } sink += root.offsetHeight; for (const e of root.children) { e.style.width = '120px'; } sink += root.offsetHeight; root.remove(); },
    canvas2d: () => { const c = document.createElement('canvas'); c.width = 720; c.height = 720; const g = c.getContext('2d'); for (let i = 0; i < 4000; i++) { g.fillStyle = 'rgb(' + (i % 255) + ',80,120)'; g.fillRect(i % 700, (i * 3) % 700, 18, 18); } g.font = '14px sans-serif'; for (let i = 0; i < 600; i++) g.fillText('texte ' + i, (i * 11) % 650, (i * 7) % 700); sink += g.getImageData(0, 0, 8, 8).data[0]; },
  };

  async function webassembly() {
    if (!globalThis.WebAssembly) return null;
    try {
      const module = await WebAssembly.instantiate(WASM);
      return three(() => { sink += module.instance.exports.f(30000000); });
    } catch (error) {
      return 'erreur:' + error.message;
    }
  }

  function webgl() {
    const canvas = document.createElement('canvas');
    canvas.width = 720;
    canvas.height = 720;
    const gl = canvas.getContext('webgl', { preserveDrawingBuffer: true, antialias: false });
    if (!gl) return { disponible: false };
    const info = gl.getExtension('WEBGL_debug_renderer_info');
    const shader = (type, source) => { const s = gl.createShader(type); gl.shaderSource(s, source); gl.compileShader(s); return s; };
    const program = gl.createProgram();
    gl.attachShader(program, shader(gl.VERTEX_SHADER, 'attribute vec2 p;uniform float t;varying float v;void main(){v=p.x;gl_Position=vec4(p.x*cos(t)-p.y*sin(t),p.x*sin(t)+p.y*cos(t),0,1);}'));
    gl.attachShader(program, shader(gl.FRAGMENT_SHADER, 'precision mediump float;varying float v;void main(){gl_FragColor=vec4(fract(v*7.),.4,.6,1);}'));
    gl.linkProgram(program);
    gl.useProgram(program);
    const points = new Float32Array(60000);
    for (let i = 0; i < points.length; i++) points[i] = (((i * 7919) % 2000) / 1000) - 1;
    gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
    gl.bufferData(gl.ARRAY_BUFFER, points, gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    const time = gl.getUniformLocation(program, 't');
    const pixels = new Uint8Array(4);
    const start = performance.now();
    const frames = 40;
    for (let f = 0; f < frames; f++) {
      gl.clearColor(0, 0, 0, 1);
      gl.clear(gl.COLOR_BUFFER_BIT);
      gl.uniform1f(time, f / 10);
      gl.drawArrays(gl.TRIANGLES, 0, 30000);
      gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, pixels);
    }
    const total = performance.now() - start;
    return {
      disponible: true,
      rendu: info ? gl.getParameter(info.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER),
      images40ms: Math.round(total),
      erreur: gl.getError(),
      webgl2: !!document.createElement('canvas').getContext('webgl2'),
    };
  }

  globalThis.runBench = async () => {
    const out = {};
    for (const [name, fn] of Object.entries(tests)) out[name] = three(fn);
    out.wasm = await webassembly();
    out.webgl = webgl();
    out.webgpu = !!navigator.gpu;
    out.speech = !!globalThis.speechSynthesis;
    out.ua = navigator.userAgent;
    out.sink = sink > 0;
    return out;
  };
})();
