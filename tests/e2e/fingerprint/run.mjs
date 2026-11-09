#!/usr/bin/env node
// Vérifie sur le téléphone que chaque session a une empreinte réaliste, cohérente, stable, et différente des autres.
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { setTimeout as sleep } from 'node:timers/promises';
import { startSite } from './site.mjs';

const SERIAL = process.env.PW_SERIAL ?? process.env.ANDROID_SERIAL;
if (!SERIAL) throw new Error('ANDROID_SERIAL manquant (adb devices)');
const ADB = process.env.ADB ?? `${process.env.HOME}/Android/Sdk/platform-tools/adb`;
const KEY = process.env.PW_KEY ?? readFileSync(new URL('../out/key.txt', import.meta.url), 'utf8').trim();
const BASE = 'http://127.0.0.1:8765';
const PORT = 18091;
const SITE = `http://127.0.0.1:${PORT}/`;
const PACKAGE = 'com.afroni.pocketwright';

let passed = 0;
const failures = [];
function check(name, ok, detail = '') {
  if (ok) {
    passed++;
    console.log(`  ok    ${name}${detail !== '' ? ` (${detail})` : ''}`);
  } else {
    failures.push(name);
    console.log(`  ÉCHEC ${name}${detail !== '' ? ` : ${detail}` : ''}`);
  }
}
const section = (title) => console.log(`\n${title}`);

function adb(...args) {
  for (let attempt = 1; ; attempt++) {
    try {
      return execFileSync(ADB, ['-s', SERIAL, ...args], { encoding: 'utf8', timeout: 30_000, stdio: ['ignore', 'pipe', 'pipe'] }).trim();
    } catch (error) {
      if (attempt >= 4 || !/not found|offline|no devices|closed/i.test(`${error.stderr}${error.message}`)) throw error;
      execFileSync(ADB, ['-s', SERIAL, 'wait-for-device'], { timeout: 60_000, stdio: 'ignore' });
    }
  }
}

async function request(method, path, body, timeoutMs = 90_000) {
  const response = await fetch(BASE + path, { method, headers: { Authorization: `Bearer ${KEY}` }, body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(timeoutMs) });
  return { status: response.status, body: await response.json().catch(() => null) };
}
async function api(method, path, body) {
  const answer = await request(method, path, body);
  if (!answer.body?.ok) throw new Error(`${path} : HTTP ${answer.status} ${answer.body?.error}`);
  return answer.body.result;
}
const run = async (id, script) => {
  const result = await api('POST', `/sessions/${id}/run`, { script, timeout: 85_000 });
  return result?.result ?? result?.value ?? result;
};
const collect = (id) => run(id, 'await page.waitForFunction(() => window.collectAll); return await page.evaluate(() => window.collectAll());');

const created = [];
async function open(fingerprint, name) {
  const session = await api('POST', '/sessions', { name, url: SITE, ...(fingerprint === undefined ? {} : { fingerprint }) });
  created.push(session.id);
  await sleep(1200);
  return session.id;
}

async function waitForApi(timeoutMs = 90_000) {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    try {
      adb('forward', 'tcp:8765', 'tcp:8765');
      if ((await request('GET', '/', undefined, 4000)).body?.result?.playwright?.status === 'ready') return true;
    } catch {
      // Pas encore prêt.
    }
    await sleep(700);
  }
  return false;
}

const header = (all, name) => {
  const headers = JSON.parse(all.main.echo);
  return Object.entries(headers).find(([key]) => key.toLowerCase() === name)?.[1];
};

// Ce que répond Chrome sur Android, d'après les sources de Chromium 156 (content_settings_registry.cc et contextes).
const CHROME_PERMISSIONS = {
  geolocation: 'prompt', notifications: 'prompt', midi: 'prompt', camera: 'prompt', microphone: 'prompt',
  'background-fetch': 'granted', 'background-sync': 'granted', 'persistent-storage': 'prompt', accelerometer: 'granted',
  gyroscope: 'granted', magnetometer: 'granted', 'screen-wake-lock': 'granted', nfc: 'prompt', 'clipboard-read': 'prompt',
  'clipboard-write': 'granted', 'payment-handler': 'granted', 'idle-detection': 'prompt', 'storage-access': 'prompt',
  'window-management': 'prompt', pushVisible: 'prompt',
};

function coherent(label, all, fp) {
  const { main, iframe, worker } = all;
  const major = fp.userAgent.match(/Chrome\/(\d+)/)[1];
  check(`${label} : agent utilisateur de Chrome Android`, main.userAgent === fp.userAgent && /Android 10; K/.test(main.userAgent) && !/; wv|Version\//.test(main.userAgent), main.userAgent.slice(0, 44));
  check(`${label} : en-tête identique au JavaScript`, header(all, 'user-agent') === main.userAgent);
  const brandsHeader = header(all, 'sec-ch-ua') ?? '';
  check(`${label} : marques de Chrome dans les en-têtes`, brandsHeader.includes(`"Google Chrome";v="${major}"`) && !brandsHeader.includes('WebView'), brandsHeader);
  check(`${label} : marques identiques en JavaScript`, JSON.parse(main.brands).every((b) => brandsHeader.includes(`"${b.brand}";v="${b.version}"`)) && !main.brands.includes('WebView'));
  check(`${label} : modèle cohérent (en-tête, JS, appareil)`, header(all, 'sec-ch-ua-model') === `"${fp.device.model}"` && JSON.parse(main.highEntropy).model === fp.device.model, fp.device.model);
  check(`${label} : version d'Android cohérente`, header(all, 'sec-ch-ua-platform-version') === `"${fp.android}.0.0"` && JSON.parse(main.highEntropy).platformVersion === `${fp.android}.0.0`, fp.android);
  check(`${label} : plateforme et processeur`, main.platform === 'Linux armv81' && main.hardwareConcurrency === 8 && main.uaPlatform === 'Android' && main.mobile === true, `${main.platform} ${main.hardwareConcurrency} ${main.uaPlatform} ${main.mobile}`);
  check(`${label} : mémoire annoncée`, main.deviceMemory === fp.memory, fp.memory);
  check(`${label} : langues cohérentes`, main.languages === fp.languages.join(',') && main.language === fp.languages[0] && (header(all, 'accept-language') ?? '').startsWith(fp.languages[0]), main.languages);
  check(`${label} : écran de l'appareil`, main.screen.startsWith(`${fp.screen.width},${fp.screen.height},${fp.screen.width},${fp.screen.height},`) && main.devicePixelRatio === 2, main.screen);
  check(`${label} : hauteur de page réaliste`, main.window.endsWith(`,${fp.screen.width},${fp.screen.height - fp.screen.chrome}`), main.window);
  check(`${label} : fuseau annoncé`, main.timezone === fp.timezone, main.timezone);
  check(`${label} : nom de l'app absent des requêtes`, !header(all, 'x-requested-with'), JSON.stringify(header(all, 'x-requested-with') ?? null));
  check(`${label} : aucune adresse dans WebRTC`, main.webrtc.rawIp === 0 && main.webrtc.candidates > 0, JSON.stringify(main.webrtc));
  check(`${label} : canvas stable d'une lecture à l'autre`, main.canvasStable === true);
  check(`${label} : WebGL annonce le GPU du catalogue`, main.webgl?.unmaskedRenderer === 'PowerVR Rogue GE8320' && main.webgl?.unmaskedVendor === 'Imagination Technologies', main.webgl?.unmaskedRenderer ?? JSON.stringify(main.webgl));
  check(`${label} : WebGL2 disponible`, main.webgl2 && typeof main.webgl2 === 'object');
  const wrong = (seen) => (typeof seen === 'object' ? Object.keys(CHROME_PERMISSIONS).filter((name) => seen[name] !== CHROME_PERMISSIONS[name]).map((name) => `${name}=${seen[name]}`).join(' ') : String(seen));
  check(`${label} : permissions de Chrome`, wrong(main.permissions) === '', wrong(main.permissions));
  const notice = main.notification;
  check(`${label} : Notification de Chrome`, notice?.permission === 'default' && notice.maxActions === 2 && notice.source === 'function Notification() { [native code] }' && /\[native code\]/.test(notice.getter) && /Illegal constructor/.test(notice.construct) && notice.parent && notice.tag === '[object Notification]' && notice.ownKeys === 0 && notice.hidden, JSON.stringify(notice).slice(0, 110));
  check(`${label} : permissions et Notification dans le worker`, worker.permissions?.notifications === 'prompt' && worker.permissions?.['background-sync'] === 'granted' && worker.permissions?.['background-fetch'] === 'prompt' && worker.notification?.permission === 'default', JSON.stringify(worker.notification?.permission));
  check(`${label} : fonctions d'allure native`, /\[native code\]/.test(main.nativeToString) && /\[native code\]/.test(main.canvasToString), main.canvasToString.slice(0, 40));
  const same = (name, other) => check(`${label} : ${name} identique à la page`, typeof other === 'object' && ['userAgent', 'platform', 'languages', 'hardwareConcurrency', 'deviceMemory', 'timezone', 'locale', 'brands', 'highEntropy', 'offscreenCanvas'].every((key) => other[key] === main[key]), typeof other === 'object' ? ['userAgent', 'platform', 'languages', 'hardwareConcurrency', 'deviceMemory', 'timezone', 'locale', 'brands', 'highEntropy', 'offscreenCanvas'].filter((key) => other[key] !== main[key]).join() : other);
  same('iframe', iframe);
  same('worker', worker);
  if (typeof all.serviceWorker === 'object') check(`${label} : service worker aligné`, ['userAgent', 'platform', 'languages', 'hardwareConcurrency', 'deviceMemory', 'timezone'].every((key) => all.serviceWorker[key] === main[key]), ['userAgent', 'platform', 'languages', 'hardwareConcurrency', 'deviceMemory', 'timezone'].filter((key) => all.serviceWorker[key] !== main[key]).join());
}

const nearAudio = (a, b) => a.main.audio.sum === b.main.audio.sum && a.main.audio.samples === b.main.audio.samples;

const stableKeys = ['canvas', 'imageData', 'measureText', 'offscreenCanvas', 'rects', 'screen', 'languages', 'highEntropy', 'math', 'webgl', 'webgl2'];
const stable = (a, b) => stableKeys.filter((key) => JSON.stringify(a.main[key]) !== JSON.stringify(b.main[key]));

async function main() {
  adb('forward', 'tcp:8765', 'tcp:8765');
  adb('reverse', `tcp:${PORT}`, `tcp:${PORT}`);
  if (!(await waitForApi())) throw new Error('API injoignable');
  const server = await startSite(PORT);
  try {
    section('Quatre sessions, chacune son empreinte');
    const ids = [];
    for (const name of ['a', 'b', 'c', 'd']) ids.push(await open(undefined, `fp-${name}`));
    const prints = [];
    const data = [];
    for (const id of ids) {
      prints.push(await api('GET', `/sessions/${id}/fingerprint`));
      data.push(await collect(id));
    }
    console.log(`  info  ${prints.map((p) => `${p.device.name}/Android ${p.android}/${p.memory} Go`).join(' · ')}`);
    check('graines toutes différentes', new Set(prints.map((p) => p.seed)).size === 4);
    check('combinaisons toutes différentes', new Set(prints.map((p) => `${p.device.model}|${p.android}|${p.memory}|${p.languages}`)).size === 4);
    for (const key of ['canvas', 'imageData', 'offscreenCanvas']) check(`${key} différent par session`, new Set(data.map((d) => d.main[key])).size === 4, data.map((d) => d.main[key]).join(' '));
    check('WebGL différent par session', new Set(data.map((d) => d.main.webgl.pixels)).size === 4, data.map((d) => d.main.webgl.pixels).join(' '));
    check('audio différent par session', new Set(data.map((d) => d.main.audio.sum)).size === 4, data.map((d) => d.main.audio.sum).join(' '));

    section('Cohérence');
    prints.forEach((print, index) => coherent('abcd'[index], data[index], print));

    section('Stabilité');
    for (const [index, id] of ids.entries()) {
      const again = await collect(id);
      check(`${'abcd'[index]} : relecture identique`, stable(data[index], again).length === 0, stable(data[index], again).join());
    }
    await api('POST', `/sessions/${ids[0]}/reload`, {});
    await sleep(1200);
    const reloaded = await collect(ids[0]);
    check('identique après rechargement', stable(data[0], reloaded).length === 0, stable(data[0], reloaded).join());
    check('audio identique après rechargement', nearAudio(reloaded, data[0]), `${reloaded.main.audio.sum} / ${data[0].main.audio.sum}`);

    section('Onglets d\'une même session');
    const tab = await run(ids[0], `const other = await context.newPage(); await other.goto(${JSON.stringify(SITE)}); await other.waitForFunction(() => window.collectAll); const answer = await other.evaluate(() => window.collectAll()); await other.close(); return answer;`);
    check('un nouvel onglet garde l\'empreinte', tab.main.highEntropy === data[0].main.highEntropy && tab.main.canvas === data[0].main.canvas && tab.main.languages === data[0].main.languages, tab.main.canvas);

    section('Changement à la volée et isolation');
    await api('POST', `/sessions/${ids[0]}/addCookies`, { cookies: [{ name: 'garde', value: 'oui', url: SITE }] });
    const before = data[1];
    const rotated = await api('POST', `/sessions/${ids[0]}/setFingerprint`, {});
    check('nouvelle graine', rotated.seed !== prints[0].seed, `${prints[0].seed} → ${rotated.seed}`);
    const now = await collect(ids[0]);
    check('nouveau canvas', now.main.canvas !== data[0].main.canvas);
    check('nouvel audio', now.main.audio.sum !== data[0].main.audio.sum);
    coherent('après changement', now, rotated);
    check('l\'empreinte lue est la nouvelle', (await api('GET', `/sessions/${ids[0]}/fingerprint`)).seed === rotated.seed);
    const cookies = await api('POST', `/sessions/${ids[0]}/cookies`, {});
    check('cookies conservés au changement', JSON.stringify(cookies).includes('garde'));
    const untouched = await collect(ids[1]);
    check('l\'autre session n\'a pas bougé', stable(before, untouched).length === 0 && untouched.main.timezone === before.main.timezone, stable(before, untouched).join());

    section('Empreinte imposée');
    const custom = await open({ device: 'SM-A045F', locale: 'en-GB', timezone: 'Asia/Tokyo', seed: 'abc123' }, 'fp-e');
    const customPrint = await api('GET', `/sessions/${custom}/fingerprint`);
    const customData = await collect(custom);
    check('appareil demandé', customPrint.device.model === 'SM-A045F' && header(customData, 'sec-ch-ua-model') === '"SM-A045F"');
    check('fuseau demandé, sans toucher les autres', customData.main.timezone === 'Asia/Tokyo' && (await collect(ids[1])).main.timezone === before.main.timezone, customData.main.timezone);
    check('langue demandée', customData.main.language === 'en-GB');
    check('worker au même fuseau', customData.worker.timezone === 'Asia/Tokyo');
    const twin = await open({ device: 'SM-A045F', locale: 'en-GB', timezone: 'Asia/Tokyo', seed: 'abc123' }, 'fp-f');
    const twinData = await collect(twin);
    check('même graine, même empreinte', twinData.main.canvas === customData.main.canvas && nearAudio(twinData, customData), `${twinData.main.audio.sum} / ${customData.main.audio.sum}`);
    check('appareil inconnu refusé', (await request('POST', '/sessions', { name: 'x', fingerprint: { device: 'iPhone' } })).status === 404);
    check('combinaison impossible refusée', (await request('POST', '/sessions', { name: 'x', fingerprint: { device: 'SM-A045F', android: 9 } })).status === 400);
    check('catalogue disponible', (await api('GET', '/fingerprints/devices')).length >= 10);

    section('Appareil réel sur demande');
    const real = await open(false, 'fp-real');
    const realData = await collect(real);
    check('agent utilisateur réel', /Android 13/.test(realData.main.userAgent), realData.main.userAgent.slice(0, 44));
    check('canvas stable (bruit propre)', realData.main.canvasStable === true);
    check('pas d\'adresse dans WebRTC', realData.main.webrtc.rawIp === 0, JSON.stringify(realData.main.webrtc));
    check('permissions et Notification réelles de la WebView', realData.main.notification === 'absente' && realData.main.permissions?.notifications === 'denied', `${realData.main.notification} ${realData.main.permissions?.notifications}`);
    check('Notification.requestPermission finit comme une invite fermée', (await run(ids[0], 'return await page.evaluate(() => Notification.requestPermission());')) === 'default');

    section('Redémarrage de l\'app');
    const kept = await api('GET', `/sessions/${ids[1]}/fingerprint`);
    await api('POST', '/updates/restart', {});
    await sleep(2500);
    check('API de retour', await waitForApi());
    const after = await api('GET', `/sessions/${ids[1]}/fingerprint`);
    check('empreinte conservée', JSON.stringify(after) === JSON.stringify(kept));
    await api('POST', `/sessions/${ids[1]}/reload`, {});
    await sleep(1500);
    const restored = await collect(ids[1]);
    check('mêmes valeurs après redémarrage', stable(before, restored).length === 0, stable(before, restored).join());
    coherent('après redémarrage', restored, after);
  } finally {
    for (const id of created) await api('DELETE', `/sessions/${id}`).catch(() => {});
    server.close();
    adb('reverse', '--remove', `tcp:${PORT}`);
  }
}

try {
  await main();
} catch (error) {
  failures.push(`Arrêt inattendu : ${error.message}`);
  console.error(error);
}
console.log(`\n${passed} réussis, ${failures.length} en échec`);
for (const failure of failures) console.log(`  - ${failure}`);
process.exit(failures.length ? 1 : 0);
