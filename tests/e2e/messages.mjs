#!/usr/bin/env node
// Parcours de la messagerie sur un vrai téléphone : de faux SMS sont déposés dans la vraie pile téléphonique
// (aucun envoi réel, pas besoin de SIM) et doivent ressortir par le flux de l'API.
//
// Conditions : PocketWright est la messagerie par défaut et « Donner à l'API l'accès aux messages » est activé.
// Le script refuse de tourner sinon : les faux SMS iraient dans la messagerie du téléphone.
// Il ne lit que les messages de ses deux expéditeurs fictifs, et ne supprime qu'eux.

import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { setTimeout as sleep } from 'node:timers/promises';

const SERIAL = process.env.PW_SERIAL ?? process.env.ANDROID_SERIAL;
if (!SERIAL) throw new Error('ANDROID_SERIAL manquant (adb devices)');
const ADB = process.env.ADB ?? `${process.env.HOME}/Android/Sdk/platform-tools/adb`;
const KEY = process.env.PW_KEY ?? readFileSync(new URL('./out/key.txt', import.meta.url), 'utf8').trim();
const PACKAGE = 'com.afroni.pocketwright';
// Numéro de ISms.injectSmsPduForSubscriber : dépend de la version d'Android (7 sur le TECNO, Android 13).
const INJECT = Number(process.env.PW_ISMS_INJECT ?? 7);
const BASE = 'http://127.0.0.1:8765';
const NUMBER = '+22670000001';
const ALPHA = 'PWTEST';
const RUN = Date.now().toString(36);
const skipCrash = process.argv.includes('--no-crash');
const keepMessages = process.argv.includes('--keep');

let passed = 0;
const failures = [];
function check(name, ok, detail = '') {
  if (ok) {
    passed++;
    console.log(`  ok    ${name}${detail ? ` (${detail})` : ''}`);
  } else {
    failures.push(name);
    console.log(`  ÉCHEC ${name}${detail ? ` : ${detail}` : ''}`);
  }
}
const section = (title) => console.log(`\n${title}`);

/** Une liaison USB instable ne doit pas passer pour un bug de l'app : on attend le téléphone et on réessaie. */
function adb(...args) {
  for (let attempt = 1; ; attempt++) {
    try {
      return execFileSync(ADB, ['-s', SERIAL, ...args], { encoding: 'utf8', timeout: 30_000, stdio: ['ignore', 'pipe', 'pipe'] }).trim();
    } catch (error) {
      const text = `${error.stderr ?? ''}${error.message ?? ''}`;
      if (attempt >= 4 || !/not found|offline|no devices|closed|protocol fault/i.test(text)) throw error;
      execFileSync(ADB, ['-s', SERIAL, 'wait-for-device'], { timeout: 60_000, stdio: 'ignore' });
    }
  }
}
const auth = { Authorization: `Bearer ${KEY}` };

async function api(path, init = {}) {
  const response = await fetch(BASE + path, { ...init, headers: { ...auth, ...init.headers }, signal: AbortSignal.timeout(8_000) });
  return { status: response.status, body: await response.json().catch(() => null) };
}

async function waitForApi(timeoutMs = 60_000) {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    try {
      adb('forward', 'tcp:8765', 'tcp:8765');
      if ((await api('/messages/status')).status === 200) return true;
    } catch {
      // Pas encore prêt.
    }
    await sleep(500);
  }
  return false;
}

// ---------------------------------------------------------------- faux SMS (GSM 03.40, type DELIVER)

const swapped = (digits) => {
  const text = digits.length % 2 ? `${digits}F` : digits;
  const bytes = [];
  for (let i = 0; i < text.length; i += 2) bytes.push(parseInt(text[i + 1] + text[i], 16));
  return bytes;
};

function pack7(text) {
  const out = [];
  let accumulator = 0;
  let bits = 0;
  for (const char of text) {
    accumulator |= char.charCodeAt(0) << bits;
    bits += 7;
    while (bits >= 8) {
      out.push(accumulator & 0xff);
      accumulator >>= 8;
      bits -= 8;
    }
  }
  if (bits) out.push(accumulator & 0xff);
  return out;
}

const address = (sender) => {
  if (sender.startsWith('+')) {
    const digits = sender.slice(1);
    return [digits.length, 0x91, ...swapped(digits)];
  }
  return [Math.ceil((sender.length * 7) / 4), 0xd0, ...pack7(sender)];
};

function deliverPdu(sender, text, udh = []) {
  const now = new Date();
  const two = (n) => String(n).padStart(2, '0');
  const stamp = two(now.getUTCFullYear() % 100) + two(now.getUTCMonth() + 1) + two(now.getUTCDate()) + two(now.getUTCHours()) + two(now.getUTCMinutes()) + two(now.getUTCSeconds()) + '00';
  const utf16be = [];
  for (let i = 0; i < text.length; i++) {
    const code = text.charCodeAt(i);
    utf16be.push(code >> 8, code & 0xff);
  }
  // 0x19 : UCS-2, classe 1. Android n'accepte en injection que les messages de classe 1.
  return Uint8Array.from([0x00, 0x04 | (udh.length ? 0x40 : 0), ...address(sender), 0x00, 0x19, ...swapped(stamp), udh.length + utf16be.length, ...udh, ...utf16be]);
}

function injectPdu(pdu) {
  const padded = new Uint8Array(Math.ceil(pdu.length / 4) * 4);
  padded.set(pdu);
  const view = new DataView(padded.buffer);
  const args = ['i32', '-1', 'i32', String(pdu.length)];
  for (let i = 0; i < padded.length; i += 4) args.push('i32', String(view.getInt32(i, true)));
  args.push('s16', '3gpp', 'i32', '0');
  const out = adb('shell', 'service', 'call', 'isms', String(INJECT), ...args);
  if (!out.includes('Parcel(00000000')) throw new Error(`Injection refusée : ${out}`);
}

const inject = (sender, text) => injectPdu(deliverPdu(sender, text));

function injectParts(sender, chunks) {
  const reference = Math.floor(Math.random() * 256);
  chunks.forEach((chunk, index) => injectPdu(deliverPdu(sender, chunk, [0x05, 0x00, 0x03, reference, chunks.length, index + 1])));
}

// ---------------------------------------------------------------- clients du flux

class Client {
  events = [];
  closed = false;
  #waiters = new Set();

  push(event) {
    this.events.push(event);
    for (const waiter of this.#waiters) waiter();
  }

  markClosed() {
    this.closed = true;
    for (const waiter of this.#waiters) waiter();
  }

  messages() {
    return this.events.filter((event) => event.type === 'message');
  }

  async until(predicate, timeoutMs = 15_000) {
    const end = Date.now() + timeoutMs;
    for (;;) {
      if (predicate(this)) return true;
      const left = end - Date.now();
      if (left <= 0) return false;
      await new Promise((resolve) => {
        const timer = setTimeout(done, left);
        const waiters = this.#waiters;
        function done() {
          clearTimeout(timer);
          waiters.delete(done);
          resolve();
        }
        waiters.add(done);
      });
    }
  }
}

function openWebSocket(since) {
  const client = new Client();
  const url = `ws://127.0.0.1:8765/messages/stream?key=${encodeURIComponent(KEY)}${since === undefined ? '' : `&since=${since}`}`;
  const socket = new WebSocket(url);
  socket.onmessage = (event) => client.push(JSON.parse(event.data));
  socket.onclose = () => client.markClosed();
  socket.onerror = () => {};
  client.close = () => socket.close();
  return client;
}

function openSse(since, headers = {}) {
  const client = new Client();
  const controller = new AbortController();
  client.close = () => controller.abort();
  (async () => {
    const response = await fetch(`${BASE}/messages/stream${since === undefined ? '' : `?since=${since}`}`, { headers: { ...auth, ...headers }, signal: controller.signal });
    client.status = response.status;
    client.contentType = response.headers.get('content-type');
    const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
    let buffer = '';
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += value;
      for (let end = buffer.indexOf('\n\n'); end >= 0; end = buffer.indexOf('\n\n')) {
        const block = buffer.slice(0, end);
        buffer = buffer.slice(end + 2);
        const lines = block.split('\n');
        const data = lines.find((line) => line.startsWith('data: '));
        if (!data) continue;
        const event = JSON.parse(data.slice(6));
        const id = lines.find((line) => line.startsWith('id: '));
        if (id) event.sseId = id.slice(4);
        client.push(event);
      }
    }
  })()
    .catch(() => {})
    .finally(() => client.markClosed());
  return client;
}

function openCommandSocket() {
  const client = new Client();
  const socket = new WebSocket(`ws://127.0.0.1:8765/messages/stream?key=${encodeURIComponent(KEY)}`);
  const waiting = new Map();
  let nextId = 1;
  socket.onmessage = (event) => {
    const data = JSON.parse(event.data);
    if (data.type === 'result') waiting.get(data.id)?.(data);
    else client.push(data);
  };
  socket.onclose = () => client.markClosed();
  socket.onerror = () => {};
  return {
    client,
    ready: ready(client),
    close: () => socket.close(),
    call(action, params = {}, timeoutMs = 10_000) {
      const id = nextId++;
      return new Promise((resolve) => {
        const timer = setTimeout(() => resolve({ ok: false, status: 0, error: 'pas de réponse' }), timeoutMs);
        waiting.set(id, (data) => {
          clearTimeout(timer);
          resolve(data);
        });
        socket.send(JSON.stringify({ id, action, params }));
      });
    },
  };
}

const ready = (client) => client.until((c) => c.events.some((event) => event.type === 'ready'));
const hasBody = (client, body) => client.messages().some((event) => event.body === body);
const received = (client, body, timeoutMs) => client.until(() => hasBody(client, body), timeoutMs);
const find = (client, body) => client.messages().find((event) => event.body === body);

// ---------------------------------------------------------------- écran du téléphone

const unescapeXml = (value) => value.replace(/&apos;/g, "'").replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');

function screen() {
  for (let attempt = 0; attempt < 5; attempt++) {
    // Pendant une transition d'écran, uiautomator échoue : on réessaie.
    const out = (() => {
      try {
        return adb('shell', 'uiautomator dump /sdcard/pw-ui.xml >/dev/null && cat /sdcard/pw-ui.xml && rm -f /sdcard/pw-ui.xml');
      } catch {
        return '';
      }
    })();
    if (!out.includes('<node ')) {
      execFileSync('sleep', ['0.5']);
      continue;
    }
    return [...out.matchAll(/<node [^>]*>/g)].map(([tag]) => {
      const attribute = (name) => unescapeXml(tag.match(new RegExp(` ${name}="([^"]*)"`))?.[1] ?? '');
      const [left, top, right, bottom] = attribute('bounds').match(/\d+/g).map(Number);
      return { text: attribute('text'), desc: attribute('content-desc'), x: (left + right) >> 1, y: (top + bottom) >> 1 };
    });
  }
  throw new Error('Écran illisible');
}

const exact = (label) => (node) => node.text === label || node.desc === label;
const containing = (label) => (node) => node.text.includes(label) || node.desc.includes(label);

async function waitFor(match, timeoutMs = 10_000) {
  const end = Date.now() + timeoutMs;
  do {
    const node = screen().find(match);
    if (node) return node;
    await sleep(300);
  } while (Date.now() < end);
  return null;
}

async function tap(match) {
  const node = await waitFor(match);
  if (node) adb('shell', 'input', 'tap', String(node.x), String(node.y));
  return Boolean(node);
}

const openMessages = () => adb('shell', 'am', 'start', '-n', `${PACKAGE}/.ui.MessagesActivity`);

/** Android réserve la suppression à la messagerie : elle passe par l'écran de l'app. */
async function deleteConversation(sender) {
  openMessages();
  if (!(await tap(exact(sender)))) return false;
  if (!(await tap(exact('Supprimer la conversation')))) return false;
  if (!(await tap(exact('Supprimer')))) return false;
  await sleep(1_000);
  return true;
}

function storedBodies(sender) {
  const out = adb('shell', 'content', 'query', '--uri', 'content://sms/inbox', '--projection', 'body:read:seen', '--where', `"address='${sender}'"`);
  return out.split('\n').filter((line) => line.startsWith('Row:'));
}

// ---------------------------------------------------------------- parcours

async function main() {
  section('Conditions');
  adb('shell', 'input', 'keyevent', 'KEYCODE_HOME');
  const holder = adb('shell', 'cmd', 'role', 'get-role-holders', 'android.app.role.SMS');
  if (holder !== PACKAGE) {
    console.log(`Arrêt : la messagerie par défaut est « ${holder} », les faux SMS iraient chez elle.`);
    process.exit(2);
  }
  if (!(await waitForApi())) throw new Error('API injoignable');
  const status = (await api('/messages/status')).body.result;
  if (!status.streaming) {
    console.log('Arrêt : activez « Donner à l\'API l\'accès aux messages » dans Paramètres.');
    process.exit(2);
  }
  check('PocketWright est la messagerie', status.defaultApp);

  section('Sécurité');
  const noKey = await fetch(`${BASE}/messages/stream`, { signal: AbortSignal.timeout(8_000) });
  check('flux refusé sans clé', noKey.status === 401, `HTTP ${noKey.status}`);
  const wrongKey = await fetch(`${BASE}/messages/stream`, { headers: { Authorization: 'Bearer mauvaise-cle' }, signal: AbortSignal.timeout(8_000) });
  check('flux refusé avec une fausse clé', wrongKey.status === 401, `HTTP ${wrongKey.status}`);
  const fromPage = await fetch(`${BASE}/messages/stream`, { headers: { ...auth, Origin: 'https://site.example' }, signal: AbortSignal.timeout(8_000) });
  check('flux refusé depuis une page web', fromPage.status === 401, `HTTP ${fromPage.status}`);
  const socketNoKey = new WebSocket('ws://127.0.0.1:8765/messages/stream');
  const closeCode = await new Promise((resolve) => {
    socketNoKey.onclose = (event) => resolve(event.code);
    socketNoKey.onerror = () => {};
  });
  check('WebSocket fermé sans clé', closeCode === 1008, `code ${closeCode}`);

  section('Réception en direct');
  const sse = openSse();
  const ws = openWebSocket();
  check('SSE prêt', await ready(sse));
  check('WebSocket prêt', await ready(ws));
  check('SSE au bon format', sse.status === 200 && (sse.contentType ?? '').startsWith('text/event-stream'), sse.contentType);

  const simple = `PW ${RUN} simple`;
  const sentAt = Date.now();
  inject(NUMBER, simple);
  check('SMS simple reçu en SSE', await received(sse, simple));
  const latency = Date.now() - sentAt;
  check('SMS simple reçu en WebSocket', await received(ws, simple));
  const event = find(ws, simple);
  check('expéditeur exact', event?.from === NUMBER, event?.from);
  check('champs du message', event?.kind === 'sms' && event.id > 0 && event.threadId > 0 && event.seq > 0 && event.date > 0 && event.dateSent > 0);
  check('même numéro seq sur les deux flux', find(sse, simple)?.seq === event?.seq);
  const firstSeq = event?.seq ?? 1;
  check('SSE donne le numéro en id', find(sse, simple)?.sseId === String(event?.seq));
  console.log(`  info  du dépôt dans la pile téléphonique à l'arrivée au client : ${latency} ms`);

  const accents = `PW ${RUN} Été à Ouaga, ça marche ✅ 😀`;
  inject(NUMBER, accents);
  check('accents et émojis intacts', await received(ws, accents));

  const alpha = `PW ${RUN} expéditeur en lettres`;
  inject(ALPHA, alpha);
  check('expéditeur alphanumérique reçu', await received(ws, alpha));
  check('expéditeur alphanumérique exact', find(ws, alpha)?.from === ALPHA, find(ws, alpha)?.from);

  const chunks = [`PW ${RUN} long début `.padEnd(60, 'a'), 'milieu '.padEnd(60, 'b'), 'fin'.padEnd(30, 'c')];
  const long = chunks.join('');
  injectParts(NUMBER, chunks);
  check('SMS en trois parties reconstitué', await received(ws, long));
  check('un seul événement pour les trois parties', ws.messages().filter((m) => m.body.startsWith(`PW ${RUN} long`)).length === 1);

  section('Rafale');
  const burst = Array.from({ length: 15 }, (_, i) => `PW ${RUN} rafale ${i}`);
  const before = ws.messages().length;
  for (const body of burst) inject(NUMBER, body);
  check('rafale de 15 entièrement reçue', await ws.until((c) => c.messages().length >= before + burst.length, 40_000), `${ws.messages().length - before}/15`);
  await sse.until((c) => burst.every((body) => hasBody(c, body)), 10_000);
  const orderWs = ws.messages().slice(before).map((m) => m.body);
  check('rafale dans l\'ordre (WebSocket)', JSON.stringify(orderWs) === JSON.stringify(burst));
  const seqs = ws.messages().map((m) => m.seq);
  check('seq strictement croissants', seqs.every((seq, i) => i === 0 || seq > seqs[i - 1]));
  check('aucun doublon', new Set(seqs).size === seqs.length);
  check('rafale complète aussi en SSE', burst.every((body) => hasBody(sse, body)));

  section('Reprise après coupure');
  const lastSeq = ws.messages().at(-1).seq;
  ws.close();
  sse.close();
  await ws.until((c) => c.closed, 5_000);
  const missed = [`PW ${RUN} manqué 1`, `PW ${RUN} manqué 2`, `PW ${RUN} manqué 3`];
  for (const body of missed) inject(NUMBER, body);
  await sleep(2_500);
  const resumed = openWebSocket(lastSeq);
  check('rejoue tout ce qui a été manqué', await resumed.until(() => missed.every((body) => hasBody(resumed, body))));
  check('rejeu dans l\'ordre et sans rien de plus', JSON.stringify(resumed.messages().map((m) => m.body)) === JSON.stringify(missed));
  const live = `PW ${RUN} après reprise`;
  inject(NUMBER, live);
  check('le direct continue après le rejeu', await received(resumed, live));
  const resumedSse = openSse(undefined, { 'Last-Event-ID': String(lastSeq) });
  check('reprise SSE par Last-Event-ID', await resumedSse.until(() => [...missed, live].every((body) => hasBody(resumedSse, body))));
  resumedSse.close();
  const lastBeforeRestart = resumed.messages().at(-1).seq;
  const fresh = openWebSocket();
  await ready(fresh);
  await sleep(1_000);
  check('sans since : pas d\'ancien message', fresh.messages().length === 0, `${fresh.messages().length}`);
  fresh.close();

  section('Stockage Android');
  const rows = storedBodies(NUMBER);
  check('messages écrits dans le stockage SMS', rows.length >= 3 + burst.length, `${rows.length} lignes`);
  check('le texte y est complet', rows.some((row) => row.includes('rafale 14')));
  check('marqués non lus', rows.filter((row) => row.includes(RUN)).every((row) => row.includes('read=0')));
  const notifications = adb('shell', 'dumpsys', 'notification', '--noredact');
  check('notification affichée', notifications.includes(`pkg=${PACKAGE}`) && notifications.includes('channel=messages'));

  resumed.close();

  section('Redémarrage de l\'app');
  const restart = await api('/updates/restart', { method: 'POST' });
  check('redémarrage demandé', restart.status === 200);
  await sleep(2_000);
  check('API de retour', await waitForApi(), '');
  const afterRestart = (await api('/messages/status')).body.result;
  check('numérotation conservée', afterRestart.lastSeq >= lastBeforeRestart, `lastSeq ${afterRestart.lastSeq}`);
  const replay = openWebSocket(lastBeforeRestart - 1);
  check('le journal survit au redémarrage', await received(replay, live));
  const afterRestartBody = `PW ${RUN} après redémarrage`;
  inject(NUMBER, afterRestartBody);
  check('réception après redémarrage', await received(replay, afterRestartBody));
  const seqBeforeCrash = find(replay, afterRestartBody)?.seq;
  replay.close();

  if (!skipCrash) {
    section('Processus tué');
    // `pidof` sort en erreur quand le processus n'existe plus : ici, c'est le but.
    const pid = () => {
      try {
        return adb('shell', 'pidof', PACKAGE);
      } catch {
        return '';
      }
    };
    const oldPid = pid();
    adb('shell', 'am', 'crash', PACKAGE);
    for (let i = 0; i < 40 && pid().includes(oldPid); i++) await sleep(250);
    const whileDead = `PW ${RUN} pendant le plantage`;
    inject(NUMBER, whileDead);
    check('API relancée par l\'arrivée du SMS', await waitForApi(90_000));
    const recovery = openWebSocket(seqBeforeCrash);
    check('SMS arrivé pendant le plantage non perdu', await received(recovery, whileDead, 30_000));
    recovery.close();
  }

  section('CRUD par le socket');
  const socket = openCommandSocket();
  await socket.ready;
  const firstThread = (await socket.call('conversations')).result.find((c) => c.addresses.includes(NUMBER));
  check('Read : conversations', Boolean(firstThread?.id) && typeof firstThread.snippet === 'string' && typeof firstThread.date === 'number', `${firstThread?.title}`);
  const page = await socket.call('messages', { threadId: firstThread.id, limit: 5 });
  check('Read : messages avec limite', page.ok && page.result.length === 5, `${page.result?.length}`);
  check('Read : messages du plus ancien au plus récent', page.result.every((m, i) => i === 0 || m.date >= page.result[i - 1].date));
  const older = await socket.call('messages', { threadId: firstThread.id, limit: 5, before: page.result[0].date });
  check('Read : remonter dans l\'historique', older.ok && older.result.length > 0 && older.result.every((m) => m.date < page.result[0].date));
  const target = page.result.at(-1);
  const single = await socket.call('message', { id: target.id });
  check('Read : un message', single.ok && single.result.id === target.id && single.result.body === target.body && single.result.box === 'incoming');
  const missing = await socket.call('message', { id: 999999999 });
  check('Read : message absent', !missing.ok && missing.status === 404, `${missing.status}`);
  const noPart = await socket.call('attachment', { partId: 999999999 });
  check('Read : pièce jointe absente', !noPart.ok && noPart.status === 404, `${noPart.status}`);

  const unread = await socket.call('update', { id: target.id, read: false });
  check('Update : non lu', unread.ok && unread.result.read === false);
  const readAgain = await socket.call('update', { id: target.id, read: true });
  check('Update : lu', readAgain.ok && readAgain.result.read === true);
  const edited = await socket.call('update', { id: target.id, body: `PW ${RUN} modifié é` });
  check('Update : texte d\'un SMS', edited.ok && edited.result.body === `PW ${RUN} modifié é`);
  const threadRead = await socket.call('update', { threadId: firstThread.id, read: true });
  check('Update : conversation lue', threadRead.ok && (await socket.call('conversations')).result.find((c) => c.id === firstThread.id).unread === false);
  const threadUnread = await socket.call('update', { threadId: firstThread.id, read: false });
  check('Update : conversation non lue', threadUnread.ok && (await socket.call('conversations')).result.find((c) => c.id === firstThread.id).unread === true);
  const nothing = await socket.call('update', { id: target.id });
  check('Update : rien à modifier', !nothing.ok && nothing.status === 400, `${nothing.status}`);

  const removed = await socket.call('delete', { id: target.id });
  check('Delete : un message', removed.ok && removed.result.deleted === 1);
  check('Delete : il n\'existe plus', (await socket.call('message', { id: target.id })).status === 404);
  check('Delete : déjà supprimé', (await socket.call('delete', { id: target.id })).status === 404);

  const invalid = await socket.call('inconnue');
  check('action inconnue refusée', !invalid.ok && invalid.status === 404, `${invalid.status}`);
  check('send refusé sans texte', (await socket.call('send', { to: NUMBER })).status === 400);
  check('send refusé sans destinataire', (await socket.call('send', { text: 'x' })).status === 400);
  check('send refusé si trop long', (await socket.call('send', { to: NUMBER, text: 'x'.repeat(1601) })).status === 400);
  // Avec une SIM, ce test enverrait un vrai SMS payant : il ne tourne que sans.
  if (adb('shell', 'getprop', 'gsm.sim.state').split(',').every((state) => state === 'ABSENT' || state === '')) {
    const sent = await socket.call('send', { to: NUMBER, text: `PW ${RUN} créé par le socket` }, 70_000);
    const created = sent.result?.messages?.[0];
    check('Create : envoi enregistré, échec rendu sans SIM', sent.ok && created?.box === 'failed' && created.body === `PW ${RUN} créé par le socket`, created?.box);
    check('Create : présent dans le fil', (await socket.call('message', { id: created.id })).ok);
    const live2 = `PW ${RUN} direct pendant les commandes`;
    inject(NUMBER, live2);
    check('le flux continue sur la même connexion', await socket.client.until(() => hasBody(socket.client, live2)));
  } else {
    console.log('  info  SIM présente : envoi non testé, pour ne rien facturer');
  }
  const conversationIds = (await socket.call('conversations')).result.filter((c) => c.addresses.includes(ALPHA)).map((c) => c.id);
  check('Delete : conversation', conversationIds.length === 1 && (await socket.call('deleteConversation', { threadId: conversationIds[0] })).result?.deleted >= 1);
  check('Delete : conversation disparue', !(await socket.call('conversations')).result.some((c) => c.addresses.includes(ALPHA)));
  socket.close();

  section('Écrans');
  openMessages();
  check('la conversation est dans la liste', await tap(exact(NUMBER)));
  check('le fil affiche les messages', Boolean(await waitFor(containing(afterRestartBody))));
  await sleep(1_200);
  check('ouvrir la conversation la marque comme lue', storedBodies(NUMBER).every((row) => row.includes('read=1')));
  adb('shell', 'am', 'start', '-a', 'android.intent.action.SENDTO', '-d', `smsto:${NUMBER}`, '--es', 'sms_body', 'texteprerempli');
  check('un lien sms: ouvre un nouveau message', Boolean(await waitFor(exact('Nouveau message'))));
  const compose = screen();
  check('destinataire et texte préremplis', compose.some(exact(NUMBER)) && compose.some(exact('texteprerempli')));
  const rowsBeforeLink = storedBodies(NUMBER).length;
  await sleep(1_000);
  check('rien n\'est envoyé sans l\'utilisateur', storedBodies(NUMBER).length === rowsBeforeLink);
  adb('shell', 'input', 'keyevent', 'KEYCODE_BACK');
  await sleep(600);

  // Avec une SIM, ce test enverrait un vrai SMS payant : il ne tourne que sans.
  const noSim = adb('shell', 'getprop', 'gsm.sim.state').split(',').every((state) => state === 'ABSENT' || state === '');
  if (noSim) {
    openMessages();
    await tap(exact(NUMBER));
    const barBefore = (await waitFor(exact('Envoyer'))).y;
    await tap(exact('Message'));
    // Le clavier est lent à s'ouvrir sur ce téléphone : avant lui, les touches tapées se perdent.
    await waitFor((node) => exact('Envoyer')(node) && node.y < barBefore - 100, 15_000);
    await sleep(800);
    adb('shell', 'input', 'text', `reponse${RUN}`);
    await waitFor(exact(`reponse${RUN}`));
    // La barre de suggestions du clavier apparaît après la saisie et déplace le bouton.
    await sleep(1_200);
    await tap(exact('Envoyer'));
    let lastSeen = '';
    const failedReply = async () => {
      const end = Date.now() + 25_000;
      do {
        const labeled = screen().filter((node) => node.text || node.desc);
        const index = labeled.findIndex(containing(`reponse${RUN}`));
        lastSeen = labeled.slice(Math.max(index, 0), index + 3).map((node) => node.text || node.desc).join(' | ');
        if (index >= 0 && labeled[index + 1]?.text.includes('Échec de')) return true;
        await sleep(400);
      } while (Date.now() < end);
      return false;
    };
    const replyFailed = await failedReply();
    check('sans SIM, la réponse affiche son échec', replyFailed, lastSeen);
    if (!replyFailed) writeFileSync(new URL('./out/ui/echec-reponse.png', import.meta.url), execFileSync(ADB, ['-s', SERIAL, 'exec-out', 'screencap', '-p'], { maxBuffer: 1 << 26 }));
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK');
    await sleep(400);
  } else {
    console.log('  info  SIM présente : envoi non testé, pour ne rien facturer');
  }

  section('Suppression');
  check('conversation numérique supprimée', await deleteConversation(NUMBER));
  check('plus rien dans le stockage', storedBodies(NUMBER).length === 0 && storedBodies(ALPHA).length === 0);
  // Le stockage vide, Android repart de l'identifiant 1 : déjà présent dans le journal pour un autre message.
  const reused = `PW ${RUN} identifiant réutilisé`;
  const afterDelete = openWebSocket();
  await ready(afterDelete);
  inject(NUMBER, reused);
  check('un nouveau message reste diffusé après suppression', await received(afterDelete, reused));
  afterDelete.close();
  const replayAll = openWebSocket(firstSeq - 1);
  await replayAll.until(() => hasBody(replayAll, reused));
  await sleep(1_500);
  const remaining = replayAll.messages().filter((m) => m.body.includes(RUN));
  check('un message supprimé n\'est pas rediffusé', remaining.length === 1 && remaining[0].body === reused, `${remaining.length} rediffusés`);
  replayAll.close();
  check('dernière conversation de test supprimée', await deleteConversation(NUMBER));
}

try {
  await main();
} catch (error) {
  failures.push(`Arrêt inattendu : ${error.message}`);
  console.error(error);
} finally {
  if (keepMessages) {
    console.log('\nMessages de test conservés (--keep).');
  } else {
    try {
      for (const sender of [NUMBER, ALPHA]) if (storedBodies(sender).length) await deleteConversation(sender);
      const left = storedBodies(NUMBER).length + storedBodies(ALPHA).length;
      check('aucun message de test ne reste', left === 0, `${left} restants`);
    } catch (error) {
      failures.push(`Nettoyage : ${error.message}`);
    }
  }
}

console.log(`\n${passed} réussis, ${failures.length} en échec`);
for (const failure of failures) console.log(`  - ${failure}`);
process.exit(failures.length ? 1 : 0);
