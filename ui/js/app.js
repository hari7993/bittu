// Router, rendering and every button action. No framework: one click listener, one render call.
import { icon } from './icons.js';
import { state, get, set, save, reset, esc, toast, person, MODES, PROVIDERS, syncModes } from './store.js';
import { ROUTES, onboarding, OB_STEPS, VOICES } from './pages.js';
import { openVoice, voiceActions } from './voice.js';
import { callPage, stopCall, callActions } from './call.js';
import { openOverlay, closeOverlay, overlayOpen, confirmSheet } from './overlay.js';
import { morphChildren } from './morph.js';
import { isApp, initNative, applySwitch, applyLevel, sync, helper, saveKey, contactSheet, pairStart, helperStart } from './native.js';

const app = document.getElementById('app');
const TABS = [['', 'home', 'Home'], ['control', 'zap', 'Phone'], ['activity', 'list', 'Activity'], ['people', 'users', 'People']];
const stack = [];
let replaceNext = false; // the next screen change swaps the current history entry instead of adding one
// Go up one screen: step back in history when we came from inside the app, otherwise swap to the parent screen.
function goBack(fallback) { if (stack.length > 1) history.back(); else { replaceNext = true; location.replace(fallback); } }

function applyTheme() {
  const root = document.documentElement;
  // Colours switch in one step: without this every colour transition would run at once and smear.
  root.classList.add('no-anim');
  requestAnimationFrame(() => requestAnimationFrame(() => root.classList.remove('no-anim')));
  if (state.theme === 'system') root.removeAttribute('data-theme'); else root.dataset.theme = state.theme;
  const dark = state.theme === 'dark' || (state.theme === 'system' && matchMedia('(prefers-color-scheme: dark)').matches);
  document.querySelector('meta[name="theme-color"]').content = dark ? '#121210' : '#F6F4EE';
  try { window.BittuBars?.theme(dark); } catch { /* running in a browser, not in the app */ }
}

const route = () => { const [name = '', arg] = location.hash.replace(/^#\/?/, '').split('/'); return { name, arg, key: name + (arg ? '/' + arg : '') }; };

function build(dir) {
  const r = route();
  let p;
  if (!state.onboarded) p = onboarding();
  else if (r.name === 'call') p = callPage(r.arg);
  else p = (ROUTES[r.name] || ROUTES[''])(r.arg);
  const tabs = p.tabs ? `<nav class="tabs" aria-label="Main">${TABS.map(([k, i, l]) => `<a href="#/${k}" ${r.name === k ? 'aria-current="page"' : ''}><span class="pill">${icon(i, 24)}</span>${l}</a>`).join('')}</nav>` : '';
  return { p, html: splitTitles( `<main class="page ${p.flush ? 'flush' : ''} ${dir === 'none' ? '' : 'enter-' + dir}" id="page">${p.html}</main>${p.bottom ? `<div class="bottom-bar">${p.bottom}</div>` : ''}${tabs}`) };
}

// Wrap each word of a page title so the words can rise in one after another.
function splitTitles(html) {
  return html.replace(/(<h1 class="(?:h1|display)[^>]*>)([\s\S]*?)(<\/h1>)/g, (m, open, body, close) => {
    let i = 0;
    return open + body.split(/(<br>)/).map((part) => (part === '<br>' ? part : part.split(' ').map((w) => (w ? `<span class="w" style="--w:${i++}"><span>${w}</span></span>` : '')).join(' '))).join('') + close;
  });
}

function paint(dir) {
  stopCall();
  const { p, html } = build(dir);
  app.innerHTML = html;
  if (p.after) p.after();
}

// Move to a screen. Where the browser supports it, the old screen slides out while the new one slides in.
const VT = typeof document.startViewTransition === 'function';
document.documentElement.classList.toggle('vt', VT);
function render(dir = 'fade', instant = false) {
  if (!VT || instant || matchMedia('(prefers-reduced-motion: reduce)').matches) { paint(dir); return; }
  document.documentElement.dataset.dir = dir;
  const vt = document.startViewTransition(() => paint(dir));
  // A transition is skipped when another starts or the app is in the background; the screen still updates.
  vt.ready.catch(() => {});
  vt.finished.catch(() => {});
}

// Update the current screen in place after a state change. Elements are kept, so their transitions play.
function refresh() {
  if (route().name === 'call' && state.onboarded) return;
  const box = document.createElement('div');
  box.innerHTML = build('none').html;
  morphChildren(app, box);
}

function speak(text, voiceId) {
  try {
    if (!('speechSynthesis' in window)) return;
    speechSynthesis.cancel();
    const u = new SpeechSynthesisUtterance(text);
    const i = VOICES.findIndex((v) => v[0] === voiceId);
    u.pitch = [1.15, 0.8, 1.35, 0.95][i] || 1;
    u.rate = [1, 0.95, 1.1, 0.9][i] || 1;
    speechSynthesis.speak(u);
  } catch { /* no speech engine here: the choice is still saved */ }
}

// "For 1 hour", "Until 7:00 AM", "Until tomorrow morning" as a real moment. 0 means it ends only when switched off.
function endsAt(label) {
  const now = Date.now();
  let m = /^For (\d+) (minute|hour)/i.exec(label);
  if (m) return now + Number(m[1]) * (m[2].toLowerCase() === 'hour' ? 3600000 : 60000);
  m = /^Until (\d{1,2}):(\d{2}) (AM|PM)/i.exec(label);
  if (/tomorrow morning/i.test(label)) m = [0, '7', '00', 'AM'];
  if (!m) return 0;
  const d = new Date(); d.setHours((Number(m[1]) % 12) + (m[3].toUpperCase() === 'PM' ? 12 : 0), Number(m[2]), 0, 0);
  if (d.getTime() <= now) d.setDate(d.getDate() + 1);
  return d.getTime();
}

const actions = {
  ...voiceActions,
  ...callActions,
  toggle: (el) => { if (applySwitch(el.dataset.path, !get(el.dataset.path))) return; set(el.dataset.path, !get(el.dataset.path)); refresh(); },
  set: (el) => { const v = el.dataset.val; set(el.dataset.path, /^\d+$/.test(v) ? Number(v) : v); applyLevel(el.dataset.path, v); applyTheme(); refresh(); },
  mode: (el) => {
    const next = state.mode === el.dataset.val ? 'normal' : el.dataset.val;
    if (next === 'normal' || next === 'off') { set('modeEnds', 0); set('until', ''); set('mode', next); refresh(); toast(next === 'normal' ? 'Back to listening' : 'Bittu has stopped'); return; }
    // Turning a mode on asks how long it should last.
    const t12 = (v) => { const [h, m] = v.split(':').map(Number); return `${h % 12 || 12}:${String(m).padStart(2, '0')} ${h < 12 ? 'AM' : 'PM'}`; };
    const choices = { driving: ['Until I arrive', 'For 1 hour', 'For 3 hours'], meeting: ['Until the meeting ends', 'For 30 minutes', 'For 1 hour'], sleep: ['Until ' + t12(state.sleepTo), 'For 1 hour', 'For 3 hours'], strangers: ['For 1 hour', 'For 3 hours', 'Until tomorrow morning'] }[next];
    openOverlay(`<div class="sheet" role="dialog" aria-modal="true" aria-label="${MODES[next].name} mode"><div class="grab"></div>
      <div class="hstack"><h2 class="h2 grow">${MODES[next].name}</h2><button class="iconbtn" data-act="close-overlay" aria-label="Close">${icon('x')}</button></div>
      <p class="muted mt-sm">${MODES[next].sub}</p>
      <div class="stack mt stagger">${[...(choices || ['For 1 hour', 'For 3 hours']), 'Until I turn it off'].map((c) => `<button class="opt" data-act="mode-for" data-val="${next}" data-until="${c}"><i></i>${c}</button>`).join('')}</div></div>`);
  },
  'memory-forget': (el) => { if (window.BittuNative && window.BittuNative.memoryForget) window.BittuNative.memoryForget(el.dataset.val); toast('Forgotten'); setTimeout(refresh, 700); },
  'memory-clear': () => confirmSheet({ title: 'Forget everything?', text: 'Bittu will forget all it has learned about you and its diary of your days. This can\'t be undone.', yes: 'Forget everything' }, () => { if (window.BittuNative && window.BittuNative.memoryClear) window.BittuNative.memoryClear(); refresh(); toast('Bittu remembers nothing now'); }),
  'keep-port': (el) => { const on = el.dataset.on === '1'; if (window.BittuNative && window.BittuNative.keepPort) window.BittuNative.keepPort(on); closeOverlay(); toast(on ? 'Turning on restart without Wi-Fi. The helper restarts once, give it 20 seconds.' : 'Closing the port. The helper restarts once, give it 20 seconds.'); },
  'models-refresh': (el) => { if (window.BittuNative && window.BittuNative.modelsRefresh) { window.BittuNative.modelsRefresh(el.dataset.val); toast('Getting the latest list…'); } },
  'mode-add': () => {
    const name = (document.getElementById('new-mode-name').value || '').trim();
    const say = (document.getElementById('new-mode-say').value || '').trim();
    if (!name) { toast('Give the mode a name first'); return; }
    if (!say) { toast('Write what Bittu should tell callers'); return; }
    if (Object.values(MODES).some((m) => m.name.toLowerCase() === name.toLowerCase())) { toast('There is already a mode with that name'); return; }
    state.myModes.push({ id: 'my' + Date.now().toString(36), name, say, act: 'answer' });
    syncModes(); save(); refresh(); toast(`${name} mode added`);
  },
  'mode-del': (el) => {
    const i = state.myModes.findIndex((m) => m.id === el.dataset.val); if (i < 0) return;
    const [gone] = state.myModes.splice(i, 1);
    if (state.mode === gone.id) { state.mode = 'normal'; state.until = ''; }
    syncModes(); save(); refresh(); toast(`${gone.name} mode removed`);
  },
  'mode-act': (el) => { const m = state.myModes.find((x) => x.id === el.dataset.val); if (!m) return; m.act = el.dataset.to; syncModes(); save(); refresh(); },
  'mode-for': (el) => { set('modeEnds', endsAt(el.dataset.until)); set('until', el.dataset.until); set('mode', el.dataset.val); if (window.BittuNative && window.BittuNative.modeEnds) window.BittuNative.modeEnds(state.modeEnds); closeOverlay(); refresh(); toast(`${MODES[el.dataset.val].name} mode · ${el.dataset.until.toLowerCase()}`); },
  voice: (el) => openVoice(el.dataset.say),
  'ob-next': () => { state.obStep += 1; if (state.obStep >= OB_STEPS) { state.onboarded = true; state.obStep = 0; save(); stack.length = 0; if (location.hash !== '#/') { location.hash = '#/'; return; } } save(); render('fwd'); },
  'ob-back': () => { state.obStep = Math.max(0, state.obStep - 1); save(); render('back'); },
  'share-person': (el) => { const p = person(el.dataset.id); p.level = p.level >= 2 ? 1 : 3; save(); refresh(); },
  'pick-voice': (el) => { set('voice', el.dataset.val); refresh(); speak('Hello, I am Bittu. I will talk like this.', el.dataset.val); },
  lang: (el) => { const l = el.dataset.val; const has = state.langs.includes(l); if (has && state.langs.length === 1) { toast('Keep at least one language'); return; } state.langs = has ? state.langs.filter((x) => x !== l) : [...state.langs, l]; save(); refresh(); },
  level: (el) => { person(el.dataset.id).level = Number(el.dataset.val); save(); refresh(); },
  through: (el) => { const p = person(el.dataset.id); p.through = !p.through; save(); refresh(); },
  'remove-person': (el) => { const p = person(el.dataset.id); confirmSheet({ title: `Remove ${esc(p.name)}?`, text: 'Bittu will treat their calls like any other caller.', yes: 'Remove' }, () => { state.people = state.people.filter((x) => x.id !== p.id); save(); goBack('#/people'); toast(p.name + ' removed'); }); },
  'remove-note': (el) => confirmSheet({ title: 'Remove this note?', text: 'The summary and what was said will be erased from this phone.', yes: 'Remove' }, () => { state.notes = state.notes.filter((n) => n.id !== el.dataset.id); save(); goBack('#/activity'); toast('Note removed'); }),
  'add-person': () => {
    // On the phone, people come from the real contacts, so Bittu knows their number.
    if (contactSheet((c) => { const id = 'p' + Date.now().toString(36); state.people.push({ id, name: c.name, number: c.number, rel: 'Contact', level: 1, through: false }); save(); location.hash = '#/person/' + id; })) return;
    openOverlay(`<div class="sheet" role="dialog" aria-modal="true" aria-label="Add a person"><div class="grab"></div><h2 class="h2">Add a person</h2>
      <form class="stack mt" id="add-form"><label class="field"><span>Name</span><input class="input" id="add-name" placeholder="Name as saved in contacts" autocomplete="off" required></label>
      <label class="field"><span>Who they are</span><input class="input" id="add-rel" placeholder="Brother, friend, office" autocomplete="off"></label>
      <button class="btn primary block mt-sm">Add</button><button type="button" class="btn block" data-act="close-overlay">Cancel</button></form></div>`);
    document.getElementById('add-form').addEventListener('submit', (e) => {
      e.preventDefault();
      const name = document.getElementById('add-name').value.trim();
      if (!name) return;
      const id = name.toLowerCase().replace(/[^a-z0-9]+/g, '-') + '-' + Date.now().toString(36);
      state.people.push({ id, name, rel: document.getElementById('add-rel').value.trim() || 'Contact', level: 1, through: false });
      save(); closeOverlay(); location.hash = '#/person/' + id;
    });
  },
  stop: () => { const off = state.mode === 'off'; set('mode', off ? 'normal' : 'off'); refresh(); toast(off ? 'Bittu is listening again' : 'Bittu has stopped'); },
  reset: () => confirmSheet({ title: 'Erase everything?', text: 'People, notes, the log and all settings on this phone will be erased. This can\'t be undone.', yes: 'Erase everything' }, () => { reset(); applyTheme(); stack.length = 0; location.hash = '#/'; render('fade'); }),
  'replay-ob': () => { state.onboarded = false; state.obStep = 0; save(); render('fade'); },
  provider: (el) => { const p = PROVIDERS.find((x) => x[0] === el.dataset.val); state.llm = { provider: p[0], model: p[2], url: p[3] }; save(); const y = document.getElementById('page').scrollTop; paint('none'); document.getElementById('page').scrollTop = y; },
  'add-rule': (el) => { const box = document.getElementById('prompt'); const cur = state.prompt.trim(); if (cur.includes(el.dataset.text)) { toast('Already in your instructions'); return; } set('prompt', (cur ? cur + '\n' : '') + el.dataset.text); box.value = state.prompt; box.scrollTop = box.scrollHeight; toast('Added'); },
  'clear-tests': () => confirmSheet({ title: 'Clear test calls?', text: 'Notes from practice calls will be removed. Real calls stay.', yes: 'Clear' }, () => { state.notes = state.notes.filter((n) => !n.test); save(); refresh(); toast('Test calls cleared'); }),
  'pair-start': () => { closeOverlay(); pairStart(); },
  'helper-start': async (el) => { el.disabled = true; toast('Starting the helper…'); const r = await helperStart(); closeOverlay(); toast(r.ok ? 'The helper is running' : r.text); sync(); },
  'native-sync': () => { closeOverlay(); sync().then(() => toast(state.setup.bridge ? 'The helper is running' : 'The helper is not running yet')); },
  testkey: async (el) => { if (!state.setup.bridge) { toast('Start the helper first (Phone access)'); return; } el.disabled = true; toast('Asking Gemini…'); const r = await helper('testkey'); el.disabled = false; toast(r.result === 'ok' ? 'Gemini Live accepted the key and model' : 'Gemini said: ' + String(r.result || r.error).slice(0, 140)); },
  'live-take': () => { helper('takeover'); toast('Over to you'); },
  'live-end': () => { helper('hangup'); },
  copy: async (el) => { try { await navigator.clipboard.writeText(el.dataset.text); toast('Copied'); } catch { toast('Press and hold the text to copy it'); } },
  'send-msg': () => { const to = person(state._msgTo || state.people[0]?.id); const text = document.getElementById('msg-text').value.trim() || 'Reaching in 10 minutes'; if (!to) { toast('Add a person first'); return; } set('_msgText', ''); openVoice(`Send ${state._msgApp || 'WhatsApp'} to ${to.name}: ${text}`); },
};

document.addEventListener('click', (e) => {
  const back = e.target.closest('[data-back]');
  if (back && stack.length > 1) { e.preventDefault(); history.back(); return; }
  const swap = e.target.closest('a[data-replace]');
  if (swap) { e.preventDefault(); replaceNext = true; location.replace(swap.getAttribute('href')); return; }
  const el = e.target.closest('[data-act]');
  if (!el || el.disabled) return;
  const fn = actions[el.dataset.act];
  if (fn) { if (el.tagName === 'A') e.preventDefault(); fn(el, e); }
});

document.addEventListener('input', (e) => {
  const el = e.target;
  if (el.dataset.bind) {
    set(el.dataset.bind, el.type === 'range' ? Number(el.value) : el.value);
    if ('refresh' in el.dataset) refresh();
    if (el.dataset.out) document.getElementById(el.dataset.out).textContent = el.value + '%';
  } else if (el.dataset.bindFlag) {
    if (isApp) return; // on the phone the key is saved when the field is left, never held in the page
    const had = get(el.dataset.bindFlag);
    set(el.dataset.bindFlag, el.value.trim().length > 0 || had); // the key itself is never written to browser storage
    if ('refresh' in el.dataset && !had) refresh();
  } else if (el.id === 'msg-text') {
    state._msgText = el.value;
  }
});

document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && overlayOpen()) closeOverlay();
  if (e.key === 'Enter' && e.target.matches('input.input[data-bind="name"]') && !state.onboarded) actions['ob-next']();
});

window.addEventListener('hashchange', () => {
  if (overlayOpen()) closeOverlay();
  const r = route();
  const isTab = TABS.some(([k]) => k === r.name);
  let dir = 'fwd';
  if (replaceNext) { replaceNext = false; stack[stack.length - 1] = r.key; render('back'); return; }
  if (stack.length > 1 && stack[stack.length - 2] === r.key) {
    stack.pop();
    if (r.name === 'call') { history.back(); return; } // never walk back into a call: it would ring again
    dir = 'back';
  }
  else if (isTab) { dir = stack.length && TABS.some(([k]) => k === stack[stack.length - 1]) ? 'fade' : 'back'; stack.length = 0; stack.push(r.key); }
  else stack.push(r.key);
  render(dir);
});
// The phone's back button (called by the Android app): close a sheet, or step back. false = nothing left to close.
// Started from outside the app (assistant gesture, Quick Settings tile): open the conversation at once.
window.bittuTalk = (arg) => {
  if (!state.onboarded) return;
  // From the wake word: the words said after "Bittu" (possibly none). From a tile or gesture: nothing, so Bittu greets.
  let wake = arg && typeof arg === 'object' ? arg.wake : undefined;
  if (wake === undefined && window.BittuNative) { const t = window.BittuNative.wakeText(); if (t) wake = t; }
  const wasOpen = overlayOpen();
  if (wasOpen) closeOverlay();
  setTimeout(() => openVoice(undefined, wake), wasOpen ? 300 : 0);
};
window.bittuAttach = () => { if (state.onboarded && !overlayOpen()) openVoice(undefined, undefined, true); };
window.bittuBack = () => { if (overlayOpen()) { closeOverlay(); return true; } if (stack.length > 1) { history.back(); return true; } return false; };
document.addEventListener('change', (e) => {
  const el = e.target;
  if (!isApp) return;
  if (el.dataset.bindFlag && el.value.trim()) { const p = el.dataset.bindFlag.split('.'); saveKey(p[0] === 'keys' ? p[1] : 'gemini', el.value); el.value = ''; el.blur(); toast('Key saved on this phone'); refresh(); }
  if (el.dataset.bind === 'brightness' || el.dataset.bind === 'volume') applyLevel(el.dataset.bind, Number(el.value));
});
document.addEventListener('submit', (e) => {
  if (e.target.id !== 'live-whisper') return;
  e.preventDefault();
  const box = e.target.querySelector('input');
  if (box.value.trim()) { helper('whisper', { text: box.value.trim() }); state._live?.lines.push(['y', 'You whispered: ' + box.value.trim()]); box.value = ''; refresh(); }
});
window.addEventListener('bittu:changed', refresh);
matchMedia('(prefers-color-scheme: dark)').addEventListener('change', applyTheme);

// Text and icon animations play once, on the first screen after the app opens.
document.documentElement.classList.add('fresh');
setTimeout(() => document.documentElement.classList.remove('fresh'), 2600);
applyTheme();
initNative(refresh);
stack.push(route().key);
render('fade');
