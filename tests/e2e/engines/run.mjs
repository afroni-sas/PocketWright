// Compare des moteurs WebView dans une copie isolée de l'app (autre identifiant, aucun droit SMS) : ne touche ni à
// l'app installée ni à ses données. Sans moteur indiqué, mesure simplement l'app installée, sans la redémarrer.
// Usage : run.mjs SORTIE tours [nom=fichier.apk ...]
import { execFileSync, execSync } from 'node:child_process';
import { createServer } from 'node:http';
import { readFileSync, writeFileSync, createReadStream } from 'node:fs';

const OUT = process.argv[2];
const ROUNDS = Number(process.argv[3]);
const ENGINES = process.argv.slice(4).map((item) => ({ name: item.split('=')[0], apk: item.split('=')[1] }));
const HOME = process.env.HOME;
const ADB = `${HOME}/Android/Sdk/platform-tools/adb`;
const SERIAL = process.env.PW_SERIAL ?? process.env.ANDROID_SERIAL;
if (!SERIAL) throw new Error('ANDROID_SERIAL manquant (adb devices)');
const INSTALLED = ENGINES.length === 0;
const PKG = INSTALLED ? 'com.afroni.pocketwright' : 'com.afroni.pocketwright.probe';
const API = INSTALLED ? 8765 : 8766;
const SITE = 18093;
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
function adb(...args) {
  for (let attempt = 1; ; attempt++) {
    try {
      return execFileSync(ADB, ['-s', SERIAL, ...args], { encoding: 'utf8', timeout: 240_000, maxBuffer: 1 << 26, stdio: ['ignore', 'pipe', 'pipe'] }).trim();
    } catch (error) {
      if (attempt >= 5 || !/not found|offline|no devices|closed|protocol fault/i.test(`${error.stderr}${error.message}`)) throw error;
      execFileSync(ADB, ['-s', SERIAL, 'wait-for-device'], { timeout: 90_000, stdio: 'ignore' });
    }
  }
}
const shell = (script) => adb('shell', script);
const runAs = (script) => shell(`run-as ${PKG} sh -c '${script}'`);
const LOG = (...args) => console.log(new Date().toISOString().slice(11, 19), ...args);

const bench = readFileSync(new URL('./bench.js', import.meta.url), 'utf8');
const heavy = `<!doctype html><meta charset=utf-8><title>lourde</title><style>${Array.from({ length: 1500 }, (_, i) => `.r${i}{margin:${i % 9}px;color:rgb(${i % 255},0,0)}`).join('')}</style><body>${Array.from({ length: 2500 }, (_, i) => `<div class=r${i % 1500}><span>ligne ${i}</span><a href=#${i}>lien</a></div>`).join('')}<script>${Array.from({ length: 4000 }, (_, i) => `function f${i}(a){return a+${i}}`).join('\n')};window.done=f3999(1);</script>`;
const server = createServer((request, response) => {
  if (request.url === '/bench.js') { response.writeHead(200, { 'content-type': 'text/javascript' }); response.end(bench); return; }
  if (request.url === '/lourde') { response.writeHead(200, { 'content-type': 'text/html', 'cache-control': 'no-store' }); response.end(heavy); return; }
  response.writeHead(200, { 'content-type': 'text/html' });
  response.end('<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width"><title>banc</title><body><script src=/bench.js></script>');
});
await new Promise((resolve) => server.listen(SITE, '127.0.0.1', resolve));
adb('reverse', `tcp:${SITE}`, `tcp:${SITE}`);

let KEY = INSTALLED ? readFileSync(new URL('../out/key.txt', import.meta.url), 'utf8').trim() : '';
async function request(method, path, body, timeoutMs = 120_000) {
  const response = await fetch(`http://127.0.0.1:${API}${path}`, { method, headers: { Authorization: `Bearer ${KEY}` }, body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(timeoutMs) });
  const data = await response.json().catch(() => null);
  if (!data?.ok) throw new Error(`${path} : HTTP ${response.status} ${data?.error}`);
  return data.result;
}
async function waitReady(timeoutMs = 120_000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      adb('forward', `tcp:${API}`, `tcp:${API}`);
      const answer = await fetch(`http://127.0.0.1:${API}/`, { headers: { Authorization: `Bearer ${KEY}` }, signal: AbortSignal.timeout(2500) });
      if ((await answer.json()).result?.playwright?.status === 'ready') return Date.now() - start;
    } catch { /* pas encore */ }
    await sleep(250);
  }
  throw new Error('moteur non prêt');
}
function deploy(engine) {
  const version = execSync(`${HOME}/Android/Sdk/build-tools/37.0.0/aapt2 dump badging "${engine.apk}" | sed -n "s/.*versionName='\\([^']*\\)'.*/\\1/p" | head -1`, { encoding: 'utf8' }).trim();
  adb('shell', 'am', 'force-stop', PKG);
  runAs('rm -rf files/chromium app_webview cache/WebView; mkdir -p files/chromium/' + version + '/libs shared_prefs');
  execSync(`"${ADB}" -s ${SERIAL} exec-in run-as ${PKG} sh -c 'cat > files/chromium/${version}/base.apk' < "${engine.apk}"`, { timeout: 280_000, stdio: 'ignore', shell: '/bin/bash' });
  runAs(`cd files/chromium/${version} && unzip -qo base.apk "lib/arm64-v8a/*" -d x && mv x/lib/arm64-v8a libs/arm64-v8a && rm -rf x && chmod 444 base.apk && chmod 755 libs libs/arm64-v8a . .. && chmod 755 libs/arm64-v8a/*`);
  const prefs = `<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map><string name="active">${version}</string><int name="attempts" value="0" /></map>`;
  execSync(`"${ADB}" -s ${SERIAL} exec-in run-as ${PKG} sh -c 'cat > shared_prefs/pocketwright-engine.xml'`, { input: prefs, timeout: 30_000, stdio: ['pipe', 'ignore', 'ignore'] });
  return version;
}
function memory() {
  const pids = shell(`ps -A -o PID,NAME | grep ${PKG} | awk '{print $1}'`).split(/\s+/).filter(Boolean);
  let total = 0;
  for (const pid of pids) {
    const match = shell(`dumpsys meminfo ${pid} | grep "TOTAL PSS"`).match(/TOTAL PSS:\s+(\d+)/);
    if (match) total += Number(match[1]);
  }
  return { mo: Math.round(total / 1024), processus: pids.length };
}
const run = async (id, script, timeout = 110_000) => {
  const r = await request('POST', `/sessions/${id}/run`, { script, timeout }, timeout + 10_000);
  return r?.result ?? r?.value ?? r;
};

const results = [];
for (let round = 0; round < ROUNDS; round++) {
  const order = INSTALLED ? [{ name: 'installé' }] : round % 2 ? [...ENGINES].reverse() : ENGINES;
  for (const engine of order) {
    const entry = { moteur: engine.name, tour: round };
    try {
      if (INSTALLED) {
        adb('forward', `tcp:${API}`, `tcp:${API}`);
      } else {
      LOG('déploiement', engine.name);
      entry.version = deploy(engine);
      adb('shell', 'am', 'start', '-n', `${PKG}/com.afroni.pocketwright.ui.MainActivity`);
      await sleep(3000);
      if (!KEY) KEY = runAs('cat shared_prefs/pocketwright.xml').match(/name="api_key">([^<]+)</)[1];
      adb('shell', 'am', 'force-stop', PKG);
      await sleep(1500);
      adb('forward', `tcp:${API}`, `tcp:${API}`);
      adb('shell', 'am', 'start', '-n', `${PKG}/com.afroni.pocketwright.ui.MainActivity`);
      const t0 = Date.now();
      entry.demarrageMs = await waitReady();
      entry.demarrageMs = Date.now() - t0;
      entry.drapeaux = shell(`logcat -d -v brief | grep -E "Réglages du moteur|Moteur Chromium" | tail -2`).replace(/\s+/g, ' ').slice(0, 170);
      await sleep(4000);
      }
      let start = Date.now();
      const session = await request('POST', '/sessions', { name: 'banc', url: `http://127.0.0.1:${SITE}/`, fingerprint: false });
      entry.creationSessionMs = Date.now() - start;
      await sleep(1200);
      entry.memoirePageVideMo = memory();
      entry.calcul = await run(session.id, 'await page.waitForFunction(() => window.runBench); return await page.evaluate(() => window.runBench());');
      entry.chargements = [];
      for (let i = 0; i < 4; i++) {
        start = Date.now();
        await request('POST', `/sessions/${session.id}/goto`, { url: `http://127.0.0.1:${SITE}/lourde?${i}`, waitUntil: 'load', timeout: 60000 });
        entry.chargements.push(Date.now() - start);
      }
      entry.memoireApresMo = memory();
      await request('DELETE', `/sessions/${session.id}`);
      LOG(engine.name, 'démarrage', entry.demarrageMs, 'ms');
    } catch (error) {
      entry.erreur = String(error.message ?? error).slice(0, 400);
      LOG('ERREUR', engine.name, entry.erreur);
    }
    results.push(entry);
    writeFileSync(OUT, JSON.stringify(results, null, 1));
  }
}
if (!INSTALLED) {
  adb('shell', 'am', 'force-stop', PKG);
  adb('forward', '--remove', `tcp:${API}`);
}
adb('reverse', '--remove', `tcp:${SITE}`);
server.close();
LOG('terminé');
process.exit(0);
