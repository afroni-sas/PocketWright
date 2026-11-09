// Mesure ce qu'une page peut lire pour identifier un navigateur. Chargé dans la page, une iframe, un worker et un
// service worker, pour vérifier que tous voient la même chose.
(() => {
  const hex = (buffer) => [...new Uint8Array(buffer)].map((byte) => byte.toString(16).padStart(2, '0')).join('');
  const digest = async (value) => hex(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(String(value)))).slice(0, 16);
  // Une API qui ne répond jamais (WebView hors écran) ne doit pas bloquer toute la mesure.
  const attempt = async (fn, ms = 6000) => {
    let timer;
    try {
      return await Promise.race([fn(), new Promise((resolve) => { timer = setTimeout(() => resolve('délai dépassé'), ms); })]);
    } catch (error) {
      return `erreur:${error.name}`;
    } finally {
      clearTimeout(timer);
    }
  };

  function paint(context) {
    context.textBaseline = 'top';
    context.font = '16px Arial';
    context.fillStyle = '#f60';
    context.fillRect(10, 1, 62, 20);
    context.fillStyle = '#069';
    context.fillText('PocketWright, é 😀', 2, 15);
    context.fillStyle = 'rgba(102, 204, 0, 0.7)';
    context.fillText('PocketWright, é 😀', 4, 17);
    context.globalCompositeOperation = 'multiply';
    context.fillStyle = 'rgb(255, 0, 255)';
    context.beginPath();
    context.arc(50, 25, 25, 0, Math.PI * 2);
    context.closePath();
    context.fill();
  }

  const PERMISSIONS = ['geolocation', 'notifications', 'midi', 'camera', 'microphone', 'background-fetch', 'background-sync', 'persistent-storage', 'accelerometer', 'gyroscope', 'magnetometer', 'screen-wake-lock', 'nfc', 'clipboard-read', 'clipboard-write', 'payment-handler', 'idle-detection', 'storage-access', 'window-management'];

  // Disponible partout, y compris dans les workers.
  async function core() {
    const nav = navigator;
    const entropy = nav.userAgentData
      ? await attempt(() => nav.userAgentData.getHighEntropyValues(['architecture', 'bitness', 'model', 'platformVersion', 'uaFullVersion', 'fullVersionList', 'wow64', 'formFactors']))
      : null;
    const zone = Intl.DateTimeFormat().resolvedOptions();
    const offscreen = await attempt(async () => {
      const canvas = new OffscreenCanvas(240, 60);
      paint(canvas.getContext('2d'));
      const blob = await canvas.convertToBlob();
      return digest(hex(await blob.arrayBuffer()));
    });
    return {
      userAgent: nav.userAgent,
      appVersion: nav.appVersion,
      platform: nav.platform,
      language: nav.language,
      languages: [...(nav.languages ?? [])].join(','),
      hardwareConcurrency: nav.hardwareConcurrency,
      deviceMemory: nav.deviceMemory ?? null,
      webdriver: nav.webdriver ?? null,
      brands: nav.userAgentData ? JSON.stringify(nav.userAgentData.brands) : null,
      mobile: nav.userAgentData?.mobile ?? null,
      uaPlatform: nav.userAgentData?.platform ?? null,
      highEntropy: entropy ? JSON.stringify(entropy) : null,
      permissions: await attempt(async () => {
        const states = {};
        for (const name of PERMISSIONS) states[name] = (await nav.permissions.query({ name })).state;
        states.pushVisible = (await nav.permissions.query({ name: 'push', userVisibleOnly: true })).state;
        return states;
      }),
      notification: await attempt(async () => {
        if (typeof Notification === 'undefined') return 'absente';
        let construct = 'aucune erreur';
        try { new Notification('essai'); } catch (error) { construct = error.message; }
        return {
          permission: Notification.permission,
          maxActions: Notification.maxActions,
          source: Function.prototype.toString.call(Notification),
          getter: Function.prototype.toString.call(Object.getOwnPropertyDescriptor(Notification, 'permission').get),
          construct,
          parent: Object.getPrototypeOf(Notification) === EventTarget,
          tag: Object.prototype.toString.call(Notification.prototype),
          ownKeys: Object.getOwnPropertyNames(Notification).filter((key) => key === 'arguments' || key === 'caller').length,
          hidden: !Object.keys(globalThis).includes('Notification'),
        };
      }),
      timezone: zone.timeZone,
      locale: zone.locale,
      offset: new Date(0).getTimezoneOffset(),
      dateText: new Date(86_400_000).toString(),
      numberText: (1234567.891).toLocaleString(),
      math: await digest([Math.tan(-1e300), Math.sinh(1), Math.expm1(1), Math.log1p(1), Math.cbrt(100), Math.acosh(1e300)].join()),
      connection: nav.connection ? JSON.stringify({ type: nav.connection.effectiveType, rtt: nav.connection.rtt, downlink: nav.connection.downlink, save: nav.connection.saveData }) : null,
      offscreenCanvas: offscreen,
      injected: globalThis.__fpInjected ?? null,
      echo: await attempt(async () => JSON.stringify((await (await fetch('/echo')).json()).headers)),
    };
  }

  async function webgl(version) {
    const canvas = document.createElement('canvas');
    canvas.width = 64;
    canvas.height = 64;
    const gl = canvas.getContext(version, { preserveDrawingBuffer: true });
    if (!gl) return null;
    const info = gl.getExtension('WEBGL_debug_renderer_info');
    const names = ['ALIASED_LINE_WIDTH_RANGE', 'ALIASED_POINT_SIZE_RANGE', 'ALPHA_BITS', 'BLUE_BITS', 'DEPTH_BITS', 'GREEN_BITS', 'MAX_COMBINED_TEXTURE_IMAGE_UNITS', 'MAX_CUBE_MAP_TEXTURE_SIZE', 'MAX_FRAGMENT_UNIFORM_VECTORS', 'MAX_RENDERBUFFER_SIZE', 'MAX_TEXTURE_IMAGE_UNITS', 'MAX_TEXTURE_SIZE', 'MAX_VARYING_VECTORS', 'MAX_VERTEX_ATTRIBS', 'MAX_VERTEX_TEXTURE_IMAGE_UNITS', 'MAX_VERTEX_UNIFORM_VECTORS', 'MAX_VIEWPORT_DIMS', 'RED_BITS', 'SHADING_LANGUAGE_VERSION', 'STENCIL_BITS', 'SUBPIXEL_BITS', 'VERSION'];
    const parameters = names.map((name) => {
      const value = gl.getParameter(gl[name]);
      return ArrayBuffer.isView(value) ? [...value].join('/') : value;
    });
    gl.clearColor(0.2, 0.4, 0.6, 1);
    gl.clear(gl.COLOR_BUFFER_BIT);
    const vertex = gl.createShader(gl.VERTEX_SHADER);
    gl.shaderSource(vertex, 'attribute vec2 p;void main(){gl_Position=vec4(p,0,1);}');
    gl.compileShader(vertex);
    const fragment = gl.createShader(gl.FRAGMENT_SHADER);
    gl.shaderSource(fragment, 'precision mediump float;void main(){gl_FragColor=vec4(.91,.27,.13,.7);}');
    gl.compileShader(fragment);
    const program = gl.createProgram();
    gl.attachShader(program, vertex);
    gl.attachShader(program, fragment);
    gl.linkProgram(program);
    gl.useProgram(program);
    const buffer = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-0.8, -0.7, 0.9, -0.3, 0.1, 0.8]), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
    const pixels = new Uint8Array(64 * 64 * 4);
    gl.readPixels(0, 0, 64, 64, gl.RGBA, gl.UNSIGNED_BYTE, pixels);
    return {
      vendor: gl.getParameter(gl.VENDOR),
      renderer: gl.getParameter(gl.RENDERER),
      unmaskedVendor: info ? gl.getParameter(info.UNMASKED_VENDOR_WEBGL) : null,
      unmaskedRenderer: info ? gl.getParameter(info.UNMASKED_RENDERER_WEBGL) : null,
      parameters: await digest(parameters.join('|')),
      extensions: await digest((gl.getSupportedExtensions() ?? []).join()),
      pixels: await digest(pixels.join()),
      dataUrl: await digest(canvas.toDataURL()),
    };
  }

  async function audio() {
    const context = new OfflineAudioContext(1, 5000, 44100);
    const oscillator = context.createOscillator();
    oscillator.type = 'triangle';
    oscillator.frequency.value = 10000;
    const compressor = context.createDynamicsCompressor();
    oscillator.connect(compressor);
    compressor.connect(context.destination);
    oscillator.start(0);
    const rendered = await context.startRendering();
    const samples = rendered.getChannelData(0);
    let sum = 0;
    for (let i = 4500; i < 5000; i++) sum += Math.abs(samples[i]);
    const live = new AudioContext();
    const properties = { sampleRate: live.sampleRate, baseLatency: live.baseLatency, outputLatency: live.outputLatency, maxChannels: live.destination.maxChannelCount };
    await live.close();
    return { sum, samples: await digest([...samples.slice(4000, 4100)].join()), ...properties };
  }

  const FONTS = ['Arial', 'Arial Black', 'Calibri', 'Cambria', 'Comic Sans MS', 'Consolas', 'Courier New', 'Georgia', 'Helvetica', 'Impact', 'Noto Sans', 'Noto Serif', 'Noto Color Emoji', 'Roboto', 'Roboto Condensed', 'Droid Sans', 'Droid Serif', 'Segoe UI', 'Tahoma', 'Times New Roman', 'Trebuchet MS', 'Verdana', 'Cutive Mono', 'Dancing Script', 'Carrois Gothic SC', 'Coming Soon', 'HiOS Sans', 'Tecno Sans', 'SamsungOne', 'MiSans'];

  function fonts() {
    const context = document.createElement('canvas').getContext('2d');
    const sample = 'mmmmmmmmmmlli WW';
    const widths = (family) => {
      context.font = `72px ${family}`;
      return context.measureText(sample).width;
    };
    const fallback = ['monospace', 'sans-serif', 'serif'].map(widths);
    const present = FONTS.filter((font) => ['monospace', 'sans-serif', 'serif'].some((generic, i) => widths(`"${font}", ${generic}`) !== fallback[i]));
    return { present: present.join(','), measure: widths('sans-serif') };
  }

  function rects() {
    const element = document.createElement('div');
    element.style.cssText = 'position:absolute;left:10.3px;top:20.7px;width:101.17px;height:33.33px;transform:rotate(3.1deg) scale(1.013);font:13.3px sans-serif';
    element.textContent = 'PocketWright mesure';
    document.body.append(element);
    const box = element.getBoundingClientRect();
    const range = document.createRange();
    range.selectNodeContents(element);
    const text = range.getBoundingClientRect();
    element.remove();
    return [box.x, box.y, box.width, box.height, text.width, text.height].join();
  }

  async function webrtc() {
    const connection = new RTCPeerConnection({ iceServers: [] });
    connection.createDataChannel('x');
    const found = [];
    connection.onicecandidate = (event) => {
      if (event.candidate) found.push(event.candidate.candidate);
    };
    await connection.setLocalDescription(await connection.createOffer());
    await new Promise((resolve) => setTimeout(resolve, 1200));
    connection.close();
    return { candidates: found.length, mdns: found.filter((c) => c.includes('.local')).length, rawIp: found.filter((c) => /\s\d+\.\d+\.\d+\.\d+\s/.test(c)).length };
  }

  const MEDIA = ['(prefers-color-scheme: dark)', '(prefers-reduced-motion: reduce)', '(prefers-contrast: more)', '(forced-colors: active)', '(inverted-colors: inverted)', '(hover: hover)', '(pointer: fine)', '(pointer: coarse)', '(any-hover: hover)', '(color-gamut: p3)', '(dynamic-range: high)', '(display-mode: browser)'];

  async function page() {
    const main = await core();
    const nav = navigator;
    const canvas = document.createElement('canvas');
    canvas.width = 240;
    canvas.height = 60;
    paint(canvas.getContext('2d'));
    const canvasUrl = canvas.toDataURL();
    const canvasAgain = canvas.toDataURL();
    const pixels = canvas.getContext('2d').getImageData(0, 0, 240, 60).data;
    const measure = canvas.getContext('2d').measureText('PocketWright mesure');
    return {
      ...main,
      vendor: nav.vendor,
      product: nav.product,
      productSub: nav.productSub,
      maxTouchPoints: nav.maxTouchPoints,
      pdfViewerEnabled: nav.pdfViewerEnabled ?? null,
      cookieEnabled: nav.cookieEnabled,
      doNotTrack: nav.doNotTrack,
      plugins: nav.plugins.length,
      mimeTypes: nav.mimeTypes.length,
      screen: [screen.width, screen.height, screen.availWidth, screen.availHeight, screen.colorDepth, screen.pixelDepth, screen.orientation?.type].join(),
      devicePixelRatio,
      window: [innerWidth, innerHeight, outerWidth, outerHeight].join(),
      media: MEDIA.map((query) => (matchMedia(query).matches ? 1 : 0)).join(''),
      canvas: await digest(canvasUrl),
      canvasStable: canvasUrl === canvasAgain,
      imageData: await digest(pixels.join()),
      measureText: measure.width,
      webgl: await attempt(() => webgl('webgl')),
      webgl2: await attempt(() => webgl('webgl2')),
      webgpu: await attempt(async () => {
        if (!nav.gpu) return null;
        const adapter = await nav.gpu.requestAdapter();
        return adapter ? JSON.stringify(adapter.info ?? {}) : 'aucun';
      }),
      audio: await attempt(audio),
      fonts: await attempt(() => fonts()),
      rects: await attempt(() => rects()),
      webrtc: await attempt(webrtc),
      devices: await attempt(async () => (await nav.mediaDevices.enumerateDevices()).map((device) => device.kind).sort().join()),
      voices: globalThis.speechSynthesis ? speechSynthesis.getVoices().length : null,
      quota: await attempt(async () => (await nav.storage.estimate()).quota),
      heapLimit: performance.memory?.jsHeapSizeLimit ?? null,
      battery: await attempt(async () => {
        const battery = await nav.getBattery();
        return [battery.charging, battery.level].join();
      }),
      nativeToString: Function.prototype.toString.call(Object.getOwnPropertyDescriptor(Navigator.prototype, 'userAgent').get),
      canvasToString: Function.prototype.toString.call(HTMLCanvasElement.prototype.toDataURL),
    };
  }

  globalThis.collectCore = core;
  globalThis.collectPage = page;

  // Dans un worker, on répond à la demande de la page.
  if (typeof document === 'undefined') {
    addEventListener('message', async (event) => {
      const answer = await attempt(core);
      (event.ports[0] ?? globalThis).postMessage(answer);
    });
  }
})();
