import http from 'node:http';
// Cas avancés exécutés sur des sessions dédiées et un site synthétique local.
export async function advanced({ step, check, api, cmd, headers, BASE, SITE, KEY, chromium, sleep, preexisting }) {
  const owned = new Set();
  const create = async (options = {}) => {
    const r = await api('POST', '/sessions', { name: 'e2e-advanced', ...options });
    if (!r.ok) throw new Error(JSON.stringify(r));
    owned.add(r.result.id);
    return r.result.id;
  };
  const drop = async (id) => { await api('DELETE', `/sessions/${id}`); owned.delete(id); };
  const raw = (path, options = {}) => fetch(BASE + path, { signal: AbortSignal.timeout(15000), ...options });
  const fixture = `<!doctype html><html><head><meta name="viewport" content="width=device-width"><title>Laboratoire</title></head><body>
    <label for="text">Texte</label><input id="text"><textarea id="area"></textarea>
    <input id="check" type="checkbox"><select id="select"><option value="a">Alpha</option><option value="b">Bêta</option></select>
    <button id="button" onclick="document.querySelector('#output').textContent='clic'">Action</button>
    <button id="disabled" disabled>Interdit</button><div id="output" data-test="valeur">initial</div>
    <ul><li>un</li><li>deux</li><li>trois</li></ul><div id="hidden" style="display:none">caché</div>
    <div style="height:2000px"></div><footer>fin</footer></body></html>`;
  let id;
  try {
    await step('11. Contrats HTTP et authentification exhaustive', async () => {
      for (const path of ['/', '/sessions', '/sessions/inexistante', '/cdp/new/json/version']) {
        check(`${path} sans authentification`, (await raw(path)).status === 401);
      }
      for (const auth of [{ 'X-API-Key': KEY }, { authorization: `Bearer ${KEY}` }]) {
        check(`authentification ${Object.keys(auth)[0]}`, (await raw('/', { headers: auth })).status === 200);
      }
      check('authentification par query', (await raw(`/?key=${encodeURIComponent(KEY)}`)).status === 200);
      for (const host of ['evil.example:8765', '127.0.0.1.evil.example:8765']) {
        const status = await new Promise((resolve, reject) => {
          const req = http.get(BASE + '/', { headers: { ...headers(), host } }, res => { res.resume(); resolve(res.statusCode); });
          req.on('error', reject); req.setTimeout(10000, () => req.destroy(new Error('HTTP timeout')));
        });
        check(`Host refusé : ${host}`, status === 401, status);
      }
      for (const body of ['{', '[]', 'null', '42', '"texte"']) {
        const r = await raw('/sessions', { method: 'POST', headers: headers(), body });
        check(`corps invalide ${body}`, r.status === 400, await r.text());
      }
      for (const [name, extra] of [['Origin étranger', { Origin: 'https://evil.example' }], ['Sec-Fetch-Site étranger', { 'Sec-Fetch-Site': 'cross-site' }]]) {
        check(`${name} refusé`, (await raw('/sessions', { headers: { ...headers(), ...extra } })).status === 401);
      }
      check('session inconnue GET', (await api('GET', '/sessions/inexistante')).status === 404);
      check('session inconnue DELETE', (await api('DELETE', '/sessions/inexistante')).status === 404);
      id = await create();
      check('une action qui agit refuse GET', (await raw(`/sessions/${id}/goto?url=https://example.com`, { headers: headers() })).status === 405);
      check('une lecture passe en GET', (await raw(`/sessions/${id}/url`, { headers: headers() })).status === 200);
      for (const [action, params, status] of [
        ['inconnue', {}, 404], ['rename', {}, 400], ['dialogs', { mode: 'inconnu' }, 400],
        ['title', { engine: 'inconnu' }, 400], ['goto', {}, 400],
        ['run', { script: 'const =' }, 400],
      ]) {
        const r = await api('POST', `/sessions/${id}/${action}`, params);
        check(`erreur ${action} ${JSON.stringify(params)}`, r.status === status && r.ok === false, `${r.status}: ${r.error}`);
      }
      check('serveur toujours utilisable après erreurs', (await api('GET', '/')).ok);
    });

    await step('12. Formulaires, Unicode et sélecteurs avancés', async () => {
      id ||= await create();
      await cmd(id, 'setContent', { html: fixture });
      const value = 'Été à Dakar 🧪 العربية 中文 <script> & " guillemets';
      await cmd(id, 'fill', { selector: '#text', value });
      check('Unicode et caractères spéciaux conservés', await cmd(id, 'inputValue', { selector: '#text' }) === value);
      await cmd(id, 'clear', { selector: '#text' });
      check('clear vide le champ', await cmd(id, 'inputValue', { selector: '#text' }) === '');
      await cmd(id, 'type', { selector: '#text', text: 'abc' });
      await cmd(id, 'press', { selector: '#text', key: 'End' });
      await cmd(id, 'keyboard', { action: 'insertText', text: 'é' });
      check('type + press + keyboard', await cmd(id, 'inputValue', { selector: '#text' }) === 'abcé');
      await cmd(id, 'fill', { selector: '#area', value: 'ligne 1\nligne 2\n🧪' });
      check('texte multiligne', await cmd(id, 'inputValue', { selector: '#area' }) === 'ligne 1\nligne 2\n🧪');
      await cmd(id, 'check', { selector: '#check' });
      check('case cochée', await cmd(id, 'isChecked', { selector: '#check' }));
      await cmd(id, 'uncheck', { selector: '#check' });
      check('case décochée', !(await cmd(id, 'isChecked', { selector: '#check' })));
      await cmd(id, 'selectOption', { selector: '#select', value: 'b' });
      check('sélection option', await cmd(id, 'inputValue', { selector: '#select' }) === 'b');
      await cmd(id, 'click', { selector: 'xpath=//button[@id="button"]' });
      check('sélecteur XPath et clic', await cmd(id, 'text', { selector: '#output' }) === 'clic');
      check('attribut DOM', await cmd(id, 'attribute', { selector: '#output', name: 'data-test' }) === 'valeur');
      check('compte des éléments', await cmd(id, 'count', { selector: 'li' }) === 3);
      check('limite queryAll', (await cmd(id, 'queryAll', { selector: 'li', limit: 2 })).length === 2);
      check('élément caché invisible', !(await cmd(id, 'isVisible', { selector: '#hidden' })));
      check('bouton désactivé', !(await cmd(id, 'isEnabled', { selector: '#disabled' })));
      check('arbre accessible', (await cmd(id, 'ariaSnapshot')).includes('Action'));
      const box = await cmd(id, 'boundingBox', { selector: '#button' });
      check('dimensions élément', box.width > 0 && box.height > 0);
    });

    await step('13. Attentes, timeouts et reprise après erreurs', async () => {
      await cmd(id, 'setContent', { html: fixture });
      await cmd(id, 'evaluate', { expression: `() => { setTimeout(() => { document.querySelector('#hidden').style.display = 'block'; }, 350); }` });
      await cmd(id, 'waitForSelector', { selector: '#hidden', state: 'visible', timeout: 5000 });
      check('attente d’apparition différée', await cmd(id, 'isVisible', { selector: '#hidden' }));
      await cmd(id, 'evaluate', { expression: `() => { setTimeout(() => document.querySelector('#hidden').remove(), 350); }` });
      check('attente de suppression', await cmd(id, 'waitForSelector', { selector: '#hidden', state: 'detached', timeout: 5000 }));
      const start = Date.now();
      const missing = await api('POST', `/sessions/${id}/click`, { selector: '#absent', timeout: 700 });
      check('timeout explicite', missing.status === 408, missing.error);
      check('timeout borné', Date.now() - start < 6000, `${Date.now() - start} ms`);
      const script = await api('POST', `/sessions/${id}/run`, { script: 'throw new Error("erreur-volontaire")' });
      check('exception script remontée', !script.ok && script.error.includes('erreur-volontaire'));
      check('reprise après timeout et exception', await cmd(id, 'title') === 'Laboratoire');
      await cmd(id, 'addScriptTag', { content: 'window.injected = 42' });
      check('injection de script', await cmd(id, 'evaluate', { expression: '() => window.injected' }) === 42);
      await cmd(id, 'addStyleTag', { content: '#output { color: rgb(1, 2, 3); }' });
      check('injection CSS', await cmd(id, 'evaluate', { expression: `() => getComputedStyle(document.querySelector('#output')).color` }) === 'rgb(1, 2, 3)');
    });

    await step('14. Historique, cookies, stockage et isolation complète', async () => {
      await cmd(id, 'goto', { url: SITE + '/login' });
      await cmd(id, 'goto', { url: SITE + '/todo' });
      await cmd(id, 'back');
      check('historique arrière', (await cmd(id, 'url')).endsWith('/login'));
      await cmd(id, 'forward');
      check('historique avant', (await cmd(id, 'url')).endsWith('/todo'));
      const added = await api('POST', `/sessions/${id}/addCookies`, { cookies: [{ name: 'e2e_cookie', value: 'alice', url: SITE }] });
      check('addCookies par Playwright', added.ok, added.error || '');
      // Le stockage navigateur reste vérifiable même si l’adaptateur CDP cookies échoue.
      await cmd(id, 'evaluate', { expression: `() => { document.cookie = 'e2e_cookie=alice; path=/'; }` });
      await cmd(id, 'evaluate', { expression: `() => { localStorage.setItem('e2e', 'alice'); sessionStorage.setItem('e2e', 'tab'); }` });
      await cmd(id, 'reload');
      check('sessionStorage conservé après reload', await cmd(id, 'evaluate', { expression: `() => sessionStorage.getItem('e2e')` }) === 'tab');
      const exported = await api('POST', `/sessions/${id}/storageState`, {});
      check('export storageState', exported.ok, exported.error || '');
      if (exported.ok) {
        check('export cookies', exported.result.cookies.some(c => c.name === 'e2e_cookie' && c.value === 'alice'));
        check('export localStorage', exported.result.origins.some(o => o.localStorage.some(v => v.name === 'e2e' && v.value === 'alice')));
      }
      const other = await create({ url: SITE + '/todo' });
      await cmd(other, 'waitForLoad');
      const isolated = (await api('GET', '/')).result.isolation;
      check('isolation disponible sur ce device', isolated);
      const listedCookies = await api('POST', `/sessions/${id}/cookies`, {});
      check('lecture cookies Playwright', listedCookies.ok, listedCookies.error || '');
      check('cookies isolés entre profils', !(await cmd(other, 'evaluate', { expression: '() => document.cookie' })).includes('e2e_cookie'));
      check('localStorage isolé', await cmd(other, 'evaluate', { expression: `() => localStorage.getItem('e2e')` }) === null);
      check('sessionStorage isolé', await cmd(other, 'evaluate', { expression: `() => sessionStorage.getItem('e2e')` }) === null);
      await cmd(other, 'evaluate', { expression: `() => { document.cookie = 'e2e_cookie=bob; path=/'; }` });
      const cleared = await api('POST', `/sessions/${other}/clearCookies`, {});
      check('clearCookies Playwright', cleared.ok, cleared.error || '');
      if (cleared.ok) check('cookies effectivement supprimés', !(await cmd(other, 'evaluate', { expression: '() => document.cookie' })).includes('e2e_cookie'));
      check('clearCookies ne touche pas l’autre profil', (await cmd(id, 'evaluate', { expression: '() => document.cookie' })).includes('e2e_cookie=alice'));
      await drop(other);
      const renamed = await cmd(id, 'rename', { name: 'Session renommée 🧪 العربية' });
      check('renommage Unicode', renamed.name === 'Session renommée 🧪 العربية');
    });

    await step('15. CDP : réseau interrompu, reprise, dialogues et métadonnées', async () => {
      const b = await chromium.connectOverCDP(`${BASE}/cdp/${id}`, { headers: headers(), isWebView: true, timeout: 15000 });
      try {
        const context = b.contexts()[0], page = context.pages()[0];
        page.setDefaultTimeout(7000);
        await page.goto(SITE + '/');
        await context.setOffline(true);
        const offline = await page.goto(SITE + '/login').then(() => false, () => true);
        check('navigation hors ligne échoue', offline);
        await context.setOffline(false);
        await page.goto(SITE + '/login');
        check('navigation reprend après retour réseau', await page.title() === 'Connexion');
        await page.setContent(fixture);
        const dialogs = [], dialogErrors = [];
        const handler = async d => {
          dialogs.push(d.type());
          try { if (d.type() === 'prompt') await d.accept('réponse 🧪'); else await d.dismiss(); }
          catch (e) { dialogErrors.push(e.message); }
        };
        page.on('dialog', handler);
        const answer = await page.evaluate(() => ({ confirm: confirm('Refuser ?'), prompt: prompt('Texte ?') }));
        check('confirm refusé avec REST et CDP attachés', answer.confirm === false, JSON.stringify(answer));
        check('prompt personnalisé avec REST et CDP attachés', answer.prompt === 'réponse 🧪', JSON.stringify(answer));
        check('événements dialogue', dialogs.join(',') === 'confirm,prompt');
        await page.waitForTimeout(200);
        check('aucun conflit de traitement des dialogues', dialogErrors.length === 0, dialogErrors.join(' | '));
        page.off('dialog', handler);
        const errors = [];
        page.on('pageerror', e => errors.push(e.message));
        await page.evaluate(() => setTimeout(() => { throw new Error('erreur-page-e2e'); }, 0));
        await page.waitForTimeout(200);
        check('erreurs JavaScript observables', errors.some(e => e.includes('erreur-page-e2e')));
      } finally { await b.close(); }
      check('déconnexion CDP conserve session', (await api('GET', `/sessions/${id}`)).ok);
      const soloId = await create({ name: 'dialogues-cdp-seul' });
      const solo = await chromium.connectOverCDP(`${BASE}/cdp/${soloId}`, { headers: headers(), isWebView: true, timeout: 15000 });
      try {
        const page = solo.contexts()[0].pages()[0];
        const errors = [];
        page.on('dialog', async d => {
          try { if (d.type() === 'prompt') await d.accept('solo'); else await d.dismiss(); }
          catch (e) { errors.push(e.message); }
        });
        const result = await page.evaluate(() => ({ confirm: confirm('Non ?'), prompt: prompt('Texte ?') }));
        check('confirm refusé avec CDP seul', result.confirm === false, JSON.stringify(result));
        check('prompt personnalisé avec CDP seul', result.prompt === 'solo', JSON.stringify(result));
        await page.waitForTimeout(200);
        check('CDP seul sans conflit de dialogue', errors.length === 0, errors.join(' | '));
      } finally { await solo.close(); await drop(soloId); }

    });

    await step('16. WebSocket : refus, messages malformés et reconnexion', async () => {
      const connect = (url) => {
        const ws = new WebSocket(url);
        const queue = [], waiters = [];
        ws.addEventListener('message', e => { const m = JSON.parse(e.data); if (waiters.length) waiters.shift()(m); else queue.push(m); });
        const closed = new Promise(r => ws.addEventListener('close', r));
        const next = () => new Promise((resolve, reject) => {
          if (queue.length) return resolve(queue.shift());
          const timer = setTimeout(() => reject(new Error('réponse WS absente')), 10000);
          waiters.push(v => { clearTimeout(timer); resolve(v); });
        });
        return { ws, next, closed };
      };
      const root = BASE.replace('http:', 'ws:');
      for (const path of ['/ws?key=faux', '/cdp?key=faux']) {
        const c = connect(root + path);
        const e = await Promise.race([c.closed, sleep(12000).then(() => ({ code: -1 }))]);
        c.ws.close();
        check(`clé erronée ${path.split('?')[0]}`, e.code === 1008, e.code);
      }
      const c = connect(`${root}/ws?key=${encodeURIComponent(KEY)}&session=${id}`);
      try {
        check('reprise session existante', (await c.next()).session.id === id);
        c.ws.send('{');
        let r = await c.next();
        check('JSON WS malformé', !r.ok && r.error.includes('JSON'));
        c.ws.send(JSON.stringify({ id: 70 }));
        r = await c.next();
        check('action WS manquante, id conservé', !r.ok && r.id === 70);
        for (let i = 0; i < 20; i++) c.ws.send(JSON.stringify({ id: i, action: 'title' }));
        const seen = new Set();
        while (seen.size < 20) { r = await c.next(); if (r.type === 'result') { if (!r.ok) throw new Error(r.error); seen.add(r.id); } }
        check('20 commandes pipelinées : réponses corrélées', seen.size === 20);
      } finally { c.ws.close(); }
      await c.closed;
      check('déconnexion WS conserve session', (await api('GET', `/sessions/${id}`)).ok);
    });

    await step('17. Charge bornée : sessions concurrentes et cycles création/fermeture', async () => {
      const peers = await Promise.all(Array.from({ length: 6 }, (_, i) => create({ name: `charge-${i}` })));
      try {
        const started = Date.now();
        const work = await Promise.all(peers.map(async (sid, i) => {
          await cmd(sid, 'goto', { url: SITE + '/slow?ms=600' });
          await cmd(sid, 'run', { script: `state.marker=${i}; return state.marker;` });
          const r = await cmd(sid, 'run', { script: 'return state.marker;' });
          return r.result === i;
        }));
        check('6 sessions exécutées sans mélange de state', work.every(Boolean), `${Date.now() - started} ms`);
        const responses = await Promise.all(Array.from({ length: 60 }, (_, i) => cmd(peers[i % peers.length], 'title')));
        check('60 lectures concurrentes cohérentes', responses.every(v => v === 'Lente'));
      } finally { for (const sid of peers) await drop(sid); }
      for (let cycle = 0; cycle < 3; cycle++) {
        const sid = await create();
        await cmd(sid, 'setContent', { html: `<h1>cycle-${cycle}</h1>` });
        check(`cycle ${cycle + 1} opérationnel`, await cmd(sid, 'text', { selector: 'h1' }) === `cycle-${cycle}`);
        await drop(sid);
        check(`cycle ${cycle + 1} supprimé`, (await api('GET', `/sessions/${sid}`)).status === 404);
      }
      check('moteur prêt après charge', (await api('GET', '/')).result.playwright.status === 'ready');
    });

    await step('18. Moteur natif explicite et captures JSON/JPEG', async () => {
      const sid = await create();
      try {
        await cmd(sid, 'setContent', { html: fixture, engine: 'native' });
        await cmd(sid, 'fill', { selector: '#text', value: 'Natif 🧪', engine: 'native' });
        check('saisie Unicode native', await cmd(sid, 'evaluate', { expression: "document.querySelector('#text').value", engine: 'native' }) === 'Natif 🧪');
        check('texte natif', await cmd(sid, 'text', { selector: '#output', engine: 'native' }) === 'initial');
        check('queryAll natif', (await cmd(sid, 'queryAll', { selector: 'li', engine: 'native' })).length === 3);
        const shot = await api('GET', `/sessions/${sid}/screenshot?format=base64&engine=native`);
        const png = Buffer.from(shot.result.base64, 'base64');
        check('capture native base64 PNG valide', png.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10])));
        const jpeg = await raw(`/sessions/${sid}/screenshot?type=jpeg&quality=65`, { headers: headers() });
        const bytes = Buffer.from(await jpeg.arrayBuffer());
        check('capture JPEG valide', jpeg.headers.get('content-type') === 'image/jpeg' && bytes[0] === 255 && bytes[1] === 216);
      } finally { await drop(sid); }
    });
    await step('18 bis. Playwright complet : pages, popups, contextes, permissions', async () => {
      const sid = await create({ name: 'pw-complet', url: SITE + '/' });
      const b = await chromium.connectOverCDP(`${BASE.replace('http', 'ws')}/cdp/${sid}?key=${encodeURIComponent(KEY)}`, { isWebView: true, timeout: 20000 });
      try {
        const context = b.contexts()[0], page = context.pages()[0];
        page.setDefaultTimeout(12000);
        await page.goto(SITE + '/');
        await page.evaluate(() => { document.cookie = 'groupe=partage; path=/'; });

        const second = await context.newPage();
        second.setDefaultTimeout(12000);
        check('context.newPage() ouvre une page', context.pages().length === 2, context.pages().length);
        await second.goto(SITE + '/');
        check('la nouvelle page partage les cookies du contexte', (await second.evaluate(() => document.cookie)).includes('groupe=partage'));

        const [popup] = await Promise.all([
          page.waitForEvent('popup', { timeout: 12000 }),
          page.evaluate((url) => { window.open(url); }, SITE + '/login'),
        ]);
        await popup.waitForLoadState();
        check('popup (window.open) vue par Playwright', popup.url().endsWith('/login'), popup.url());
        check('la popup connaît sa page d\'origine', (await popup.opener()) === page);
        await popup.close();
        await second.close();
        check('fermeture des pages supplémentaires', context.pages().length === 1, context.pages().length);

        await page.goto(SITE + '/todo');
        await page.evaluate(() => localStorage.setItem('origine', 'visitee'));
        await page.goto('about:blank');
        const state = await context.storageState();
        check('storageState relit une origine qui n\'est plus ouverte', JSON.stringify(state.origins).includes('visitee'), JSON.stringify(state.origins).slice(0, 120));
        check('context.cookies() par Playwright', (await context.cookies()).some((c) => c.name === 'groupe'));

        const isolated = await b.newContext();
        const inside = await isolated.newPage();
        inside.setDefaultTimeout(12000);
        check('browser.newContext() puis newPage()', isolated.pages().length === 1);
        await inside.goto(SITE + '/');
        check('le nouveau contexte est isolé', !(await inside.evaluate(() => document.cookie)).includes('groupe=partage'), await inside.evaluate(() => document.cookie));
        await isolated.addCookies([{ name: 'isole', value: 'oui', url: SITE }]);
        check('addCookies dans le nouveau contexte', (await inside.evaluate(() => document.cookie)).includes('isole=oui'));
        check('le contexte d\'origine ne voit pas ce cookie', !(await context.cookies()).some((c) => c.name === 'isole'));
        const count = (await api('GET', '/sessions')).result.length;
        await isolated.close();
        await sleep(600);
        check('context.close() ferme ses onglets', (await api('GET', '/sessions')).result.length === count - 1);

        await context.grantPermissions(['geolocation'], { origin: SITE });
        await context.setGeolocation({ latitude: 14.69, longitude: -17.44 });
        check('grantPermissions et setGeolocation sans erreur', true);
      } finally {
        await b.close().catch(() => {});
        await drop(sid);
      }
    });

    await step('18 ter. Dialogues sans client CDP extérieur (mode réglé par REST)', async () => {
      const sid = await create({ name: 'dialogues-rest' });
      try {
        await cmd(sid, 'setContent', { html: '<title>d</title>' });
        await cmd(sid, 'dialogs', { mode: 'dismiss' });
        check('mode dismiss : confirm rend false', await cmd(sid, 'evaluate', { expression: "() => confirm('?')" }) === false);
        await cmd(sid, 'dialogs', { mode: 'accept', promptText: 'réglé' });
        check('mode accept : prompt rend le texte réglé', await cmd(sid, 'evaluate', { expression: "() => prompt('?')" }) === 'réglé');
      } finally { await drop(sid); }
    });
  } finally {
    for (const sid of owned) await drop(sid).catch(() => {});
  }
  await step('19. Nettoyage et santé finale', async () => {
    const sessions = (await api('GET', '/sessions')).result.filter((s) => !preexisting.has(s.id));
    check('aucune session de test restante', sessions.length === 0, sessions.map((s) => s.name).join(', '));
    check('API et moteur prêts', (await api('GET', '/')).result.playwright.status === 'ready');
  });
}
