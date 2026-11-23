// One overlay layer for bottom sheets and the full-screen voice view.
const el = () => document.getElementById('overlay');
let onClose = null;
let lastFocus = null;

export function openOverlay(html, opts = {}) {
  const o = el();
  lastFocus = document.activeElement;
  onClose = opts.onClose || null;
  o.className = '';
  o.innerHTML = (opts.scrim === false ? '' : '<div class="scrim" data-act="close-overlay"></div>') + html;
  o.hidden = false;
  document.getElementById('app').inert = true;
  const first = o.querySelector('[autofocus], input, button:not(.scrim)');
  if (first) setTimeout(() => first.focus({ preventScroll: true }), 60);
}

export function closeOverlay() {
  const o = el();
  if (o.hidden) return;
  const done = onClose;
  onClose = null;
  o.classList.add('closing');
  setTimeout(() => {
    o.hidden = true;
    o.innerHTML = '';
    o.className = '';
    document.getElementById('app').inert = false;
    if (lastFocus && document.contains(lastFocus)) lastFocus.focus({ preventScroll: true });
  }, 160);
  if (done) done();
}

export const overlayOpen = () => !el().hidden;

// Small yes/no sheet. Used before removing things in the app itself.
export function confirmSheet({ title, text, yes, danger = true }, onYes) {
  openOverlay(`<div class="sheet" role="dialog" aria-modal="true" aria-label="${title}"><div class="grab"></div>
    <h2 class="h2">${title}</h2><p class="muted mt-sm">${text}</p>
    <div class="stack mt"><button class="btn ${danger ? 'danger' : 'primary'} block" id="sheet-yes">${yes}</button><button class="btn block" data-act="close-overlay">Cancel</button></div></div>`);
  document.getElementById('sheet-yes').addEventListener('click', () => { closeOverlay(); onYes(); });
}
