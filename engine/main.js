'use strict';
/*
 * PocketWright — le moteur Playwright embarqué.
 *
 * Il tourne dans l'app, sur Node.js (nodejs-mobile), et pilote chaque session par la vraie bibliothèque
 * Playwright : `chromium.connectOverCDP` sur la passerelle CDP de l'app, qui ne lui montre que la page de la
 * session. L'app lui transmet les commandes de l'API sur 127.0.0.1, avec un jeton que lui seul connaît.
 */
const http = require('node:http');
const { timingSafeEqual } = require('node:crypto');
const { inspect } = require('node:util');

// Dans une app, une exception non rattrapée ou process.exit() arrêteraient tout le processus Android.
process.on('uncaughtException', (e) => console.error('[engine] exception non rattrapée', e));
process.on('unhandledRejection', (e) => console.error('[engine] promesse rejetée', e));
process.exit = (code) => console.error(`[engine] process.exit(${code}) ignoré`);

const PORT = Number(process.env.PW_ENGINE_PORT);
const TOKEN = process.env.PW_ENGINE_TOKEN;
const GATEWAY = process.env.PW_GATEWAY;
const GATEWAY_KEY = process.env.PW_GATEWAY_KEY;
const API = process.env.PW_API;

// Une version téléchargée par l'app (PW_CORE_PATH), sinon celle livrée avec elle.
const CORE = process.env.PW_CORE_PATH || 'playwright-core';
const playwright = require(CORE);
const { chromium } = playwright;
const VERSION = require(`${CORE}/package.json`).version;

// Node n'écrit son cache de compilation qu'en sortant, et ce processus ne sort jamais : sans ça, chaque
// démarrage recompile les 7 Mo de Playwright.
try {
  require('node:module').flushCompileCache();
} catch (e) {
  console.error('[engine] cache de compilation non écrit', e.message);
}

/** id de session → { browser, context, page, state }. `state` garde ce que les scripts y rangent. */
const sessions = new Map();

class EngineError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

/** Ce que l'app sait de la session : sa page DevTools, et qui doit répondre aux boîtes de dialogue. */
function sessionInfo(id) {
  return new Promise((resolve, reject) => {
    const req = http.get(`${API}/internal/sessions/${encodeURIComponent(id)}`, { headers: { authorization: `Bearer ${GATEWAY_KEY}` } }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        try {
          const reply = JSON.parse(Buffer.concat(chunks).toString('utf8'));
          if (reply.ok) resolve(reply.result);
          else reject(new Error(reply.error));
        } catch (e) {
          reject(e);
        }
      });
    });
    req.on('error', reject);
    req.setTimeout(5_000, () => req.destroy(new Error('Délai dépassé')));
  });
}

/** La page de cette session : la connexion voit aussi les autres onglets du groupe (les popups). */
async function ownPage(id, context) {
  const pages = context.pages();
  if (pages.length === 0) return context.waitForEvent('page', { timeout: 10_000 });
  if (pages.length === 1) return pages[0];
  const { targetId } = await sessionInfo(id);
  for (const candidate of pages) {
    const cdp = await context.newCDPSession(candidate);
    const { targetInfo } = await cdp.send('Target.getTargetInfo');
    await cdp.detach().catch(() => {});
    if (targetInfo.targetId === targetId) return candidate;
  }
  return pages[0];
}

/**
 * Sans écouteur, Playwright refuse toute boîte de dialogue d'office. Cet écouteur l'en empêche : un script
 * qui écoute répond lui-même, et sinon on regarde qui doit répondre (l'utilisateur à l'écran, un autre
 * client CDP) avant d'appliquer le mode réglé par la commande `dialogs`.
 */
function watchDialogs(id, context) {
  context.on('dialog', async (dialog) => {
    const page = dialog.page();
    if (context.listenerCount('dialog') > 1 || (page && page.listenerCount('dialog') > 0)) return;
    try {
      const { dialogs } = await sessionInfo(id);
      if (dialogs.responder !== 'auto') return;
      if (dialogs.mode === 'accept') await dialog.accept(dialogs.promptText ?? dialog.defaultValue());
      else await dialog.dismiss();
    } catch (e) {
      console.error('[engine] boîte de dialogue sans réponse', e.message);
    }
  });
}

async function attach(id) {
  if (!id) throw new EngineError(400, 'Session manquante');
  let entry = sessions.get(id);
  if (!entry) sessions.set(id, (entry = { state: {} }));
  if (entry.page && !entry.page.isClosed() && entry.browser?.isConnected()) return entry;
  if (!entry.connecting) {
    entry.connecting = (async () => {
      if (entry.browser) await entry.browser.close().catch(() => {});
      const url = `${GATEWAY}/${encodeURIComponent(id)}?key=${encodeURIComponent(GATEWAY_KEY)}`;
      const browser = await chromium.connectOverCDP(url, { isWebView: true, timeout: 30_000 });
      const context = browser.contexts()[0];
      const page = await ownPage(id, context);
      watchDialogs(id, context);
      browser.on('disconnected', () => {
        if (entry.browser === browser) entry.browser = entry.context = entry.page = null;
      });
      Object.assign(entry, { browser, context, page });
      return entry;
    })().finally(() => {
      entry.connecting = null;
    });
  }
  return entry.connecting;
}

async function release(id) {
  const entry = sessions.get(id);
  sessions.delete(id);
  // Sur une connexion CDP, browser.close() ne fait que se déconnecter : la session ne se ferme pas.
  if (entry && entry.browser) await entry.browser.close().catch(() => {});
}

// ---------------------------------------------------------------- commandes

const pick = (p, keys) => Object.fromEntries(keys.filter((k) => p[k] !== undefined).map((k) => [k, p[k]]));
const need = (p, key) => {
  if (p[key] === undefined || p[key] === null || p[key] === '') throw new EngineError(400, `Paramètre « ${key} » manquant`);
  return p[key];
};
const loc = (s, p) => s.page.locator(need(p, 'selector'));

/**
 * Playwright prend une chaîne pour une expression : `"() => document.title"` rendrait la fonction, sans
 * l'appeler. Une chaîne qui s'écrit comme une fonction devient donc une fonction (son texte reste le même,
 * Playwright l'envoie tel quel à la page ; rien n'est exécuté ici).
 */
const FUNCTION_SOURCE = /^\s*(async\s+)?(function\b|\([^)]*\)\s*=>|[A-Za-z_$][\w$]*\s*=>)/;
const pageFunction = (source) => {
  if (typeof source !== 'string' || !FUNCTION_SOURCE.test(source)) return source;
  const fn = function () {};
  fn.toString = () => source;
  return fn;
};
const ACTION_OPTS = ['timeout', 'force', 'noWaitAfter', 'trial', 'position', 'modifiers'];
const pageInfo = async (s, response) => ({
  url: s.page.url(),
  title: await s.page.title(),
  status: response ? response.status() : null,
});
const files = (list) =>
  (Array.isArray(list) ? list : [list]).map((f) =>
    typeof f === 'string' ? f : { name: f.name, mimeType: f.mimeType || 'application/octet-stream', buffer: Buffer.from(f.buffer || f.base64 || '', 'base64') },
  );

const commands = {
  goto: async (s, p) => pageInfo(s, await s.page.goto(need(p, 'url'), pick(p, ['waitUntil', 'timeout', 'referer']))),
  reload: async (s, p) => pageInfo(s, await s.page.reload(pick(p, ['waitUntil', 'timeout']))),
  back: async (s, p) => pageInfo(s, await s.page.goBack(pick(p, ['waitUntil', 'timeout']))),
  forward: async (s, p) => pageInfo(s, await s.page.goForward(pick(p, ['waitUntil', 'timeout']))),
  setContent: async (s, p) => (await s.page.setContent(need(p, 'html'), pick(p, ['waitUntil', 'timeout'])), pageInfo(s)),

  click: (s, p) => loc(s, p).click(pick(p, [...ACTION_OPTS, 'button', 'clickCount', 'delay'])),
  dblclick: (s, p) => loc(s, p).dblclick(pick(p, [...ACTION_OPTS, 'button', 'delay'])),
  tap: (s, p) => loc(s, p).tap(pick(p, ACTION_OPTS)),
  hover: (s, p) => loc(s, p).hover(pick(p, ACTION_OPTS)),
  focus: (s, p) => loc(s, p).focus(pick(p, ['timeout'])),
  check: (s, p) => loc(s, p).check(pick(p, ACTION_OPTS)),
  uncheck: (s, p) => loc(s, p).uncheck(pick(p, ACTION_OPTS)),
  fill: (s, p) => loc(s, p).fill(String(p.value ?? ''), pick(p, ['timeout', 'force', 'noWaitAfter'])),
  clear: (s, p) => loc(s, p).clear(pick(p, ['timeout', 'force'])),
  type: (s, p) =>
    p.selector
      ? s.page.locator(p.selector).pressSequentially(String(need(p, 'text')), pick(p, ['delay', 'timeout']))
      : s.page.keyboard.type(String(need(p, 'text')), pick(p, ['delay'])),
  press: (s, p) =>
    p.selector
      ? s.page.locator(p.selector).press(need(p, 'key'), pick(p, ['delay', 'timeout']))
      : s.page.keyboard.press(need(p, 'key'), pick(p, ['delay'])),
  selectOption: (s, p) => loc(s, p).selectOption(p.values ?? p.value ?? null, pick(p, ['timeout', 'force'])),
  select: (s, p) => commands.selectOption(s, p),
  setInputFiles: (s, p) => loc(s, p).setInputFiles(files(need(p, 'files')), pick(p, ['timeout'])),
  dragTo: (s, p) => loc(s, p).dragTo(s.page.locator(need(p, 'target')), pick(p, ACTION_OPTS)),
  scrollIntoView: (s, p) => loc(s, p).scrollIntoViewIfNeeded(pick(p, ['timeout'])),
  scroll: (s, p) => s.page.mouse.wheel(Number(p.x ?? p.dx ?? 0), Number(p.y ?? p.dy ?? 0)),
  mouse: (s, p) => {
    const m = s.page.mouse;
    switch (need(p, 'action')) {
      case 'move': return m.move(Number(p.x), Number(p.y), pick(p, ['steps']));
      case 'down': return m.down(pick(p, ['button', 'clickCount']));
      case 'up': return m.up(pick(p, ['button', 'clickCount']));
      case 'click': return m.click(Number(p.x), Number(p.y), pick(p, ['button', 'clickCount', 'delay']));
      case 'dblclick': return m.dblclick(Number(p.x), Number(p.y), pick(p, ['button', 'delay']));
      case 'wheel': return m.wheel(Number(p.dx ?? 0), Number(p.dy ?? 0));
      default: throw new EngineError(400, 'action : move, down, up, click, dblclick ou wheel');
    }
  },
  keyboard: (s, p) => {
    const k = s.page.keyboard;
    switch (need(p, 'action')) {
      case 'type': return k.type(String(need(p, 'text')), pick(p, ['delay']));
      case 'insertText': return k.insertText(String(need(p, 'text')));
      case 'press': return k.press(need(p, 'key'), pick(p, ['delay']));
      case 'down': return k.down(need(p, 'key'));
      case 'up': return k.up(need(p, 'key'));
      default: throw new EngineError(400, 'action : type, insertText, press, down ou up');
    }
  },
  touchscreen: (s, p) => s.page.touchscreen.tap(Number(need(p, 'x')), Number(need(p, 'y'))),

  waitForSelector: async (s, p) => (await s.page.waitForSelector(need(p, 'selector'), pick(p, ['state', 'timeout'])), true),
  waitForLoad: async (s, p) => (await s.page.waitForLoadState(p.state || 'load', pick(p, ['timeout'])), pageInfo(s)),
  waitForURL: async (s, p) => (await s.page.waitForURL(need(p, 'url'), pick(p, ['timeout', 'waitUntil'])), pageInfo(s)),
  waitForTimeout: (s, p) => s.page.waitForTimeout(Number(need(p, 'ms'))),
  wait: (s, p) => (p.selector ? commands.waitForSelector(s, p) : commands.waitForTimeout(s, p)),
  waitForFunction: async (s, p) =>
    (await s.page.waitForFunction(pageFunction(need(p, 'expression')), p.arg, pick(p, ['timeout', 'polling']))).jsonValue(),

  evaluate: (s, p) =>
    p.selector
      ? s.page.locator(p.selector).evaluate(pageFunction(need(p, 'expression')), p.arg)
      : s.page.evaluate(pageFunction(need(p, 'expression')), p.arg),
  addInitScript: (s, p) => s.page.addInitScript(need(p, 'script')),
  addScriptTag: async (s, p) => (await s.page.addScriptTag(pick(p, ['url', 'content', 'type'])), true),
  addStyleTag: async (s, p) => (await s.page.addStyleTag(pick(p, ['url', 'content'])), true),

  info: (s) => pageInfo(s),
  url: (s) => s.page.url(),
  title: (s) => s.page.title(),
  content: (s) => s.page.content(),
  text: (s, p) => (p.selector ? s.page.locator(p.selector).innerText(pick(p, ['timeout'])) : s.page.locator('body').innerText()),
  textContent: (s, p) => loc(s, p).textContent(pick(p, ['timeout'])),
  html: (s, p) => (p.selector ? loc(s, p).evaluate((el) => el.outerHTML) : s.page.content()),
  innerHTML: (s, p) => loc(s, p).innerHTML(pick(p, ['timeout'])),
  attribute: (s, p) => loc(s, p).getAttribute(need(p, 'name'), pick(p, ['timeout'])),
  inputValue: (s, p) => loc(s, p).inputValue(pick(p, ['timeout'])),
  count: (s, p) => loc(s, p).count(),
  isVisible: (s, p) => loc(s, p).isVisible(),
  isEnabled: (s, p) => loc(s, p).isEnabled(pick(p, ['timeout'])),
  isChecked: (s, p) => loc(s, p).isChecked(pick(p, ['timeout'])),
  boundingBox: (s, p) => loc(s, p).boundingBox(pick(p, ['timeout'])),
  ariaSnapshot: (s, p) => s.page.locator(p.selector || 'body').ariaSnapshot(pick(p, ['timeout'])),
  queryAll: (s, p) =>
    loc(s, p).evaluateAll(
      (els, limit) =>
        els.slice(0, limit).map((el) => ({
          tag: el.tagName.toLowerCase(),
          id: el.id || null,
          text: (el.innerText || el.textContent || '').trim().slice(0, 300),
          href: el.href || null,
          value: 'value' in el ? el.value : null,
        })),
      Number(p.limit ?? 100),
    ),

  screenshot: async (s, p) => {
    const opts = pick(p, ['fullPage', 'type', 'quality', 'omitBackground', 'animations', 'caret', 'scale', 'clip', 'timeout']);
    const buffer = p.selector ? await loc(s, p).screenshot(opts) : await s.page.screenshot(opts);
    return { type: opts.type || 'png', base64: buffer.toString('base64') };
  },
  pdf: async (s, p) => ({ base64: (await s.page.pdf(pick(p, ['format', 'landscape', 'printBackground', 'scale', 'margin']))).toString('base64') }),
  viewport: async (s, p) => (await s.page.setViewportSize({ width: Number(need(p, 'width')), height: Number(need(p, 'height')) }), s.page.viewportSize()),
  emulateMedia: (s, p) => s.page.emulateMedia(pick(p, ['media', 'colorScheme', 'reducedMotion', 'forcedColors', 'contrast'])),
  setExtraHTTPHeaders: (s, p) => s.page.setExtraHTTPHeaders(need(p, 'headers')),
  bringToFront: (s) => s.page.bringToFront(),

  cookies: (s, p) => s.context.cookies(p.urls ?? p.url),
  addCookies: (s, p) => s.context.addCookies(need(p, 'cookies')),
  clearCookies: (s, p) => s.context.clearCookies(pick(p, ['name', 'domain', 'path'])),
  storageState: (s) => s.context.storageState(),
  setGeolocation: (s, p) => s.context.setGeolocation(p.latitude === undefined ? null : pick(p, ['latitude', 'longitude', 'accuracy'])),
  grantPermissions: (s, p) => s.context.grantPermissions(need(p, 'permissions'), pick(p, ['origin'])),
  setOffline: (s, p) => s.context.setOffline(Boolean(p.offline ?? true)),

  /** Un script Playwright libre : le corps d'une fonction async avec page, context, browser, playwright, state. */
  run: async (s, p) => {
    const logs = [];
    const log = (...args) => logs.push(args.map((a) => (typeof a === 'string' ? a : inspect(a, { depth: 4 }))).join(' '));
    const sandboxConsole = { log, info: log, warn: log, error: log, debug: log };
    const AsyncFunction = (async () => {}).constructor;
    let fn;
    try {
      fn = new AsyncFunction('page', 'context', 'browser', 'playwright', 'state', 'console', need(p, 'script'));
    } catch (e) {
      throw new EngineError(400, `Script invalide : ${e.message}`);
    }
    const result = await withTimeout(fn(s.page, s.context, s.browser, playwright, s.state, sandboxConsole), Number(p.timeout ?? 120_000));
    return { result: plain(result), logs };
  },
};

// ---------------------------------------------------------------- serveur

function withTimeout(promise, ms) {
  let timer;
  return Promise.race([
    promise,
    new Promise((_, reject) => {
      timer = setTimeout(() => reject(new EngineError(408, `Délai dépassé (${ms} ms)`)), ms);
    }),
  ]).finally(() => clearTimeout(timer));
}

/** Ce que JSON sait porter : les Buffer passent en base64, les objets Playwright par leur description. */
function plain(value, depth = 0) {
  if (value === undefined || value === null) return null;
  if (Buffer.isBuffer(value)) return { base64: value.toString('base64') };
  if (typeof value === 'bigint') return value.toString();
  if (typeof value !== 'object') return typeof value === 'function' ? null : value;
  if (depth > 8) return inspect(value, { depth: 0 });
  if (Array.isArray(value)) return value.map((v) => plain(v, depth + 1));
  const proto = Object.getPrototypeOf(value);
  if (proto !== Object.prototype && proto !== null) {
    if (value instanceof Date) return value.toISOString();
    if (value instanceof Map) return Object.fromEntries([...value].map(([k, v]) => [String(k), plain(v, depth + 1)]));
    return inspect(value, { depth: 0 });
  }
  return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, plain(v, depth + 1)]));
}

function send(res, status, body) {
  const data = JSON.stringify(body);
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'content-length': Buffer.byteLength(data) });
  res.end(data);
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const text = Buffer.concat(chunks).toString('utf8');
      try {
        resolve(text ? JSON.parse(text) : {});
      } catch {
        reject(new EngineError(400, 'JSON invalide'));
      }
    });
    req.on('error', reject);
  });
}

const sameToken = (given) => {
  const a = Buffer.from(String(given ?? ''));
  const b = Buffer.from(TOKEN);
  return a.length === b.length && timingSafeEqual(a, b);
};

const server = http.createServer(async (req, res) => {
  if (!sameToken(req.headers['x-engine-token'])) return send(res, 401, { ok: false, error: 'Jeton invalide' });
  const path = new URL(req.url, 'http://engine').pathname;
  try {
    if (req.method === 'GET' && path === '/health') {
      return send(res, 200, { ok: true, playwright: VERSION, node: process.version, sessions: sessions.size });
    }
    const body = await readJson(req);
    if (path === '/command') {
      const fn = commands[body.action];
      if (!fn) throw new EngineError(404, `Action inconnue : ${body.action}`);
      const s = await attach(body.session);
      const result = await fn(s, body.params || {});
      return send(res, 200, { ok: true, result: plain(result) });
    }
    if (path === '/release') {
      await release(body.session);
      return send(res, 200, { ok: true, result: true });
    }
    if (path === '/actions') return send(res, 200, { ok: true, result: Object.keys(commands) });
    throw new EngineError(404, `Inconnu : ${path}`);
  } catch (e) {
    const status = e.status || (e.name === 'TimeoutError' ? 408 : 500);
    return send(res, status, { ok: false, error: e.message || String(e), name: e.name });
  }
});

server.listen(PORT, '127.0.0.1', () => console.log(`[engine] Playwright ${VERSION} sur Node ${process.version}, port ${PORT}`));
