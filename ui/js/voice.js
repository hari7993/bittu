// The "Talk to Bittu" screen. In this UI preview the command engine is a small set of rules
// that changes the same state the Rust core will own later. Commands can be tapped or typed.
import { icon, mascot, petClass } from './icons.js';
import { state, set, esc, toast, addLog } from './store.js';
import { openOverlay, closeOverlay } from './overlay.js';
import { isApp, listen, stopListening, speak, act, applySwitch, applyLevel, assist, think } from './native.js';

let timers = [];
let pending = null;
const later = (fn, ms) => timers.push(setTimeout(fn, ms));
const clear = () => { timers.forEach(clearTimeout); timers = []; pending = null; };
// On the phone: real speech. Words arrive as they are heard, then the finished sentence is run as a command.
let liveOn = false;
let saidText = '';
let heardText = '';
// Live conversation: Bittu's words and the owner's words arrive as they are spoken.
function onTalk(ev) {
  if (!$('heard')) return;
  if (ev.ev === 'talk-tool') { $('plan').insertAdjacentHTML('beforeend', `<li class="ok"><span class="tick">${icon('check', 12, 3)}</span>${esc({ phone_switch: 'Changing a switch', set_mode: 'Changing the mode', call_person: 'Placing a call', send_message: 'Sending a message', open_app: 'Opening an app', set_level: 'Adjusting a level', developer_option: 'Changing a developer option', read_notifications: 'Reading notifications', reply_to_notification: 'Replying', scroll: 'Scrolling', read_screen: 'Reading the screen', tap_on: 'Tapping', type_text: 'Typing', press_button: 'Pressing a button' }[ev.name] || ev.name)}</li>`); return; }
  if (ev.state === 'listening') { setStage('Listening', 'listening'); $('v-chips').hidden = true; }
  if (ev.state === 'thinking') setStage('Thinking', 'thinking');
  if (ev.state === 'idle') { setStage('Say "Bittu"', 'live'); liveOn = false; }
  if (ev.state === 'speaking') setStage('Bittu is talking', 'talking');
  if (ev.heard) { if (saidText) { saidText = ''; $('v-reply').innerHTML = ''; $('plan').innerHTML = ''; } heardText = ev.whole ? ev.heard : heardText + ev.heard; $('heard').classList.remove('wait'); $('heard').textContent = heardText.trim(); }
  if (ev.said) { heardText = heardText && ''; saidText = ev.whole ? ev.said : saidText + ev.said; $('v-reply').innerHTML = `<div class="reply"><small style="opacity:.7;font-weight:600">Bittu</small><div>${esc(saidText.trim())}</div></div>`; }
  if (ev.turn) heardText = '';
  if (ev.state === 'closed') { liveOn = false; setStage('Tap the mic', 'live'); if (ev.error) { $('heard').classList.add('wait'); $('heard').textContent = ev.error; } $('v-chips').hidden = false; }
}
function startLive(wake) { liveOn = true; saidText = ''; heardText = ''; $('plan').innerHTML = ''; $('v-reply').innerHTML = ''; $('v-chips').hidden = true; $('heard').classList.add('wait'); $('heard').textContent = 'Say something…'; setStage('Listening', 'listening'); assist('listen'); }

function onSpeech(e) {
  const ev = e.detail;
  if (ev.ev === 'talk' || ev.ev === 'talk-tool') { onTalk(ev); return; }
  if (ev.ev !== 'stt' || !$('heard')) return;
  if (ev.partial) { $('heard').classList.remove('wait'); $('heard').textContent = ev.partial; }
  if (ev.final !== undefined) { if (ev.final.trim()) run(ev.final.trim()); else showError('I didn\'t catch that. Tap the mic and try again.'); }
  if (ev.error) showError(ev.error);
}
function showError(text) { setStage('Tap the mic', 'live'); $('heard').classList.add('wait'); $('heard').textContent = text; $('v-chips').hidden = false; }
function closeVoice() { clear(); stopListening(); assist('quiet'); liveOn = false; window.removeEventListener('bittu:native', onSpeech); }
const $ = (id) => document.getElementById(id);
const changed = () => window.dispatchEvent(new Event('bittu:changed'));
const cap = (s) => s.charAt(0).toUpperCase() + s.slice(1);

const SUGGEST = ['Turn off Wi-Fi', 'Send WhatsApp to Akka: reaching in 10 minutes', 'Delete all screenshots', 'Pay 500 rupees to Ravi', 'Start driving mode', 'Show layout bounds'];

export function openVoice(say, wake, attach) {
  clear();
  openOverlay(`<div class="sheet full" role="dialog" aria-modal="true" aria-label="Talk to Bittu">
    <div class="voice-top"><span class="badge" id="v-state" style="background:rgb(128 128 128/.22);color:inherit">Listening</span><button class="iconbtn" data-act="close-overlay" aria-label="Close">${icon('x')}</button></div>
    <div class="orb-wrap">${mascot('listening', 168, 'orb')}</div>
    <div class="heard wait" id="heard" aria-live="polite">Tell me what to do.</div>
    <ul class="plan" id="plan"></ul>
    <div id="v-reply" aria-live="polite"></div>
    <div class="chips scroll" id="v-chips" style="margin-top:14px">${SUGGEST.map((t) => `<button class="chip" data-act="v-say" data-say="${esc(t)}">${esc(t)}</button>`).join('')}</div>
    <form class="voice-input" id="v-form">${isApp ? `<button type="button" class="iconbtn" data-act="v-mic" style="background:var(--accent);color:var(--accent-ink);width:56px;height:56px" aria-label="Speak">${icon('mic')}</button>` : ''}<input class="input" id="v-text" placeholder="Or type a command" autocomplete="off" enterkeyhint="send" aria-label="Type a command"><button class="iconbtn" style="background:var(--accent);color:var(--accent-ink);width:56px;height:56px" aria-label="Send">${icon('send')}</button></form>
  </div>`, { scrim: false, onClose: closeVoice });
  window.removeEventListener('bittu:native', onSpeech);
  window.addEventListener('bittu:native', onSpeech);
  $('v-form').addEventListener('submit', (e) => {
    e.preventDefault();
    const v = $('v-text').value.trim();
    if (v) { $('v-text').value = ''; run(v); }
  });
  // attach: a conversation is already running (started by the wake word); only show it.
  if (attach) { liveOn = true; saidText = ''; heardText = ''; $('v-chips').hidden = true; setStage('Listening', 'listening'); }
  else if (say) later(() => run(say), 450);
  else if (isApp) startLive();
}

function setStage(label, orbClass) {
  $('v-state').textContent = label;
  $('orb').className = petClass(orbClass);
}

function run(cmd) {
  // On the phone every request, spoken or typed, goes to the always-on assistant.
  if (isApp) { liveOn = true; saidText = ''; heardText = ''; $('plan').innerHTML = ''; $('v-reply').innerHTML = ''; $('v-chips').hidden = true; assist('say', cmd); return; }
  clear();
  $('plan').innerHTML = '';
  $('v-reply').innerHTML = '';
  $('v-chips').hidden = true;
  setStage('Listening', 'listening');
  const heard = $('heard');
  heard.classList.remove('wait');
  heard.textContent = '';
  const words = cmd.split(/\s+/);
  words.forEach((w, i) => later(() => heard.insertAdjacentHTML('beforeend', `<span class="w-in">${esc((i ? ' ' : '') + w)}</span>`), 70 * i));
  later(() => setStage('Thinking', 'thinking'), 70 * words.length + 150);
  later(() => {
    const plan = interpret(cmd);
    if (plan.risk && state.checks[plan.risk]) ask(plan); else execute(plan);
  }, 70 * words.length + 750);
}

function ask(plan) {
  pending = plan;
  setStage('Waiting for you', 'live');
  $('v-reply').innerHTML = `<div class="reply ask"><b>${plan.sure}</b><div class="small" style="margin-top:2px">Say yes and I'll do it.</div>
    <div class="hstack" style="margin-top:12px;gap:8px;flex-wrap:wrap"><button class="btn sm primary" data-act="v-yes">Yes, do it</button><button class="btn sm raised" data-act="v-no">Cancel</button><button class="btn sm text" data-act="v-never" style="color:inherit">Don't ask again</button></div></div>`;
}

function execute(plan) {
  $('v-reply').innerHTML = '';
  setStage('Working', 'thinking');
  const list = $('plan');
  plan.steps.forEach((s, i) => {
    later(() => {
      list.querySelectorAll('li').forEach((li) => li.classList.add('ok'));
      list.insertAdjacentHTML('beforeend', `<li><span class="tick">${icon('check', 12, 3)}</span>${esc(s)}</li>`);
      list.scrollTop = list.scrollHeight;
    }, 520 * i);
  });
  later(async () => {
    list.querySelectorAll('li').forEach((li) => li.classList.add('ok'));
    // On the phone the action is real, and what Bittu says comes from what actually happened.
    const real = plan.real ? await plan.real() : null;
    if (real) plan.reply = real; else if (plan.effect) plan.effect();
    if (!$('v-reply')) return;
    speak(plan.reply);
    if (plan.log) addLog(plan.log[0], plan.log[1]);
    setStage('Done', 'done');
    $('v-reply').innerHTML = `<div class="reply"><small style="opacity:.7;font-weight:600">Bittu</small><div>${esc(plan.reply)}</div></div>`;
    $('v-chips').hidden = false;
    changed();
  }, 520 * plan.steps.length + 150);
}

export const voiceActions = {
  'v-say': (el) => run(el.dataset.say),
  'v-mic': () => { if (isApp) { if (liveOn) { assist('quiet'); liveOn = false; setStage('Tap the mic', 'live'); } else startLive(); return; } clear(); $('plan').innerHTML = ''; $('v-reply').innerHTML = ''; $('heard').classList.add('wait'); $('heard').textContent = 'Listening…'; setStage('Listening', 'listening'); listen(); },
  'v-yes': () => { const p = pending; pending = null; if (p) execute(p); },
  'v-no': () => { pending = null; setStage('Listening', 'listening'); $('v-reply').innerHTML = `<div class="reply">Okay, left as it is.</div>`; $('v-chips').hidden = false; },
  'v-never': () => { const p = pending; pending = null; if (!p) return; set('checks.' + p.risk, false); toast('Bittu won\'t ask for this again'); changed(); execute(p); },
  'close-overlay': () => closeOverlay(),
};

// Turns a sentence into steps, a spoken reply and a state change.
function interpret(cmd) {
  const c = cmd.toLowerCase();
  const who = state.people.find((p) => new RegExp('\\b' + p.name.toLowerCase() + '\\b').test(c));
  const named = who?.name || cap((c.match(/\b(?:to|call)\s+(?!the\b|my\b|that\b)([a-z]+)/) || [])[1] || 'them');
  const on = !/\b(off|stop|disable|close)\b/.test(c);
  const word = on ? 'on' : 'off';

  if (!isApp && /\b(delete|erase|clear|uninstall|remove)\b/.test(c)) {
    return { risk: 'delete', sure: 'This deletes things for good.', steps: ['Finding what you asked for', 'Selecting it', 'Deleting'], reply: 'Deleted.', log: ['trash', 'Deleted what you asked: "' + cmd + '"'] };
  }
  if (!isApp && /\b(pay|upi|transfer|bank|rupees|rs)\b|₹/.test(c)) {
    return { risk: 'money', sure: 'This opens a money app and fills in a payment.', steps: ['Opening the payments app', 'Filling in who and how much', 'Stopping at the PIN screen'], reply: 'Ready. Enter your PIN to finish. I never type PINs.', log: ['wallet', 'Set up a payment you asked for'] };
  }
  if (!isApp && /\b(otp|code|password)\b/.test(c)) {
    return { risk: 'otp', sure: 'This reads a private code out loud.', steps: ['Opening your latest message', 'Finding the code'], reply: 'Reading the code from your latest message now.', log: ['key', 'Read a code out to you'] };
  }
  if (/whatsapp|message|text|sms|telegram/.test(c)) {
    const app = /telegram/.test(c) ? 'Telegram' : /sms|text/.test(c) ? 'Messages' : 'WhatsApp';
    const text = (cmd.split(/:\s*/).slice(1).join(': ') || (cmd.match(/\b(?:saying|that)\s+(.+)$/i) || [])[1] || '').trim();
    return { risk: who ? null : 'newNumber', sure: `${named} is not in your people list.`, steps: ['Opening ' + app, 'Finding ' + named, 'Typing your message', 'Sending'], reply: `Sent to ${named} on ${app}.`, log: ['msg', `Sent a ${app} message to ${named}`],
      real: !isApp ? null : () => (!text ? 'What should the message say? Say it like: message Akka, saying I am late.' : app === 'Messages' ? act.sms(who, text) : app === 'WhatsApp' ? act.whatsapp(who, text) : null) };
  }
  if (/\bcall\b|\bdial\b|\bring\b/.test(c)) {
    return { risk: who ? null : 'newNumber', sure: `${named} is not in your people list.`, steps: ['Finding ' + named, 'Placing the call'], reply: `Calling ${named} now.`, log: ['phone', 'Called ' + named], real: !isApp ? null : () => act.call(who) };
  }
  const tg = [[/wi-?fi/, 'wifi', 'Wi-Fi'], [/bluetooth/, 'bt', 'Bluetooth'], [/hotspot/, 'hotspot', 'Hotspot'], [/torch|flashlight/, 'torch', 'Torch'], [/mobile data|\bdata\b/, 'data', 'Mobile data'], [/silent|do not disturb|dnd/, 'dnd', 'Silent']].find(([re]) => re.test(c));
  if (tg) return { steps: [`Turning ${tg[2]} ${word}`], reply: `${tg[2]} is ${word}.`, effect: () => { if (!applySwitch('toggles.' + tg[1], on)) set('toggles.' + tg[1], on); }, log: [tg[1] === 'data' ? 'globe' : tg[1] === 'dnd' ? 'bell' : tg[1], `Turned ${tg[2]} ${word}`] };

  const lvl = c.match(/\b(brightness|volume)\b.*?(\d{1,3})/);
  if (lvl) { const n = Math.min(100, Number(lvl[2])); return { steps: [`Setting ${lvl[1]} to ${n}%`], reply: `${cap(lvl[1])} is at ${n}%.`, effect: () => { set(lvl[1], n); applyLevel(lvl[1], n); }, log: [lvl[1] === 'volume' ? 'vol' : 'sun', `Set ${lvl[1]} to ${n}%`] }; }

  if (/developer|debugging|layout bounds|show taps|stay awake/.test(c)) {
    if (!state.setup.secure) return { steps: ['Checking phone access'], reply: 'I need full settings access for that. Finish it once in Phone access and I\'ll do it.' };
    const key = /usb/.test(c) ? 'usb' : /wireless/.test(c) ? 'wireless' : /bounds/.test(c) ? 'bounds' : /taps/.test(c) ? 'taps' : /awake/.test(c) ? 'awake' : 'enabled';
    const label = { usb: 'USB debugging', wireless: 'Wireless debugging', bounds: 'Layout bounds', taps: 'Show taps', awake: 'Stay awake', enabled: 'Developer options' }[key];
    return { steps: ['Opening hidden settings', `Turning ${label} ${word}`], reply: `${label} is ${word}.`, effect: () => { if (isApp) { if (on && key !== 'enabled') applySwitch('dev.enabled', true); applySwitch('dev.' + key, on); return; } if (on) set('dev.enabled', true); set('dev.' + key, on); }, log: ['code', `Turned ${label} ${word}`] };
  }
  const mode = ['driving', 'meeting', 'sleep'].find((m) => c.includes(m));
  if (mode) return { steps: [on ? `Starting ${mode} mode` : `Ending ${mode} mode`], reply: on ? `${cap(mode)} mode is on.` : 'Back to listening.', effect: () => set('mode', on ? mode : 'normal'), log: [mode === 'driving' ? 'car' : mode === 'meeting' ? 'cal' : 'moon', `${on ? 'Started' : 'Ended'} ${mode} mode`] };

  if (!isApp && /where|reach|arrive|how far/.test(c)) return { steps: ['Checking your location'], reply: `You are about 12 km from ${state.dest}. You should reach by 7:40 pm.` };

  const app = cap((c.match(/\bopen\s+([a-z]+)/) || [])[1] || 'the app');
  // On the phone, anything that is not a known command is a question for Bittu's brain.
  if (isApp && app === 'The app') return { steps: ['Thinking'], reply: '', real: () => think(cmd) };
  return { steps: ['Opening ' + app, 'Reading the screen', 'Tapping through it'], reply: 'Done. It\'s on your screen.', log: ['eye', 'Did on screen: "' + cmd + '"'], real: !isApp || app === 'The app' ? null : () => act.open(app) };
}
