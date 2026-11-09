// Le site de test : une petite boutique avec connexion, liste de tâches, tableau de bord, envoi de fichier,
// iframe, page lente et page longue. Servi par le PC ; le téléphone le joint par `adb reverse`.
import http from 'node:http';
import { randomBytes } from 'node:crypto';

const PORT = Number(process.env.SITE_PORT || 18090);
const accounts = new Map(); // sid → utilisateur
export const stats = { apiCalls: 0, logins: [] };

const page = (title, body, script = '') => `<!doctype html>
<html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title}</title>
<style>body{font-family:sans-serif;margin:16px;line-height:1.4} li{margin:6px 0} button{margin-left:8px}</style>
</head><body>${body}<script>${script}</script></body></html>`;

const cookies = (req) => Object.fromEntries((req.headers.cookie || '').split(';').map((c) => c.trim().split('=')).filter((p) => p[0]));
const user = (req) => accounts.get(cookies(req).sid);
const body = (req) => new Promise((resolve) => {
  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', () => resolve(Buffer.concat(chunks)));
});
const send = (res, status, html, headers = {}) => {
  res.writeHead(status, { 'content-type': 'text/html; charset=utf-8', ...headers });
  res.end(html);
};

const routes = {
  'GET /': (req, res) => send(res, 200, page('Boutique de test', `
    <h1>PocketWright Test Shop</h1>
    <nav><a href="/login">Connexion</a> · <a href="/todo">Tâches</a> · <a href="/dashboard">Tableau de bord</a>
    · <a href="/upload">Envoi</a> · <a href="/frames">Cadres</a> · <a href="/long">Page longue</a></nav>`)),

  'GET /login': (req, res) => {
    const error = new URL(req.url, 'http://x').searchParams.has('error');
    send(res, 200, page('Connexion', `
      <h1>Connexion</h1>
      ${error ? '<p role="alert" id="error">Identifiants incorrects</p>' : ''}
      <form method="post" action="/login">
        <label>Utilisateur <input id="user" name="user" autocomplete="off"></label><br>
        <label>Mot de passe <input id="pass" name="pass" type="password"></label><br>
        <button type="submit">Se connecter</button>
      </form>`));
  },

  'POST /login': async (req, res) => {
    const form = new URLSearchParams((await body(req)).toString());
    if (form.get('pass') !== 'secret' || !form.get('user')) return send(res, 302, '', { location: '/login?error=1' });
    const sid = randomBytes(12).toString('hex');
    accounts.set(sid, form.get('user'));
    stats.logins.push(form.get('user'));
    // Un cookie durable (Max-Age) : la session doit le retrouver après un redémarrage de l'app.
    send(res, 302, '', { location: '/account', 'set-cookie': `sid=${sid}; HttpOnly; Path=/; Max-Age=86400; SameSite=Lax` });
  },

  'GET /account': (req, res) => {
    const name = user(req);
    if (!name) return send(res, 302, '', { location: '/login' });
    send(res, 200, page(`Compte de ${name}`, `<h1>Bonjour ${name}</h1><p id="since">Connecté</p>`));
  },

  // Une liste de tâches : rendu différé (attente automatique), confirm() à la suppression, localStorage.
  'GET /todo': (req, res) => send(res, 200, page('Tâches', `
    <h1>Mes tâches</h1>
    <input placeholder="Nouvelle tâche" id="new"><button id="add">Ajouter</button>
    <ul id="list"></ul><p id="count">…</p>`, `
    const load = () => JSON.parse(localStorage.getItem('todos') || '[]');
    const save = (t) => localStorage.setItem('todos', JSON.stringify(t));
    const render = () => {
      const todos = load();
      const list = document.getElementById('list');
      list.innerHTML = '';
      todos.forEach((t, i) => {
        const li = document.createElement('li');
        li.textContent = t;
        const del = document.createElement('button');
        del.textContent = 'Supprimer';
        del.setAttribute('aria-label', 'Supprimer ' + t);
        del.onclick = () => { if (confirm('Supprimer « ' + t + ' » ?')) { todos.splice(i, 1); save(todos); setTimeout(render, 300); } };
        li.appendChild(del);
        list.appendChild(li);
      });
      document.getElementById('count').textContent = todos.length + ' tâche(s)';
    };
    document.getElementById('add').onclick = () => {
      const input = document.getElementById('new');
      if (!input.value) return;
      const todos = load(); todos.push(input.value); save(todos); input.value = '';
      setTimeout(render, 600);
    };
    setTimeout(render, 600);`)),

  'GET /dashboard': (req, res) => send(res, 200, page('Tableau de bord', `
    <h1>Tableau de bord</h1><p>Visites : <span id="visits">…</span> · Ventes : <span id="sales">…</span></p>`, `
    fetch('/api/stats').then((r) => r.json()).then((s) => {
      document.getElementById('visits').textContent = s.visits;
      document.getElementById('sales').textContent = s.sales;
      console.log('stats chargées', JSON.stringify(s));
    });`)),

  'GET /api/stats': (req, res) => {
    stats.apiCalls++;
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ visits: 1234, sales: 56 }));
  },

  'GET /upload': (req, res) => send(res, 200, page('Envoi', `
    <h1>Envoyer un fichier</h1>
    <form method="post" action="/upload" enctype="multipart/form-data">
      <input type="file" name="doc" id="doc"><button type="submit">Envoyer</button>
    </form>`)),

  'POST /upload': async (req, res) => {
    const raw = await body(req);
    const boundary = '--' + (req.headers['content-type'] || '').split('boundary=')[1];
    const part = raw.toString('latin1').split(boundary).find((p) => p.includes('filename='));
    const name = part?.match(/filename="([^"]*)"/)?.[1] ?? '?';
    const content = part ? part.slice(part.indexOf('\r\n\r\n') + 4, part.lastIndexOf('\r\n')) : '';
    send(res, 200, page('Reçu', `<p id="result">Reçu : ${name} (${Buffer.byteLength(content, 'latin1')} octets)</p>`));
  },

  'GET /frames': (req, res) => send(res, 200, page('Cadres', `
    <h1>Page avec un cadre</h1><iframe name="inner" src="/frame-inner" width="300" height="120"></iframe>`)),
  'GET /frame-inner': (req, res) => send(res, 200, page('Intérieur', `
    <button onclick="this.textContent='Cliqué dans le cadre'">Cliquer ici</button>`)),

  'GET /slow': async (req, res) => {
    const ms = Number(new URL(req.url, 'http://x').searchParams.get('ms') || 1500);
    await new Promise((r) => setTimeout(r, ms));
    send(res, 200, page('Lente', `<h1>Arrivée après ${ms} ms</h1>`));
  },

  'GET /long': (req, res) => send(res, 200, page('Page longue', `
    <h1>Une page longue</h1>
    ${Array.from({ length: 120 }, (_, i) => `<p>Paragraphe ${i + 1} — ${'texte '.repeat(12)}</p>`).join('')}
    <footer id="end" style="background:#1e2a44;color:#fff;padding:24px">Fin de la page</footer>`)),
};

export function startSite() {
  const server = http.createServer(async (req, res) => {
    const route = routes[`${req.method} ${new URL(req.url, 'http://x').pathname}`];
    if (!route) return send(res, 404, page('404', '<h1>Introuvable</h1>'));
    try {
      await route(req, res);
    } catch (e) {
      send(res, 500, page('500', `<pre>${e.stack}</pre>`));
    }
  });
  return new Promise((resolve, reject) => { server.once('error', reject); server.listen(PORT, '127.0.0.1', () => resolve(server)); });
}
