// Le petit site du collecteur : la page, une iframe, un worker, un service worker, et l'écho des en-têtes reçus.
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';

const collector = readFileSync(new URL('./collector.js', import.meta.url), 'utf8');

const PAGE = `<!doctype html><meta charset="utf-8"><title>collecteur</title><body>
<script src="/collector.js"></script>
<iframe id="frame" src="/frame.html" width="80" height="40"></iframe>
<script>
async function fromWorker() {
  const worker = new Worker('/worker.js');
  const answer = await new Promise((resolve, reject) => {
    worker.onmessage = (event) => resolve(event.data);
    worker.onerror = (event) => reject(new Error(event.message));
    worker.postMessage('go');
  });
  worker.terminate();
  return answer;
}
async function fromServiceWorker() {
  const registration = await navigator.serviceWorker.register('/sw.js');
  const active = registration.active ?? (await navigator.serviceWorker.ready).active;
  const channel = new MessageChannel();
  const answer = new Promise((resolve) => { channel.port1.onmessage = (event) => resolve(event.data); });
  active.postMessage('go', [channel.port2]);
  return answer;
}
window.collectAll = async () => {
  const frame = document.getElementById('frame');
  if (!frame.contentWindow.collectCore) await new Promise((resolve) => { frame.onload = resolve; });
  const safely = async (fn) => { try { return await Promise.race([fn(), new Promise((resolve) => setTimeout(() => resolve('délai dépassé'), 12000))]); } catch (error) { return 'erreur:' + error.message; } };
  return {
    main: await collectPage(),
    iframe: await safely(() => frame.contentWindow.collectCore()),
    worker: await safely(fromWorker),
    serviceWorker: await safely(fromServiceWorker),
  };
};
</script>`;

export function startSite(port) {
  const server = createServer((request, response) => {
    const send = (type, body) => {
      response.writeHead(200, { 'content-type': type, 'cache-control': 'no-store', 'accept-ch': 'Sec-CH-UA-Full-Version-List, Sec-CH-UA-Model, Sec-CH-UA-Platform-Version, Sec-CH-UA-Arch, Sec-CH-UA-Bitness, Sec-CH-Device-Memory, Device-Memory, Sec-CH-DPR, DPR, Sec-CH-Viewport-Width' });
      response.end(body);
    };
    const path = request.url.split('?')[0];
    if (path === '/echo') {
      const pairs = [];
      for (let i = 0; i < request.rawHeaders.length; i += 2) pairs.push([request.rawHeaders[i], request.rawHeaders[i + 1]]);
      send('application/json', JSON.stringify({ headers: Object.fromEntries(pairs.filter(([name]) => !/^(host|referer|connection|cache-control|pragma|sec-fetch-|if-)/i.test(name))) }));
    } else if (path === '/collector.js') send('text/javascript', collector);
    else if (path === '/worker.js' || path === '/sw.js') send('text/javascript', "importScripts('/collector.js');");
    else if (path === '/frame.html') send('text/html', '<!doctype html><meta charset="utf-8"><script src="/collector.js"></script>');
    else send('text/html', PAGE);
  });
  return new Promise((resolve) => server.listen(port, '127.0.0.1', () => resolve(server)));
}
