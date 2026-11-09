// Scénario de bout en bout de PocketWright, sur un vrai téléphone (ou un émulateur) branché en adb.
//
//   ANDROID_SERIAL=<numéro> node scenario.mjs
//
// Le PC sert le site de test (site.mjs) ; le téléphone le joint sur 127.0.0.1:18090 (adb reverse), et le
// PC joint l'API de l'app sur 127.0.0.1:18766 (adb forward). Trois sessions sont pilotées en même temps,
// chacune par une voie de l'API : REST, WebSocket, et Playwright lui-même par la passerelle CDP.
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { chromium } from 'playwright-core';
import { startSite, stats } from './site.mjs';
import http from 'node:http';
import { advanced } from './advanced.mjs';

const ADB = process.env.ADB || `${process.env.HOME}/Android/Sdk/platform-tools/adb`;
const SERIAL = process.env.ANDROID_SERIAL;
const PKG = 'com.afroni.pocketwright';
const API_PORT = Number(process.env.API_PORT || 18766);
const SITE_PORT = Number(process.env.SITE_PORT || 18090);
const OUT = process.env.OUT || new URL('./out/', import.meta.url).pathname;
const SITE = `http://127.0.0.1:${SITE_PORT}`;
const BASE = `http://127.0.0.1:${API_PORT}`;
if (!SERIAL) throw new Error('ANDROID_SERIAL manquant (adb devices)');
mkdirSync(OUT, { recursive: true });

const adb = (...args) => execFileSync(ADB, ['-s', SERIAL, ...args], { encoding: 'utf8' }).trim();
const adbBinary = (...args) => execFileSync(ADB, ['-s', SERIAL, ...args], { maxBuffer: 64 * 1024 * 1024 });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const t0 = Date.now();
const watchdog = setTimeout(() => { console.error('Durée maximale du scénario dépassée (15 min)'); process.exit(2); }, 900_000);
watchdog.unref();

// ------------------------------------------------------------------ comptes rendus

const results = [];
let section = '';
const title = (name) => {
  section = name;
  console.log(`\n── ${name}`);
};
const check = (name, ok, detail = '') => {
  results.push({ section, name, ok: !!ok, elapsedMs: Date.now() - t0, detail: String(detail) });
  writeFileSync(`${OUT}/progress.json`, JSON.stringify(results, null, 2));
  console.log(`  ${ok ? '✔' : '✘'} ${name}${detail !== '' ? `  (${String(detail).slice(0, 220)})` : ''}`);
  return !!ok;
};
const note = (text) => {
  results.push({ section, name: text, ok: true, note: true, detail: '' });
  console.log(`  ℹ ${text}`);
};
const step = async (name, fn) => {
  title(name);
  try {
    await fn();
  } catch (e) {
    check(`étape interrompue`, false, e.stack?.split('\n').slice(0, 3).join(' | ') ?? e);
  }
};
const pngSize = (buf) => ({ width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) });

// ------------------------------------------------------------------ API

let KEY = '';
const headers = () => ({ authorization: `Bearer ${KEY}`, 'content-type': 'application/json' });
async function api(method, path, body) {
  const r = await fetch(BASE + path, { signal: AbortSignal.timeout(150_000), method, headers: headers(), body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await r.text();
  let json;
  try { json = JSON.parse(text); } catch { json = { ok: false, error: text.slice(0, 200) }; }
  return { status: r.status, ...json };
}
const cmd = async (id, action, params = {}) => {
  const r = await api('POST', `/sessions/${id}/${action}`, params);
  if (!r.ok) throw new Error(`${action} : ${r.error}`);
  return r.result;
};

/** Un client WebSocket de l'API : une connexion, sa session. */
class CommandSocket {
  constructor(query) {
    this.ws = new WebSocket(`ws://127.0.0.1:${API_PORT}/ws?key=${encodeURIComponent(KEY)}&${query}`);
    this.next = 1;
    this.waiting = new Map();
    this.events = [];
    this.session = new Promise((resolve, reject) => {
      this.ws.onerror = () => reject(new Error('WebSocket refusé'));
      this.ws.onmessage = (m) => {
        const msg = JSON.parse(m.data);
        if (msg.type === 'session') resolve(msg.session);
        else if (msg.type === 'event') this.events.push(msg);
        else if (msg.type === 'result') this.waiting.get(msg.id)?.(msg);
      };
    });
    this.closed = new Promise((resolve) => { this.ws.onclose = (e) => resolve(e); });
  }
  send(action, params = {}) {
    const id = this.next++;
    return new Promise((resolve, reject) => {
      this.waiting.set(id, (msg) => (msg.ok ? resolve(msg.result) : reject(new Error(`${action} : ${msg.error}`))));
      this.ws.send(JSON.stringify({ id, action, params }));
    });
  }
}

async function waitForEngine(timeoutMs = 120_000) {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    const info = await api('GET', '/').catch(() => null);
    if (info?.ok && info.result.playwright.status === 'ready') return info.result;
    await sleep(1000);
  }
  throw new Error('Le moteur Playwright ne devient pas prêt');
}

function readKey() {
  // Un APK optimisé refuse `run-as` : la clé se copie alors depuis Paramètres, dans PW_KEY ou out/key.txt.
  if (process.env.PW_KEY) return process.env.PW_KEY.trim();
  const saved = `${OUT}/../key.txt`;
  if (existsSync(saved)) return readFileSync(saved, 'utf8').trim();
  for (let attempt = 0; attempt < 30; attempt++) {
    try {
      const prefs = adb('shell', 'run-as', PKG, 'cat', 'shared_prefs/pocketwright.xml');
      const match = prefs.match(/name="api_key">([^<]+)/);
      if (match) return match[1];
    } catch {}
    execFileSync('sleep', ['1']);
  }
  throw new Error('Clé API illisible : définir PW_KEY');
}

/** Réveille le téléphone et met l'app devant, pour les captures de son écran. */
function showApp() {
  adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP');
  try { adb('shell', 'wm', 'dismiss-keyguard'); } catch {}
  adb('shell', 'am', 'start', '-n', `${PKG}/.ui.MainActivity`);
}

function tapText(text) {
  adb('shell', 'uiautomator', 'dump', '/sdcard/pw-ui.xml');
  const xml = adb('shell', 'cat', '/sdcard/pw-ui.xml');
  const node = [...xml.matchAll(/<node [^>]*>/g)].map((m) => m[0]).find((n) => n.includes(` text="${text}"`) || n.includes(`content-desc="${text}"`));
  const b = node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
  if (!b) return false;
  adb('shell', 'input', 'tap', String((+b[1] + +b[3]) >> 1), String((+b[2] + +b[4]) >> 1));
  return true;
}

// ================================================================== scénario

const site = await startSite();
adb('reverse', `tcp:${SITE_PORT}`, `tcp:${SITE_PORT}`);
adb('forward', `tcp:${API_PORT}`, 'tcp:8765');
showApp();
for (let attempt = 0; attempt < 30; attempt++) {
  try { KEY = readKey(); break; } catch { await sleep(500); }
}
if (!KEY) throw new Error('Clé API indisponible après le premier lancement');
const device = `${adb('shell', 'getprop', 'ro.product.manufacturer')} ${adb('shell', 'getprop', 'ro.product.model')}, Android ${adb('shell', 'getprop', 'ro.build.version.release')}`;
let info;
const ids = {};
const preexisting = new Set();
const ours = (list) => list.filter((s) => !preexisting.has(s.id));

await step('1. Serveur, moteur et sécurité', async () => {
  info = await waitForEngine();
  note(`Appareil : ${device}`);
  note(`Navigateur : Chromium ${info.browser.version} (WebView ${info.browser.channel}) · Playwright ${info.playwright.version} · Node ${info.playwright.node}`);
  check('Playwright embarqué prêt', info.playwright.status === 'ready', info.playwright.version);
  check('sans clé : 401', (await fetch(BASE + '/sessions')).status === 401);
  check('mauvaise clé : 401', (await fetch(BASE + '/sessions', { headers: { authorization: 'Bearer faux' } })).status === 401);
  const rebinding = await new Promise((resolve, reject) => {
    const req = http.get(BASE + '/sessions', { headers: { ...headers(), host: 'evil.example:8765' } }, res => { res.resume(); resolve({ status: res.statusCode }); });
    req.on('error', reject);
    req.setTimeout(10000, () => req.destroy(new Error('HTTP timeout')));
  });
  check('Host étranger (DNS rebinding) : 401', rebinding.status === 401, rebinding.status);
  // Les onglets déjà ouverts sur le téléphone ne sont pas à nous : on ne les touche pas.
  (await api('GET', '/sessions')).result.forEach((s) => preexisting.add(s.id));
  note(`${preexisting.size} onglet(s) déjà ouvert(s), laissés tels quels`);
});

await step('2. Session « alice » par REST : connexion, erreurs, attente automatique', async () => {
  const s = await api('POST', '/sessions', { name: 'alice', url: `${SITE}/` });
  ids.alice = s.result.id;
  check('session créée', s.ok, ids.alice);
  await cmd(ids.alice, 'goto', { url: `${SITE}/login` });
  await cmd(ids.alice, 'fill', { selector: '#user', value: 'alice' });
  await cmd(ids.alice, 'fill', { selector: 'label:has-text("Mot de passe") >> input', value: 'mauvais' });
  await cmd(ids.alice, 'click', { selector: 'role=button[name="Se connecter"]' });
  const alert = await cmd(ids.alice, 'text', { selector: 'role=alert' });
  check('mauvais mot de passe refusé', alert === 'Identifiants incorrects', alert);
  await cmd(ids.alice, 'fill', { selector: '#user', value: 'alice' });
  await cmd(ids.alice, 'fill', { selector: '#pass', value: 'secret' });
  await cmd(ids.alice, 'press', { selector: '#pass', key: 'Enter' });
  await cmd(ids.alice, 'waitForURL', { url: '**/account' });
  const hello = await cmd(ids.alice, 'text', { selector: 'h1' });
  check('connectée (cookie HttpOnly)', hello === 'Bonjour alice', hello);
  const ua = await cmd(ids.alice, 'evaluate', { expression: '() => navigator.userAgent' });
  check('evaluate d\'une fonction', /Chrome\/\d+/.test(ua), ua.match(/Chrome\/[\d.]+/)?.[0]);
  const sum = await cmd(ids.alice, 'evaluate', { expression: '([a, b]) => a + b', arg: [20, 22] });
  check('evaluate avec argument', sum === 42, sum);
});

let bob;
await step('3. Session « bob » par WebSocket : une connexion = sa session, commandes en parallèle', async () => {
  bob = new CommandSocket(`name=bob&url=${encodeURIComponent(`${SITE}/login`)}`);
  const session = await bob.session;
  ids.bob = session.id;
  check('la connexion ouvre sa propre session', ids.bob && ids.bob !== ids.alice, ids.bob);
  await bob.send('waitForLoad');
  await bob.send('fill', { selector: '#user', value: 'bob' });
  await bob.send('fill', { selector: '#pass', value: 'secret' });
  await bob.send('click', { selector: 'text=Se connecter' });
  await bob.send('waitForURL', { url: '**/account' });
  // Trois commandes envoyées d'un coup sur la même connexion.
  const [h1, url, pageTitle] = await Promise.all([bob.send('text', { selector: 'h1' }), bob.send('url'), bob.send('title')]);
  check('bob connecté', h1 === 'Bonjour bob', h1);
  check('réponses pipelinées', url.endsWith('/account') && pageTitle === 'Compte de bob', `${url} · ${pageTitle}`);
  check('événements reçus (load)', bob.events.some((e) => e.event === 'load'), `${bob.events.length} événement(s)`);
});

await step('4. Isolation des sessions (cookies)', async () => {
  await cmd(ids.alice, 'reload');
  const hello = await cmd(ids.alice, 'text', { selector: 'h1' });
  if (info.isolation) {
    check('alice reste alice après la connexion de bob', hello === 'Bonjour alice', hello);
    const guest = await api('POST', '/sessions', { name: 'invité', url: `${SITE}/account` });
    await sleep(1500);
    const where = await cmd(guest.result.id, 'url');
    check('une session neuve n\'est connectée à aucun compte', where.endsWith('/login'), where);
    await api('DELETE', `/sessions/${guest.result.id}`);
  } else {
    note(`WebView ${info.browser.version} < 128 : pas de profils séparés, les sessions partagent leurs cookies (alice voit « ${hello} »). Mettre à jour Android System WebView pour l'isolation.`);
  }
});

await step('5. Script Playwright libre (run) : liste de tâches, rendu différé, confirm(), localStorage', async () => {
  const r = await cmd(ids.alice, 'run', {
    script: `
      await page.goto('${SITE}/todo');
      await page.evaluate(() => localStorage.clear());
      await page.reload();
      for (const t of ['Acheter du pain', 'Appeler Marie', 'Réviser le contrat']) {
        await page.getByPlaceholder('Nouvelle tâche').fill(t);
        await page.getByRole('button', { name: 'Ajouter' }).click();
        await page.getByRole('listitem').filter({ hasText: t }).waitFor();
      }
      const before = await page.getByRole('listitem').count();
      let dialog = '';
      page.once('dialog', (d) => { dialog = d.message(); d.accept(); });
      await page.getByRole('button', { name: 'Supprimer Appeler Marie' }).click();
      await page.getByRole('listitem').filter({ hasText: 'Appeler Marie' }).waitFor({ state: 'detached' });
      state.todosAfter = await page.locator('#count').innerText();
      console.log('dialogue :', dialog);
      return { before, after: await page.getByRole('listitem').allInnerTexts(), count: state.todosAfter, dialog,
               storage: await page.evaluate(() => localStorage.getItem('todos')) };`,
  });
  check('3 tâches ajoutées (rendu à 600 ms attendu par Playwright)', r.result.before === 3, r.result.before);
  check('confirm() vu et accepté par Playwright', /Appeler Marie/.test(r.result.dialog), r.result.dialog);
  check('2 tâches restantes', r.result.count === '2 tâche(s)', JSON.stringify(r.result.after));
  check('journal du script renvoyé', r.logs.some((l) => l.includes('dialogue')), r.logs.join(' | '));
  const remembered = await cmd(ids.alice, 'run', { script: 'return state.todosAfter;' });
  check('`state` garde ses valeurs d\'un script à l\'autre', remembered.result === '2 tâche(s)', remembered.result);
});

let guestBrowser;
await step('6. Playwright côté client par la passerelle CDP : réseau, cadres, fichiers, captures', async () => {
  guestBrowser = await chromium.connectOverCDP(`ws://127.0.0.1:${API_PORT}/cdp?key=${encodeURIComponent(KEY)}&name=cdp-client`, { isWebView: true });
  const contexts = guestBrowser.contexts();
  const page = contexts[0].pages()[0];
  const listed = (await api('GET', '/sessions')).result;
  ids.cdp = listed.find((s) => s.name === 'cdp-client')?.id;
  check('la connexion CDP ouvre sa session, et ne voit qu\'elle', contexts.length === 1 && contexts[0].pages().length === 1 && ids.cdp, `${listed.length} sessions dans l'app, 1 page vue`);

  const logs = [];
  page.on('console', (m) => logs.push(m.text()));
  const apiCallsBefore = stats.apiCalls;
  await page.route('**/api/stats', (route) => route.fulfill({ json: { visits: 999999, sales: 7 } }));
  await page.goto(`${SITE}/dashboard`);
  await page.locator('#visits', { hasText: '999999' }).waitFor();
  check('réponse réseau simulée (page.route)', (await page.locator('#sales').innerText()) === '7' && stats.apiCalls === apiCallsBefore, `appels réels au serveur : ${stats.apiCalls - apiCallsBefore}`);
  check('console de la page reçue', logs.some((l) => l.includes('999999')), logs.join(' | '));
  await page.unroute('**/api/stats');

  await page.goto(`${SITE}/frames`);
  const inner = page.frameLocator('iframe[name="inner"]');
  await inner.getByRole('button', { name: 'Cliquer ici' }).click();
  check('clic dans une iframe (frameLocator)', (await inner.getByRole('button').innerText()) === 'Cliqué dans le cadre');

  await page.goto(`${SITE}/upload`);
  await page.locator('#doc').setInputFiles({ name: 'rapport.txt', mimeType: 'text/plain', buffer: Buffer.from('Bonjour PocketWright') });
  await page.getByRole('button', { name: 'Envoyer' }).click();
  const received = await page.locator('#result').innerText();
  check('envoi de fichier (setInputFiles)', received === 'Reçu : rapport.txt (20 octets)', received);

  await page.goto(`${SITE}/long`);
  const viewportShot = await page.screenshot();
  const fullShot = await page.screenshot({ fullPage: true });
  const elementShot = await page.locator('#end').screenshot();
  writeFileSync(`${OUT}/cdp-viewport.png`, viewportShot);
  writeFileSync(`${OUT}/cdp-fullpage.png`, fullShot);
  writeFileSync(`${OUT}/cdp-element.png`, elementShot);
  const v = pngSize(viewportShot), f = pngSize(fullShot), e = pngSize(elementShot);
  check('page.screenshot()', v.width > 300 && v.height > 300, `${v.width}×${v.height}`);
  check('page.screenshot({ fullPage })', f.height > v.height * 2, `${f.width}×${f.height}`);
  check('locator.screenshot()', e.width > 100 && e.height > 20 && e.height < v.height, `${e.width}×${e.height}`);

  await guestBrowser.close();
  const still = await api('GET', `/sessions/${ids.cdp}`);
  check('browser.close() déconnecte sans fermer la session', still.ok, still.result?.url);
});

await step('7. Sessions en parallèle', async () => {
  const extra = await Promise.all([1, 2, 3].map((i) => api('POST', '/sessions', { name: `parallèle-${i}` })));
  const all = [ids.alice, ...extra.map((r) => r.result.id)];
  const start = Date.now();
  const titles = await Promise.all(all.map((id) => cmd(id, 'goto', { url: `${SITE}/slow?ms=2000` }).then((r) => r.title)));
  const elapsed = Date.now() - start;
  check('4 pages lentes (2 s) chargées en même temps', titles.every((t) => t === 'Lente') && elapsed < 6000, `${elapsed} ms`);
  const native = await cmd(extra[0].result.id, 'goto', { url: `${SITE}/login`, engine: 'native' });
  await cmd(extra[0].result.id, 'fill', { selector: '#user', value: 'natif', engine: 'native' });
  const value = await cmd(extra[0].result.id, 'evaluate', { expression: "document.querySelector('#user').value", engine: 'native' });
  check('moteur natif de secours (goto, fill, evaluate)', native.title === 'Connexion' && value === 'natif', value);
  const shot = await fetch(`${BASE}/sessions/${extra[0].result.id}/screenshot`, { headers: headers() });
  const png = Buffer.from(await shot.arrayBuffer());
  writeFileSync(`${OUT}/rest-screenshot.png`, png);
  check('GET /screenshot rend une image PNG', shot.headers.get('content-type') === 'image/png' && pngSize(png).width > 300, `${pngSize(png).width}×${pngSize(png).height}`);
  for (const r of extra) await api('DELETE', `/sessions/${r.result.id}`);
});

await step('7 bis. WebGL : rendu réel et captures', async () => {
  // Du vert WebGL, un carré rouge HTML posé dessus : la capture doit garder les deux, dans cet ordre.
  const html = `<!doctype html><meta name=viewport content="width=device-width,initial-scale=1"><body style="margin:0;background:#fff">
    <div style="position:relative"><canvas id=c width=400 height=400 style="display:block;width:100%;border-radius:1px"></canvas>
    <div style="position:absolute;left:0;top:0;width:40%;height:40%;background:#f00"></div></div>
    <script>const g=c.getContext('webgl');(function d(){g.clearColor(.1,.6,.3,1);g.clear(g.COLOR_BUFFER_BIT);requestAnimationFrame(d)})();</script>`;
  const id = (await api('POST', '/sessions', { name: 'webgl' })).result.id;
  await cmd(id, 'setContent', { html });
  await sleep(800);
  const gl = await cmd(id, 'evaluate', { expression: `() => { const g = document.createElement('canvas').getContext('webgl2'); const i = g && g.getExtension('WEBGL_debug_renderer_info'); return { renderer: i && g.getParameter(i.UNMASKED_RENDERER_WEBGL), error: g && g.getError() }; }` });
  check('WebGL 2 disponible, sur le vrai GPU', !!gl.renderer && gl.error === 0, gl.renderer);
  const before = await cmd(id, 'evaluate', { expression: `() => document.querySelector('#c').getAttribute('style')` });
  // La page décode elle-même l'image : deux points, au milieu du canvas et dans le carré rouge.
  const colors = (png) => cmd(id, 'evaluate', {
    expression: `async (b64) => {
      const image = await createImageBitmap(await (await fetch('data:image/png;base64,' + b64)).blob());
      const box = document.querySelector('#c').getBoundingClientRect(), k = image.width / innerWidth;
      const out = new OffscreenCanvas(image.width, image.height).getContext('2d'); out.drawImage(image, 0, 0);
      const at = (x, y) => [...out.getImageData(Math.round(x * k), Math.round(y * k), 1, 1).data].slice(0, 3);
      return { canvas: at(box.width * .75, box.height * .75), html: at(box.width * .15, box.height * .15) };
    }`,
    arg: png.toString('base64'),
  });
  const green = (rgb) => Math.abs(rgb[0] - 26) < 8 && Math.abs(rgb[1] - 153) < 8 && Math.abs(rgb[2] - 77) < 8;
  const red = (rgb) => rgb[0] > 245 && rgb[1] < 10 && rgb[2] < 10;
  const rest = Buffer.from(await (await fetch(`${BASE}/sessions/${id}/screenshot`, { headers: headers() })).arrayBuffer());
  writeFileSync(`${OUT}/webgl-rest.png`, rest);
  const restColors = await colors(rest);
  check('GET /screenshot montre le canvas WebGL', green(restColors.canvas), restColors.canvas.join(','));
  check('GET /screenshot garde le HTML au-dessus du canvas', red(restColors.html), restColors.html.join(','));
  const run = await api('POST', `/sessions/${id}/run`, { timeout: 30000, script: `return (await page.screenshot({ type: 'png' })).toString('base64');` });
  const shot = Buffer.from(run.result?.result ?? run.result?.value ?? run.result, 'base64');
  writeFileSync(`${OUT}/webgl-playwright.png`, shot);
  const shotColors = await colors(shot);
  check('page.screenshot() montre le canvas WebGL', green(shotColors.canvas), shotColors.canvas.join(','));
  const after = await cmd(id, 'evaluate', { expression: `() => document.querySelector('#c').getAttribute('style')` });
  check('style du canvas rendu après la capture', after === before, after);
  await api('DELETE', `/sessions/${id}`);
});

await step('8. L\'écran de l\'app : liste des sessions et vue en direct', async () => {
  showApp();
  await sleep(2500);
  const count = (await api('GET', '/sessions')).result.length;
  writeFileSync(`${OUT}/app-browser.png`, adbBinary('exec-out', 'screencap', '-p'));
  tapText(`Onglets : ${count}`);
  await sleep(2500);
  writeFileSync(`${OUT}/app-sessions.png`, adbBinary('exec-out', 'screencap', '-p'));
  check('capture de la grille des onglets', true, `${OUT}/app-sessions.png`);
  // Les sessions de l'API ont leur propre rubrique dès que l'utilisateur a aussi des onglets.
  if (tapText('API')) await sleep(1200);
  if (tapText('Lente')) {
    await sleep(3000);
    writeFileSync(`${OUT}/app-live.png`, adbBinary('exec-out', 'screencap', '-p'));
    const shown = (await api('GET', '/sessions')).result.find((s) => s.shown);
    check('vue en direct d\'une session', !!shown, shown?.name);
    if (shown) {
      const b = await chromium.connectOverCDP(`ws://127.0.0.1:${API_PORT}/cdp/${shown.id}?key=${encodeURIComponent(KEY)}`, { isWebView: true });
      const shot = await b.contexts()[0].pages()[0].screenshot();
      writeFileSync(`${OUT}/cdp-shown.png`, shot);
      check('capture Playwright d\'une session affichée', pngSize(shot).width > 300, `${pngSize(shot).width}×${pngSize(shot).height}`);
      await b.close();
    }
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK');
  } else {
    note('onglet introuvable à l\'écran (téléphone verrouillé ?) : vue en direct non testée');
  }
});

await step('9. Persistance : l\'app est tuée puis relancée', async () => {
  await sleep(2000);
  const before = (await api('GET', '/sessions')).result.map((s) => s.id).sort();
  adb('shell', 'am', 'force-stop', PKG);
  await sleep(1500);
  const down = await fetch(BASE + '/').then(() => 'répond', () => 'arrêtée');
  check('l\'app est bien arrêtée', down === 'arrêtée', down);
  showApp();
  const started = Date.now();
  await waitForEngine();
  note(`relancée et Playwright prêt en ${Date.now() - started} ms`);
  const after = (await api('GET', '/sessions')).result.map((s) => s.id).sort();
  check('les mêmes sessions reviennent', JSON.stringify(after) === JSON.stringify(before), after.join(', '));
  await cmd(ids.alice, 'goto', { url: `${SITE}/account` });
  const hello = await cmd(ids.alice, 'text', { selector: 'h1' });
  check('le cookie de connexion a survécu', /^Bonjour (alice|bob)$/.test(hello), hello);
  await cmd(ids.alice, 'goto', { url: `${SITE}/todo` });
  const count = await cmd(ids.alice, 'text', { selector: '#count' }).catch(() => '');
  const todos = await cmd(ids.alice, 'evaluate', { expression: "() => JSON.parse(localStorage.getItem('todos') || '[]')" });
  check('le localStorage a survécu', todos.length === 2, JSON.stringify(todos) + ' ' + count);
  check('la connexion WebSocket de bob a été coupée avec l\'app', await Promise.race([bob.closed.then(() => true), sleep(3000).then(() => false)]));
});

await step('10. Fermeture par l\'API', async () => {
  const again = new CommandSocket(`session=${ids.bob}`);
  const s = await again.session;
  check('reprise de la session de bob par une nouvelle connexion', s.id === ids.bob, s.url);
  const closed = await again.send('close');
  const reason = await again.closed;
  check('commande close par WebSocket', closed.closed === true && reason.code === 1000, `code ${reason.code}`);
  check('bob n\'existe plus', (await api('GET', `/sessions/${ids.bob}`)).status === 404);

  const b = await chromium.connectOverCDP(`ws://127.0.0.1:${API_PORT}/cdp/${ids.cdp}?key=${encodeURIComponent(KEY)}`, { isWebView: true });
  await b.contexts()[0].pages()[0].close();
  await sleep(1000);
  check('page.close() par CDP ferme la session', (await api('GET', `/sessions/${ids.cdp}`)).status === 404);
  await b.close().catch(() => {});

  const del = await api('DELETE', `/sessions/${ids.alice}`);
  check('DELETE /sessions/{id}', del.ok && del.result.closed === true);
  check('plus aucune session de test', ours((await api('GET', '/sessions')).result).length === 0);
});

if (process.env.SUITE !== 'baseline') {
  await advanced({ step, check, api, cmd, headers, BASE, SITE, KEY, chromium, sleep, preexisting });
}

// ------------------------------------------------------------------ bilan

const failed = results.filter((r) => !r.ok);
const passed = results.filter((r) => r.ok && !r.note).length;
console.log(`\n${failed.length === 0 ? '✔' : '✘'} ${passed}/${passed + failed.length} vérifications réussies en ${Math.round((Date.now() - t0) / 1000)} s`);
writeFileSync(`${OUT}/results.json`, JSON.stringify({ finishedAt: new Date().toISOString(), durationMs: Date.now() - t0, passed, failed: failed.length, device, browser: info?.browser, playwright: info?.playwright, isolation: info?.isolation, results }, null, 2));
const report = [
  '# Rapport E2E PocketWright', '',
  `Date UTC : ${new Date().toISOString()}`, `Appareil : ${device}`,
  `Résultat : ${passed}/${passed + failed.length} contrôles réussis ; durée ${Math.round((Date.now() - t0) / 1000)} s.`, '',
  ...results.map(r => `- ${r.note ? 'INFO' : r.ok ? 'PASS' : 'FAIL'} — ${r.section} — ${r.name}${r.detail ? ': ' + r.detail.replaceAll('\n', ' ') : ''}`),
  '', 'Limites : un seul téléphone ; pas de validation exhaustive de toutes les versions Android, de l’autonomie longue durée, du redémarrage système ou du réseau mobile réel.',
].join('\n');
writeFileSync(`${OUT}/report.md`, report);
site.close();
adb('reverse', '--remove', `tcp:${SITE_PORT}`);
adb('forward', '--remove', `tcp:${API_PORT}`);
process.exit(failed.length === 0 ? 0 : 1);
