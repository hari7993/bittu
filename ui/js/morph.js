// Updates the live page to match new markup without rebuilding it.
// Elements stay in place, so CSS transitions run when an option changes (switch slides, tile fills, pill moves).
const key = (n) => (n.nodeType === 1 ? n.getAttribute('data-key') : null);

function replay(el, cls) {
  el.classList.remove(cls);
  void el.offsetWidth; // restart the animation
  el.classList.add(cls);
}

function morphNode(a, b) {
  if (a.nodeType !== b.nodeType || a.nodeName !== b.nodeName) {
    if (b.nodeType === 1) b.classList.add('morph-in');
    a.replaceWith(b);
    return;
  }
  if (a.nodeType === 3) {
    if (a.data !== b.data) {
      a.data = b.data;
      const host = a.parentElement && a.parentElement.closest('[data-swap]');
      if (host) replay(host, 'swap');
    }
    return;
  }
  if (a.nodeType !== 1) return;
  for (const { name } of [...a.attributes]) if (!b.hasAttribute(name) && name !== 'class') a.removeAttribute(name);
  for (const { name, value } of [...b.attributes]) {
    if (name === 'class') { const keepSwap = a.classList.contains('swap'); if (a.className !== value) a.className = value; if (keepSwap) a.classList.add('swap'); }
    else if (a.getAttribute(name) !== value) a.setAttribute(name, value);
  }
  if (!b.hasAttribute('class') && a.hasAttribute('class')) a.removeAttribute('class');
  if (a.tagName === 'TEXTAREA' || a.tagName === 'INPUT') return; // what the person typed stays
  morphChildren(a, b);
}

export function morphChildren(a, b) {
  const olds = [...a.childNodes];
  const news = [...b.childNodes];
  if (olds.length === news.length && olds.every((n, i) => key(n) === key(news[i]))) {
    olds.forEach((n, i) => morphNode(n, news[i]));
    return;
  }
  const byKey = new Map();
  olds.forEach((n) => { const k = key(n); if (k) byKey.set(k, n); });
  if (!byKey.size) {
    news.forEach((n) => { if (n.nodeType === 1) n.classList.add('morph-in'); });
    a.replaceChildren(...news);
    return;
  }
  const newKeys = new Set(news.map(key).filter(Boolean));
  const keep = new Set();
  let cur = a.firstChild;
  const skipDoomed = () => { while (cur && !(key(cur) && newKeys.has(key(cur)))) cur = cur.nextSibling; };
  for (const nn of news) {
    skipDoomed();
    const k = key(nn);
    const on = k ? byKey.get(k) : null;
    if (on) {
      if (on === cur) cur = cur.nextSibling; else a.insertBefore(on, cur);
      morphNode(on, nn);
      keep.add(on);
    } else {
      if (nn.nodeType === 1) nn.classList.add('morph-in');
      a.insertBefore(nn, cur);
      keep.add(nn);
    }
  }
  olds.forEach((n) => { if (!keep.has(n)) n.remove(); });
}
