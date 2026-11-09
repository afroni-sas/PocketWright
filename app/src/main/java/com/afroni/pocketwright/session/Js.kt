package com.afroni.pocketwright.session

import kotlinx.serialization.json.JsonPrimitive

/** Le JavaScript injecté dans les pages pour exécuter les commandes. */
internal object Js {

    const val STARTED = "\"__pw_started\""
    const val NO_BRIDGE = "\"__pw_nobridge\""

    /**
     * Les sélecteurs, comme dans Playwright : CSS par défaut, `xpath=…` (ou `//…`), et `text=…` qui prend
     * l'élément le plus profond contenant ce texte (`text="…"` pour un texte exact).
     */
    private const val HELPERS = """
const __pwFind = (sel) => {
  if (typeof sel !== 'string' || !sel) throw new Error('Sélecteur vide');
  if (sel.startsWith('xpath=') || sel.startsWith('//') || sel.startsWith('(//')) {
    const xp = sel.startsWith('xpath=') ? sel.slice(6) : sel;
    return document.evaluate(xp, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
  }
  if (sel.startsWith('text=')) {
    let t = sel.slice(5).trim(), exact = false;
    if (t.length > 1 && (t[0] === '"' || t[0] === "'") && t[t.length - 1] === t[0]) { t = t.slice(1, -1); exact = true; }
    const norm = (s) => (s || '').replace(/\s+/g, ' ').trim();
    const want = exact ? norm(t) : norm(t).toLowerCase();
    const hit = (n) => {
      const s = norm(n.innerText !== undefined ? n.innerText : n.textContent);
      return exact ? s === want : s.toLowerCase().includes(want);
    };
    const root = document.body || document.documentElement;
    if (!root) return null;
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT);
    for (let n = walker.currentNode; n; n = walker.nextNode()) {
      if (/^(SCRIPT|STYLE|NOSCRIPT|TEMPLATE|HEAD)${'$'}/.test(n.tagName)) continue;
      if (hit(n) && !Array.from(n.children).some(hit)) return n;
    }
    return null;
  }
  return document.querySelector(sel);
};
const __pwGet = (sel) => {
  const el = __pwFind(sel);
  if (!el) throw new Error('Élément introuvable : ' + sel);
  return el;
};
const __pwVisible = (el) => {
  if (!el || !el.isConnected) return false;
  const st = getComputedStyle(el);
  if (st.visibility === 'hidden' || st.display === 'none') return false;
  const r = el.getBoundingClientRect();
  return r.width > 0 && r.height > 0;
};
"""

    /**
     * Enveloppe [body] (le corps d'une fonction async) pour qu'il rende son résultat par le pont, promesses
     * comprises. Le script lui-même rend [STARTED] tout de suite : un autre résultat veut dire qu'il n'a pas
     * pu être lu (erreur de syntaxe) ou que la page n'a pas encore le pont.
     */
    fun wrap(bridge: String, callId: String, body: String): String = """
(function () {
  if (!window.$bridge) return '__pw_nobridge';
  const __id = '$callId';
  const __done = (ok, v) => {
    let payload;
    try { payload = JSON.stringify({ ok: ok, v: v === undefined ? null : v }); }
    catch (e) { payload = JSON.stringify({ ok: false, v: 'Résultat non sérialisable en JSON : ' + e }); }
    window.$bridge.done(__id, payload);
  };
  $HELPERS
  try {
    Promise.resolve((async () => {
$body
    })()).then((v) => __done(true, v), (e) => __done(false, (e && e.message) ? e.message : String(e)));
  } catch (e) { __done(false, (e && e.message) ? e.message : String(e)); }
  return '__pw_started';
})();
"""

    /** Une chaîne Kotlin écrite comme littéral JavaScript. */
    fun str(value: String): String = JsonPrimitive(value).toString()

    /**
     * Donne à chaque canvas une copie de lui-même en image de fond ; `window[holder]` remet tout en place, et un délai
     * le fait de toute façon si l'app ne revient jamais. La copie est prise dans une image d'animation : en dehors,
     * le tampon d'un canvas WebGL est déjà effacé. Rend `true` si un canvas a été touché.
     */
    fun paintCanvases(holder: String): String = """
const canvases = [...document.querySelectorAll('canvas')].filter((c) => c.width && c.height && c.getClientRects().length).slice(0, $MAX_CANVAS_COPIES);
if (!canvases.length) return false;
const frame = () => new Promise((done) => { requestAnimationFrame(done); setTimeout(done, 700); });
await frame();
const touched = [];
for (const canvas of canvases) {
  let copy;
  try { copy = canvas.toDataURL(); } catch { continue; }
  touched.push([canvas, canvas.getAttribute('style')]);
  const style = canvas.style;
  style.setProperty('background-image', 'url("' + copy + '")', 'important');
  style.setProperty('background-size', '100% 100%', 'important');
  style.setProperty('background-repeat', 'no-repeat', 'important');
  style.setProperty('background-origin', 'content-box', 'important');
  style.setProperty('background-clip', 'content-box', 'important');
}
if (!touched.length) return false;
const key = ${str(holder)};
const restore = () => {
  if (!(key in window)) return;
  delete window[key];
  for (const [canvas, style] of touched) {
    if (style === null) canvas.removeAttribute('style'); else canvas.setAttribute('style', style);
  }
};
Object.defineProperty(window, key, { value: restore, configurable: true });
setTimeout(restore, 15000);
await frame();
await frame();
return true;
"""

    fun restoreCanvases(holder: String): String = "window[${str(holder)}]?.(); return true;"

    private const val MAX_CANVAS_COPIES = 16

    private val FUNCTION = Regex("""^\s*(async\s+)?(function\b|\([^)]*\)\s*=>|[A-Za-z_$][\w$]*\s*=>)""")

    /** Le corps qui évalue [expression] : une expression, ou une fonction appelée avec [argJson]. */
    fun evaluation(expression: String, argJson: String?): String =
        if (FUNCTION.containsMatchIn(expression)) "return await ($expression)(${argJson ?: "undefined"});"
        else "return await ($expression);"
}
