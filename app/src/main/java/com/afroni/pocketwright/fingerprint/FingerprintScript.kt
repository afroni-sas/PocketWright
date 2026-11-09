package com.afroni.pocketwright.fingerprint

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Ce que le moteur ne sait pas régler lui-même par session : le bruit canvas et audio, à graine fixe (donc stable
 * d'une lecture à l'autre), le masquage des adresses dans WebRTC, et quelques valeurs de l'identité.
 *
 * Le script tourne avant ceux de la page, dans chaque cadre, et dans les workers.
 */
object FingerprintScript {

    fun build(fingerprint: Fingerprint, version: String): String {
        val major = version.substringBefore('.').toIntOrNull() ?: 0
        val full = FingerprintFactory.brands(major, version)
        val low = FingerprintFactory.brands(major, major.toString())
        val config = buildJsonObject {
            put("seed", (fingerprint.seed xor (fingerprint.seed ushr 32)).toInt())
            put("identity", fingerprint.identity)
            if (fingerprint.identity) {
                put("userAgent", fingerprint.userAgent(major))
                put("platform", PLATFORM)
                put("languages", JsonArray(fingerprint.languages.map(::JsonPrimitive)))
                put("cores", fingerprint.cores)
                put("memory", fingerprint.memory)
                put("model", fingerprint.model)
                put("platformVersion", fingerprint.platformVersion)
                put("fullVersion", version)
                put("fullVersionList", JsonArray(full.map { (brand, value) -> brandJson(brand, if (brand.startsWith("Not")) "$value.0.0.0" else value) }))
                put("brands", JsonArray(low.map { (brand, value) -> brandJson(brand, value) }))
                if (DeviceCatalog.sharesGpu) put("gpu", DeviceCatalog.GPU)
            }
        }
        return "(() => {\nconst C = $config;\n$BODY\n})();"
    }

    const val PLATFORM = "Linux armv81"

    private fun brandJson(brand: String, version: String) = buildJsonObject {
        put("brand", brand)
        put("version", version)
    }

    private val BODY = """
const G = globalThis;
const isPage = typeof document !== 'undefined';
const masks = new WeakMap();
const nativeToString = Function.prototype.toString;
const maskedToString = new Proxy(nativeToString, {
  apply(target, self, args) {
    const mask = masks.get(self);
    return typeof mask === 'string' ? mask : Reflect.apply(target, mask ?? self, args);
  },
});
masks.set(maskedToString, nativeToString);
Object.defineProperty(Function.prototype, 'toString', { ...Object.getOwnPropertyDescriptor(Function.prototype, 'toString'), value: maskedToString });

const wrap = (owner, name, handler) => {
  const descriptor = owner && Object.getOwnPropertyDescriptor(owner, name);
  if (!descriptor || typeof descriptor.value !== 'function') return;
  const proxy = new Proxy(descriptor.value, { apply: (target, self, args) => handler(target, self, args) });
  masks.set(proxy, descriptor.value);
  Object.defineProperty(owner, name, { ...descriptor, value: proxy });
};
const mapGetter = (owner, name, map) => {
  const descriptor = owner && Object.getOwnPropertyDescriptor(owner, name);
  if (!descriptor || !descriptor.get) return;
  const proxy = new Proxy(descriptor.get, { apply: (target, self, args) => map(Reflect.apply(target, self, args)) });
  masks.set(proxy, descriptor.get);
  Object.defineProperty(owner, name, { ...descriptor, get: proxy });
};
const fixed = (owner, name, value) => mapGetter(owner, name, () => value);

const mix = (a, b) => {
  let hash = C.seed ^ Math.imul(a, 0x9E3779B1) ^ Math.imul(b, 0x85EBCA77);
  hash = Math.imul(hash ^ (hash >>> 15), 0x2C1B3C6D);
  hash = Math.imul(hash ^ (hash >>> 12), 0x297A2D39);
  return (hash ^ (hash >>> 15)) >>> 0;
};

// Canvas : les mêmes pixels changent à chaque lecture, d'une session à l'autre seulement.
const MAX_PIXELS = 1000000;
const noise = (data, width, left, top) => {
  if (data.length > MAX_PIXELS * 4) return;
  for (let i = 0; i < data.length; i += 4) {
    if (data[i + 3] === 0) continue;
    const pixel = i / 4;
    const hash = mix(left + (pixel % width), top + Math.floor(pixel / width));
    if ((hash & 63) !== 0) continue;
    const channel = i + ((hash >>> 8) % 3);
    const value = data[channel];
    data[channel] = value === 0 ? 1 : value === 255 ? 254 : value + ((hash >>> 12) & 1 ? 1 : -1);
  }
};
const native2d = G.CanvasRenderingContext2D?.prototype.getImageData;
const nativeOffscreen = G.OffscreenCanvasRenderingContext2D?.prototype.getImageData;
const readable = (proto) => wrap(proto, 'getImageData', (target, self, args) => {
  const image = Reflect.apply(target, self, args);
  noise(image.data, image.width, Math.trunc(args[0]) || 0, Math.trunc(args[1]) || 0);
  return image;
});
readable(G.CanvasRenderingContext2D?.prototype);
readable(G.OffscreenCanvasRenderingContext2D?.prototype);

const noisyCopy = (source, create, read) => {
  const width = source.width;
  const height = source.height;
  if (!width || !height || width * height > MAX_PIXELS) return null;
  try {
    const copy = create(width, height);
    const context = copy.getContext('2d', { willReadFrequently: true });
    context.drawImage(source, 0, 0);
    const image = Reflect.apply(read, context, [0, 0, width, height]);
    noise(image.data, width, 0, 0);
    context.putImageData(image, 0, 0);
    return copy;
  } catch {
    // Canvas d'une autre origine : la fonction d'origine lèvera l'erreur attendue.
    return null;
  }
};
if (isPage) {
  const createElement = document.createElement.bind(document);
  const element = (width, height) => {
    const canvas = createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    return canvas;
  };
  for (const name of ['toDataURL', 'toBlob']) {
    wrap(G.HTMLCanvasElement?.prototype, name, (target, self, args) => Reflect.apply(target, noisyCopy(self, element, native2d) ?? self, args));
  }
}
wrap(G.OffscreenCanvas?.prototype, 'convertToBlob', (target, self, args) =>
  Reflect.apply(target, noisyCopy(self, (width, height) => new OffscreenCanvas(width, height), nativeOffscreen) ?? self, args));
for (const proto of [G.WebGLRenderingContext?.prototype, G.WebGL2RenderingContext?.prototype]) {
  wrap(proto, 'readPixels', (target, self, args) => {
    const result = Reflect.apply(target, self, args);
    if (args[6] instanceof Uint8Array && args[4] === 0x1908) noise(args[6], args[2], args[0], args[1]);
    return result;
  });
}

// Audio : un gain inaudible (moins de 0,01 dB) et constant pour la session, appliqué une seule fois par tampon.
const nativeChannel = G.AudioBuffer?.prototype.getChannelData;
const gain = 1 + (0.0002 + ((mix(1, 7) % 1000) / 1000) * 0.0008) * ((mix(3, 5) & 1) ? 1 : -1);
const treated = new WeakSet();
const treat = (buffer) => {
  if (treated.has(buffer)) return;
  treated.add(buffer);
  if (buffer.length > 4000000) return;
  for (let channel = 0; channel < buffer.numberOfChannels; channel++) {
    const data = Reflect.apply(nativeChannel, buffer, [channel]);
    for (let i = 0; i < data.length; i++) data[i] *= gain;
  }
};
for (const name of ['getChannelData', 'copyFromChannel']) {
  wrap(G.AudioBuffer?.prototype, name, (target, self, args) => {
    try { treat(self); } catch {}
    return Reflect.apply(target, self, args);
  });
}

// WebRTC : jamais d'adresse locale, comme le fait Chrome avec ses noms .local.
if (isPage && G.RTCIceCandidate) {
  const names = new Map();
  const hex = (value, size) => (value >>> 0).toString(16).padStart(8, '0').slice(0, size);
  const hide = (address) => {
    if (!names.has(address)) {
      let code = 0;
      for (const character of address) code = (Math.imul(code, 31) + character.charCodeAt(0)) | 0;
      names.set(address, hex(mix(code, 1), 8) + '-' + hex(mix(code, 2), 4) + '-4' + hex(mix(code, 3), 3) + '-8' + hex(mix(code, 4), 3) + '-' + hex(mix(code, 5), 8) + hex(mix(code, 6), 4) + '.local');
    }
    return names.get(address);
  };
  const isAddress = (text) => /^[0-9.]+$/.test(text) || (text.includes(':') && !text.endsWith('.local'));
  const HOST = /^((?:a=)?candidate:\S+ \d+ \S+ \d+ )(\S+)( \d+ typ host)/;
  const line = (text) => text.replace(HOST, (all, before, address, after) => (isAddress(address) ? before + hide(address) + after : all));
  mapGetter(G.RTCIceCandidate.prototype, 'candidate', (value) => (value ? line(value) : value));
  mapGetter(G.RTCIceCandidate.prototype, 'address', (value) => (value && isAddress(value) ? hide(value) : value));
  mapGetter(G.RTCSessionDescription?.prototype, 'sdp', (value) => (value
    ? value.split('\r\n').map((text) => (text.startsWith('c=IN IP') ? text.replace(/^(c=IN IP\d ).*/, (all, head) => head + (head.includes('IP6') ? '::' : '0.0.0.0')) : line(text))).join('\r\n')
    : value));
}

if (!C.identity) return;

const entropy = () => ({
  architecture: '',
  bitness: '',
  model: C.model,
  platformVersion: C.platformVersion,
  uaFullVersion: C.fullVersion,
  fullVersionList: C.fullVersionList.map((entry) => ({ ...entry })),
  wow64: false,
  formFactors: ['Mobile'],
});
wrap(G.NavigatorUAData?.prototype, 'getHighEntropyValues', (target, self, args) =>
  Reflect.apply(target, self, args).then((base) => {
    const all = entropy();
    const out = { ...base };
    for (const hint of Array.from(args[0] ?? [])) if (hint in all) out[hint] = all[hint];
    return out;
  }));
fixed(G.Navigator?.prototype, 'deviceMemory', C.memory);
fixed(G.Navigator?.prototype, 'platform', C.platform);
// Le pilote de ce téléphone se nomme autrement que le GPU des appareils du catalogue, qui est pourtant le même.
if (C.gpu) {
  for (const proto of [G.WebGLRenderingContext?.prototype, G.WebGL2RenderingContext?.prototype]) {
    wrap(proto, 'getParameter', (target, self, args) => {
      const value = Reflect.apply(target, self, args);
      if (value === null) return value;
      if (args[0] === 0x9245) return 'Imagination Technologies';
      if (args[0] === 0x9246) return C.gpu;
      return value;
    });
  }
}

// Permissions : une WebView refuse d'office, ou accorde, ce que Chrome sur Android propose de demander ou accorde.
// Valeurs lues dans les sources de Chromium : content_settings_registry.cc et les contextes de permission. Les clés
// sont celles de PermissionStatus.name, qui n'a pas toujours l'orthographe du nom demandé à query().
const CHROME_STATE = {
  denied: {
    notifications: 'prompt', clipboard_read: 'prompt', durable_storage: 'prompt', nfc: 'prompt',
    idle_detection: 'prompt', 'storage-access': 'prompt', 'window-management': 'prompt',
    background_sync: 'granted', payment_handler: 'granted', screen_wake_lock: 'granted',
    background_fetch: isPage && G.top === G ? 'granted' : 'prompt',
  },
  granted: { midi: 'prompt' },
};
const status = G.PermissionStatus?.prototype;
const statusState = status && Object.getOwnPropertyDescriptor(status, 'state');
const statusName = status && Object.getOwnPropertyDescriptor(status, 'name')?.get;
if (statusState?.get && statusName) {
  const state = new Proxy(statusState.get, {
    apply(target, self, args) {
      const value = Reflect.apply(target, self, args);
      return CHROME_STATE[value]?.[Reflect.apply(statusName, self, [])] ?? value;
    },
  });
  masks.set(state, statusState.get);
  Object.defineProperty(status, 'state', { ...statusState, get: state });
}

// Notification n'existe pas dans une WebView ; Chrome sur Android l'expose, sans permettre de la construire.
if (!G.Notification && G.EventTarget) {
  function Notification(title) {
    'use strict';
    if (!new.target) throw new TypeError("Failed to construct 'Notification': Please use the 'new' operator, this DOM object constructor cannot be called as a function.");
    if (!arguments.length) throw new TypeError("Failed to construct 'Notification': 1 argument required, but only 0 present.");
    throw new TypeError("Failed to construct 'Notification': Illegal constructor. Use ServiceWorkerRegistration.showNotification() instead.");
  }
  const native = (fn, label) => {
    masks.set(fn, 'function ' + label + '() { [native code] }');
    return fn;
  };
  const illegal = () => { throw new TypeError('Illegal invocation'); };
  const prototype = Object.create(G.EventTarget.prototype);
  const attributes = ['onclick', 'onshow', 'onerror', 'onclose', 'title', 'dir', 'lang', 'body', 'tag', 'icon', 'badge', 'vibrate', 'timestamp', 'renotify', 'silent', 'requireInteraction', 'data', 'actions', 'image'];
  for (const name of attributes) {
    const holder = Object.getOwnPropertyDescriptor({ get [name]() { return illegal(); }, set [name](value) { illegal(); } }, name);
    native(holder.get, 'get ' + name);
    native(holder.set, 'set ' + name);
    Object.defineProperty(prototype, name, { get: holder.get, set: name.startsWith('on') ? holder.set : undefined, enumerable: true, configurable: true });
  }
  const close = { close() { illegal(); } }.close;
  Object.defineProperty(prototype, 'close', { value: native(close, 'close'), writable: true, enumerable: true, configurable: true });
  Object.defineProperty(prototype, 'constructor', { value: Notification, writable: true, enumerable: false, configurable: true });
  Object.defineProperty(prototype, Symbol.toStringTag, { value: 'Notification', configurable: true });
  Object.setPrototypeOf(Notification, G.EventTarget);
  Object.defineProperty(Notification, 'prototype', { value: prototype, writable: false, enumerable: false, configurable: false });
  native(Notification, 'Notification');
  const constants = { permission: 'default', maxActions: 2 };
  for (const [name, value] of Object.entries(constants)) {
    const get = Object.getOwnPropertyDescriptor({ get [name]() { return value; } }, name).get;
    Object.defineProperty(Notification, name, { get: native(get, 'get ' + name), enumerable: true, configurable: true });
  }
  if (isPage) {
    // Sans personne pour répondre, la demande se termine comme une invite que l'on a fermée.
    const request = {
      requestPermission() {
        const callback = arguments[0];
        return new Promise((resolve) => setTimeout(() => {
          if (typeof callback === 'function') callback('default');
          resolve('default');
        }, 1500 + (mix(11, 13) % 2500)));
      },
    }.requestPermission;
    Object.defineProperty(Notification, 'requestPermission', { value: native(request, 'requestPermission'), writable: true, enumerable: true, configurable: true });
  }
  Object.defineProperty(G, 'Notification', { value: Notification, writable: true, enumerable: false, configurable: true });
}

if (isPage) {
  if (!G.chrome) {
    const started = Date.now() / 1000;
    Object.defineProperty(G, 'chrome', {
      configurable: true, enumerable: true, writable: true,
      value: {
        app: { isInstalled: false, InstallState: { DISABLED: 'disabled', INSTALLED: 'installed', NOT_INSTALLED: 'not_installed' }, RunningState: { CANNOT_RUN: 'cannot_run', READY_TO_RUN: 'ready_to_run', RUNNING: 'running' } },
        csi: () => ({ startE: Math.floor(started * 1000), onloadT: Math.floor(started * 1000) + 300, pageT: performance.now(), tran: 15 }),
        loadTimes: () => ({ requestTime: started, startLoadTime: started, commitLoadTime: started + 0.1, finishDocumentLoadTime: started + 0.3, finishLoadTime: started + 0.4, firstPaintTime: started + 0.3, firstPaintAfterLoadTime: 0, navigationType: 'Other', wasFetchedViaSpdy: false, wasNpnNegotiated: false, npnNegotiatedProtocol: '', wasAlternateProtocolAvailable: false, connectionInfo: 'http/1.1' }),
      },
    });
  }
} else {
  // Le moteur ne propage pas toutes ses surcharges aux workers.
  const worker = G.WorkerNavigator?.prototype;
  fixed(worker, 'userAgent', C.userAgent);
  fixed(worker, 'appVersion', C.userAgent.replace(/^Mozilla\//, ''));
  fixed(worker, 'platform', C.platform);
  fixed(worker, 'hardwareConcurrency', C.cores);
  fixed(worker, 'deviceMemory', C.memory);
  fixed(worker, 'language', C.languages[0]);
  fixed(worker, 'languages', Object.freeze([...C.languages]));
  mapGetter(G.NavigatorUAData?.prototype, 'brands', () => C.brands.map((entry) => Object.freeze({ ...entry })));
}
"""
}
