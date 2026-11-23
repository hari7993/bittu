// The link between the interface and the real phone. In a browser preview there is no phone, so every
// function here quietly does nothing and the interface keeps running on its sample data.
import { icon } from './icons.js';
import { state, save, set, esc, toast, addLog, MODES, setAfterSave } from './store.js';
import { openOverlay, closeOverlay } from './overlay.js';

const N = window.BittuNative || null;
export const isApp = !!N;
let seq = 0;
const waits = {};
let redraw = () => {};

window.bittuReply = (json) => { const m = JSON.parse(json); const f = waits[m.id]; delete waits[m.id]; if (f) f(m.res); };
window.bittuEvent = (json) => window.dispatchEvent(new CustomEvent('bittu:native', { detail: JSON.parse(json) }));

/** Ask Bittu's helper (the part with deep phone access) to do something. */
export const helper = (op, data = {}) => new Promise((res) => {
  if (!N) { res({ ok: false, error: 'preview' }); return; }
  const id = ++seq; waits[id] = res; N.shell(id, op, JSON.stringify(data));
});
export const sh = async (cmd) => ((await helper('sh', { cmd })).out || '').trim();

const LANG = { English: 'en-IN', Telugu: 'te-IN', Hindi: 'hi-IN', Tamil: 'ta-IN', Kannada: 'kn-IN' };
const VOICE = { meera: 'Aoede', arjun: 'Charon', tara: 'Kore', kiran: 'Puck' };
export const listen = () => N && N.listen(LANG[state.langYou] || 'en-IN');
export const stopListening = () => N && N.stopListening();
export const speak = (text) => N && N.speak(text, LANG[state.langYou] || 'en-IN');
export const openSettings = (which) => N && N.open(which);
export const saveKey = (provider, key) => { if (N) { N.saveKey(provider, key); state.keys[provider] = N.hasKey(provider); state.setup.key = Object.values(state.keys).some(Boolean); save(); } };

/** The owner's settings that the call helper needs. Sent whenever anything is saved. */
function pushConfig() {
  if (!N) return;
  // A timed mode whose time has passed while the page was asleep ends here, before anything is sent.
  if (state.modeEnds && Date.now() > state.modeEnds && !['normal', 'off'].includes(state.mode)) { state.mode = 'normal'; state.until = ''; state.modeEnds = 0; }
  N.config(JSON.stringify({
    name: state.name, refer: state.refer, tone: state.tone, replyLen: state.replyLen, prompt: state.prompt, note: state.note,
    mode: state.mode, until: state.until, modeEnds: state.modeEnds || 0, otp: state.otp, langCaller: state.langCaller, ringFirst: state.ringFirst, familyNumbers: state.familyNumbers || '', familyWords: state.familyWords || '', fishVoice: state.fishVoice || '', fishModel: state.fishModel || 's2.1-pro-free', elevenVoice: state.elevenVoice || '', elevenModel: state.elevenModel || 'eleven_flash_v2_5', backupModel: state.llm.provider === 'anthropic' && state.llm.model ? state.llm.model : 'claude-haiku-5-5', myModes: state.myModes || [], modeSay: ((state.myModes || []).find((m) => m.id === state.mode) || {}).say || '', modeAct: ((state.myModes || []).find((m) => m.id === state.mode) || {}).act || '', privateCall: state.privateCall !== false, meetingText: state.meetingText,
    share: state.share, dest: state.dest, liveModel: state.liveModel || 'gemini-3.8-live', liveVoice: VOICE[state.voice] || 'Aoede',
    textModel: state.llm.provider === 'gemini' && state.llm.model ? state.llm.model : 'gemini-flash-latest',
    people: state.people.map((p) => ({ name: p.name, rel: p.rel, level: p.level, through: p.through, number: p.number || '' })),
  }));
  N.talkConfig(JSON.stringify(talkCfg()));
}

const when = (ms) => { const d = new Date(ms); const today = new Date().toDateString() === d.toDateString(); return (today ? 'Today, ' : d.toLocaleDateString([], { day: 'numeric', month: 'short' }) + ', ') + d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }).toLowerCase(); };

/** Reads what is really true on the phone (permissions, helper, switches, new call notes) into the interface. */
export async function sync() {
  if (!N) return;
  const s = JSON.parse(N.status());
  Object.assign(state.setup, { mic: s.mic, contacts: s.contacts, notif: s.notif, location: s.location, access: s.access, dialer: s.phone, bridge: s.helper, secure: s.helper, notifRead: s.notifAccess });
  state._start = s.startCommand;
  for (const p of Object.keys(state.keys)) state.keys[p] = N.hasKey(p);
  state.setup.key = Object.values(state.keys).some(Boolean);
  for (const n of JSON.parse(N.takeNotes())) {
    state.notes.unshift({ id: n.id, who: n.who, number: n.number, when: when(n.time), mode: (MODES[n.mode] || MODES.normal).name, tag: n.tag, sum: n.sum, lines: n.lines });
    addLog('phone', `${n.tag === 'Handled' ? 'Handled' : 'Answered'} a call from ${n.who}`);
  }
  if (s.helper) {
    const v = (await sh('for k in wifi_on bluetooth_on mobile_data zen_mode development_settings_enabled adb_enabled adb_wifi_enabled stay_on_while_plugged_in; do settings get global $k; done; settings get system show_touches; getprop debug.layout')).split('\n').map((x) => x.trim());
    if (v.length >= 10) {
      Object.assign(state.toggles, { wifi: v[0] !== '0', bt: v[1] !== '0', data: v[2] !== '0', dnd: v[3] !== '0' && v[3] !== 'null' });
      Object.assign(state.dev, { enabled: v[4] === '1', usb: v[5] === '1', wireless: v[6] === '1', awake: v[7] !== '0' && v[7] !== 'null', taps: v[8] === '1', bounds: v[9] === 'true' });
    }
  }
  save();
  redraw();
}

const needHelper = () => { if (state.setup.bridge) return false; toast('Start Bittu\'s helper first (Phone access)'); return true; };
const SWITCH = {
  wifi: (on) => `svc wifi ${on ? 'enable' : 'disable'}`,
  bt: (on) => `svc bluetooth ${on ? 'enable' : 'disable'}`,
  data: (on) => `svc data ${on ? 'enable' : 'disable'}`,
  dnd: (on) => `cmd notification set_dnd ${on ? 'priority' : 'off'}`,
};
const DEV = {
  enabled: (on) => `settings put global development_settings_enabled ${on ? 1 : 0}`,
  usb: (on) => `settings put global adb_enabled ${on ? 1 : 0}`,
  wireless: (on) => `settings put global adb_wifi_enabled ${on ? 1 : 0}`,
  awake: (on) => `settings put global stay_on_while_plugged_in ${on ? 3 : 0}`,
  taps: (on) => `settings put system show_touches ${on ? 1 : 0}`,
  bounds: (on) => `setprop debug.layout ${on}; service call activity 1599295570 >/dev/null`,
  gpu: (on) => `setprop debug.hwui.profile ${on ? 'visual_bars' : 'false'}; service call activity 1599295570 >/dev/null`,
};

/** Flip a real switch. Returns true when the phone handled it (so the caller must not also flip the sample value). */
export function applySwitch(path, on) {
  if (!N) return false;
  const [group, key] = path.split('.');
  if (path === 'wake') { set('wake', on); N.wake(on); redraw(); toast(on ? 'Say "Bittu" any time' : 'Wake word is off'); return true; }
  if (group === 'setup') {
    if (['mic', 'contacts', 'notif', 'location'].includes(key)) N.ask(key);
    else if (key === 'dialer') N.ask('phone');
    else if (key === 'access') N.open('accessibility');
    else if (key === 'notifRead') N.open('notifications');
    else if (key === 'bridge' || key === 'secure') helperSheet();
    else if (key === 'autostart' || key === 'battery') { N.open(key); set(path, true); redraw(); }
    else return false;
    return true;
  }
  if (group === 'toggles') {
    if (key === 'torch') { if (N.torch(on)) { set(path, on); redraw(); } else toast('The torch is not available'); return true; }
    if (key === 'hotspot') { if (!needHelper()) sh('am start -a android.settings.TETHER_SETTINGS'); return true; }
    if (needHelper()) return true;
    sh(SWITCH[key](on)).then(() => setTimeout(sync, 900));
    set(path, on); redraw();
    return true;
  }
  if (group === 'dev' && DEV[key]) {
    if (needHelper()) return true;
    sh(DEV[key](on)); set(path, on); redraw();
    return true;
  }
  return false;
}

export function applyLevel(what, value) {
  if (!N) return;
  if (what === 'brightness' || what === 'volume') { const said = N.level(what, Math.round(value)); if (!/ is (set to|at) /.test(said)) toast(said); return; }
  if (!state.setup.bridge) return;
  if (what === 'dev.anim') { const v = value === 'off' ? 0 : parseFloat(value); sh(`for k in window_animation_scale transition_animation_scale animator_duration_scale; do settings put global $k ${v}; done`); }
}

/** Real actions for spoken commands. Each returns a sentence for Bittu to say, or null to fall back to the sample behaviour. */
export const act = {
  call: async (p) => { if (!N) return null; if (!p || !p.number) return 'I don\'t have a number for that person. Add them from your contacts first.'; if (needHelper()) return 'My helper isn\'t running, so I can\'t place calls yet.'; await sh(`am start -a android.intent.action.CALL -d tel:${p.number.replace(/[^0-9+]/g, '')}`); return `Calling ${p.name}.`; },
  sms: async (p, text) => { if (!N) return null; if (!p || !p.number) return 'I don\'t have a number for that person.'; return N.sms(p.number, text) ? `Sent to ${p.name}.` : 'I need permission to send texts. Allow it and ask me again.'; },
  whatsapp: async (p, text) => { if (!N) return null; if (!p || !p.number) return 'I don\'t have a number for that person.'; if (needHelper()) return 'My helper isn\'t running yet.'; const d = p.number.replace(/\D/g, ''); await sh(`am start -a android.intent.action.VIEW -d 'https://wa.me/${d.length === 10 ? '91' + d : d}?text=${encodeURIComponent(text).replace(/'/g, '%27')}'`); if (state.setup.access && N.screenTap('Send', 7000)) { N.screenPress('home'); return `Sent to ${p.name} on WhatsApp.`; } return state.setup.access ? `I opened your chat with ${p.name} and typed it, but I couldn't find the send button. Tap send.` : `I opened your chat with ${p.name} and typed it. Tap send. Turn on screen control and I'll press it myself.`; },
  open: async (name) => {
    if (!N || !state.setup.bridge) return null;
    const found = N.findApp(String(name));
    if (!found) return `There is no app called ${name} on this phone.`;
    const [component, label] = found.split('|');
    await sh(`am start -n '${component}'`);
    // Only say it opened if it really is in front now.
    for (let i = 0; i < 6; i++) {
      await new Promise((r) => setTimeout(r, 500));
      const top = await sh("dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'");
      if (top.includes(component.split('/')[0])) return `${label} is open on the screen now.`;
    }
    return `I tried to open ${label}, but it did not come to the front.`;
  },
};

const findPerson = (name) => {
  const q = String(name || '').trim().toLowerCase();
  if (!q) return null;
  const mine = state.people.find((p) => p.name.toLowerCase() === q) || state.people.find((p) => p.name.toLowerCase().includes(q));
  if (mine && mine.number) return mine;
  if (!N || !state.setup.contacts) return mine || null;
  const all = JSON.parse(N.contacts());
  return all.find((c) => c.name.toLowerCase() === q) || all.find((c) => c.name.toLowerCase().startsWith(q)) || all.find((c) => c.name.toLowerCase().includes(q)) || mine || null;
};

/** Things Bittu can do when it decides to, during a live conversation. Each returns a plain sentence saying what happened. */
export async function runTool(name, a) {
  try {
    if (name === 'phone_switch') {
      const key = { wifi: 'wifi', bluetooth: 'bt', mobile_data: 'data', torch: 'torch', silent: 'dnd', hotspot: 'hotspot' }[a.name];
      if (!key) return 'There is no switch called ' + a.name + '.';
      if (key !== 'torch' && !state.setup.bridge) return 'The helper is not running, so that switch could not be changed.';
      applySwitch('toggles.' + key, !!a.on);
      addLog(key === 'data' ? 'globe' : key === 'dnd' ? 'bell' : key, `Turned ${a.name.replace('_', ' ')} ${a.on ? 'on' : 'off'}`);
      return key === 'hotspot' ? 'The hotspot settings page is open. The owner must tap the switch.' : `${a.name} is now ${a.on ? 'on' : 'off'}.`;
    }
    if (name === 'set_mode') { if (!MODES[a.mode]) return 'Unknown mode.'; set('until', a.mode === 'normal' || a.mode === 'off' ? '' : 'Until I turn it off'); set('mode', a.mode); redraw(); addLog('car', `Mode set to ${MODES[a.mode].name}`); return `Mode is now ${MODES[a.mode].name}.`; }
    if (name === 'call_person') { const p = findPerson(a.name); if (!p) return `Nobody called ${a.name} is in the contacts.`; const r = await act.call(p); addLog('phone', 'Called ' + p.name); return r; }
    if (name === 'send_message') { const p = findPerson(a.name); if (!p) return `Nobody called ${a.name} is in the contacts.`; const r = await (String(a.app).toLowerCase() === 'sms' ? act.sms(p, a.text) : act.whatsapp(p, a.text)); addLog('msg', `Message to ${p.name}`); return r; }
    if (name === 'open_app') return await act.open(a.name) || 'The helper is not running, so apps cannot be opened.';
    if (name === 'set_level') { const n = Math.max(0, Math.min(100, Math.round(a.percent))); if (!state.setup.bridge) return 'The helper is not running, so that could not be changed.'; set(a.what, n); applyLevel(a.what, n); redraw(); return `${a.what} is at ${n} percent.`; }
    if (name === 'developer_option') {
      const key = { developer_options: 'enabled', usb_debugging: 'usb', wireless_debugging: 'wireless', stay_awake: 'awake', show_taps: 'taps', layout_bounds: 'bounds' }[a.name];
      if (!key) return 'There is no developer option called ' + a.name + '.';
      if (!state.setup.bridge) return 'The helper is not running, so developer options cannot be changed.';
      applySwitch('dev.' + key, !!a.on); addLog('code', `Turned ${a.name.replace(/_/g, ' ')} ${a.on ? 'on' : 'off'}`);
      return `${a.name.replace(/_/g, ' ')} is now ${a.on ? 'on' : 'off'}.`;
    }
    if (name === 'read_screen') { if (!state.setup.access) return 'Screen control is switched off, so the screen cannot be read.'; return N.screenRead() || 'Nothing readable is on screen.'; }
    if (name === 'tap_on') { if (!state.setup.access) return 'Screen control is switched off.'; return N.screenTap(String(a.words), 1500) ? `Tapped "${a.words}".` : `Nothing with the words "${a.words}" was found on screen.`; }
    if (name === 'type_text') { if (!state.setup.access) return 'Screen control is switched off.'; return N.screenType(String(a.text)) ? 'Typed it.' : 'No text box is open on screen.'; }
    if (name === 'press_button') { if (!state.setup.access) return 'Screen control is switched off.'; return N.screenPress(String(a.button)) ? `Pressed ${a.button}.` : 'That button could not be pressed.'; }
    return 'That action does not exist.';
  } catch (e) { return 'It failed: ' + e; }
}

const ownerPrompt = () => `You are Bittu, the personal phone assistant of ${state.name.trim() || 'the owner'}. The owner is speaking to you out loud, and your reply is read out by a voice.

How to answer:
- One or two short spoken sentences. Plain words only: no lists, no symbols, no emoji, no markdown.
- Style: ${{ warm: 'warm and friendly', polite: 'polite and respectful', playful: 'light and cheerful', brief: 'very brief' }[state.tone] || 'warm'}. Answer in ${state.langYou}, or in the language the owner just used.
- Your words come from a speech recognizer, so they may be slightly misheard. Take the most sensible meaning.
- If what you received is clearly not meant for you (background talk, half a word, noise), reply with exactly: (silent)

Doing things:
- You control this phone through your tools: switches, call modes, calling, WhatsApp and SMS, opening apps, brightness and volume, developer options, reading notifications and replying to them, and working inside any app by reading the screen, tapping, typing, scrolling and pressing back or home.
- When the owner asks for something, do it straight away. Do not ask for permission and do not say you cannot before you have tried. The owner is in charge of their own phone.
- "This" or "it" means what is on the screen right now. For example, while a reel is open in Instagram, "send this to Akka" means: read the screen, tap the share or send button, find Akka, tap her, tap send. Read the screen again after every step and carry on until it is done.
- To tell the owner about messages, use read_notifications. To answer one, use reply_to_notification with its number.
- Say what really happened, from the tool's answer. If a tool says it failed, say that plainly. Never claim something you did not see confirmed.
- Never type a password or a PIN. Stop and ask the owner to enter it.
- You can find where the phone is with my_location, and look things up on the internet with web_search. For weather, news, scores, prices or anything that changes, find the location if it matters, search, then answer in one or two short sentences. Never say you have no internet or no location.
${state.prompt.trim() ? '\nThe owner\'s standing instructions:\n' + state.prompt.trim() : ''}`;

export const canTalkLive = () => !!N && !!state.keys.gemini;
// Settings for conversations with the owner: Claude thinks, the phone (or ElevenLabs) speaks.
const talkCfg = () => ({ prompt: ownerPrompt(), provider: state.llm.provider || 'anthropic', url: state.llm.url || '', model: state.llm.model || (state.llm.provider === 'anthropic' ? 'claude-haiku-5-5' : ''), lang: LANG[state.langYou] || 'en-IN', tts: ['cloud', 'fish'].includes(state.tts) ? state.tts : 'phone', fishVoice: state.fishVoice || '', fishModel: state.fishModel || 's2.1-pro-free', elevenModel: state.elevenModel || 'eleven_flash_v2_5', elevenVoice: state.elevenVoice || '', announce: state.announce !== false });
/** Tell the always-on assistant to listen now, to take a typed request, or to be quiet. */
export const assist = (action, text = '') => N && N.assist(action, text);
/** @param wake undefined for a normal start; a string (possibly empty) when the owner said "Bittu" followed by those words. */
export const talkStart = (wake) => N.talkStart(JSON.stringify({ ...talkCfg(), idleSecs: wake === undefined ? 180 : 30, ...(wake === undefined ? {} : { hello: wake ? `The owner called your name and said: "${wake}". Respond to that now.` : 'The owner just called your name. Answer with a very short "Yes?" and listen.' }) }));
export const talkStop = () => N && N.talkStop();
export const toolResult = (id, text) => N && N.toolResult(id, String(text));

/** One question to the owner's chosen AI service, for when live voice is not available. */
const turns = [];
export async function think(text) {
  if (!N) return null;
  turns.push({ role: 'user', text });
  const res = await new Promise((r) => { const id = ++seq; waits[id] = r; N.think(id, JSON.stringify({ provider: state.llm.provider, model: state.llm.model, url: state.llm.url, system: ownerPrompt() + '\nYou have no tools in this mode: if the owner asks you to do something on the phone, tell them to say it as a short command.', turns: turns.slice(-8) })); });
  if (!res.ok) { turns.pop(); return res.error; }
  turns.push({ role: 'assistant', text: res.text });
  return res.text;
}

/** How the helper gets started: paired with the phone's own Wireless debugging, so no computer is needed. */
export function helperSheet() {
  const s = N ? JSON.parse(N.status()) : {};
  const steps = `<div class="stack mt">
           <div class="step"><div class="head"><span class="n">1</span>Connect to Wi-Fi</div></div>
           <div class="step"><div class="head"><span class="n">2</span>Tap Start pairing below</div><p class="small muted">Developer options opens, and a Bittu notification appears.</p></div>
           <div class="step"><div class="head"><span class="n">3</span>Open Wireless debugging and switch it on</div></div>
           <div class="step"><div class="head"><span class="n">4</span>Tap "Pair device with pairing code"</div><p class="small muted">Keep that window open.</p></div>
           <div class="step"><div class="head"><span class="n">5</span>Pull down the Bittu notification and type the six digits</div></div>
           <button class="btn primary block" data-act="pair-start">${icon('key', 20)} Start pairing</button><button class="btn text block" data-act="close-overlay">Close</button></div>`;
  // Pairing comes first: a helper started from a computer stops when the cable comes out.
  const body = !s.paired
    ? `<p class="muted mt-sm">${state.setup.bridge ? 'The helper is running now, but only because a computer started it. It will stop when the cable is unplugged. Pair once and Bittu starts it by itself, with no computer.' : 'Do this once. It lets Bittu start its helper by itself, with no computer.'}</p>${steps}`
    : state.setup.bridge
      ? `<p class="muted mt-sm">Bittu can answer calls and use the phone's protected switches. Bittu is paired, so after the phone restarts it starts the helper again by itself the next time Wi-Fi is on.</p>
         <div class="stack mt"><button class="btn block" data-act="pair-start">Pair again</button><button class="btn text block" data-act="close-overlay">Close</button></div>`
      : `<p class="muted mt-sm">Bittu is paired with this phone. It needs Wi-Fi for a moment to start the helper, then works without it.</p>
         <div class="stack mt"><button class="btn primary block" data-act="helper-start">${icon('zap', 20)} Start the helper now</button><button class="btn block" data-act="pair-start">Pair again</button><button class="btn text block" data-act="close-overlay">Close</button></div>`;
  const keep = !!(N && N.keepPortOn && N.keepPortOn());
  const port = s.paired ? `<div class="card mt" style="margin-top:16px"><div class="row" style="padding:0"><span class="grow"><span class="t">Restart without Wi-Fi</span><div class="s">${keep ? 'On. The phone keeps debugging port 5555 open so the helper can come back on mobile data or with no network. Never tap Allow on a debugging prompt you did not expect.' : 'Off. If the helper stops while you are away from Wi-Fi, it stays stopped until Wi-Fi is back.'}</div></span><button class="switch" role="switch" aria-checked="${keep}" aria-label="Restart without Wi-Fi" data-act="keep-port" data-on="${keep ? '0' : '1'}"></button></div></div>` : '';
  openOverlay(`<div class="sheet" role="dialog" aria-modal="true" aria-label="Bittu's helper"><div class="grab"></div>
    <h2 class="h2">${!s.paired ? 'Pair Bittu with this phone' : state.setup.bridge ? 'The helper is running' : 'The helper is stopped'}</h2>${body}${port}</div>`);
}
export const pairStart = () => N && N.pairStart();
export const helperStart = () => new Promise((res) => { if (!N) { res({ ok: false, text: 'preview' }); return; } const id = ++seq; waits[id] = res; N.helperStart(id); });

/** Pick a person from the phone's contacts. */
export function contactSheet(onPick) {
  if (!N) return false;
  if (!state.setup.contacts) { N.ask('contacts'); return true; }
  const all = JSON.parse(N.contacts());
  const row = (c, i) => `<button class="row tap" data-i="${i}"><span class="avatar k${i % 5}">${esc(c.name[0].toUpperCase())}</span><span class="grow"><span class="t">${esc(c.name)}</span><div class="s">${esc(c.number)}</div></span></button>`;
  openOverlay(`<div class="sheet" role="dialog" aria-modal="true" aria-label="Pick a contact" style="height:86%"><div class="grab"></div><h2 class="h2">Pick a contact</h2>
    <input class="input mt-sm" id="contact-q" placeholder="Search ${all.length} contacts" autocomplete="off" style="margin-top:12px">
    <div class="card flat mt-sm" id="contact-list" style="margin-top:12px">${all.slice(0, 60).map(row).join('') || '<div class="empty">No contacts with a phone number.</div>'}</div></div>`);
  const list = document.getElementById('contact-list');
  const show = (q) => { const hits = all.map((c, i) => [c, i]).filter(([c]) => !q || c.name.toLowerCase().includes(q) || c.number.includes(q)).slice(0, 60); list.innerHTML = hits.map(([c, i]) => row(c, i)).join('') || '<div class="empty">Nobody matches.</div>'; };
  document.getElementById('contact-q').addEventListener('input', (e) => show(e.target.value.trim().toLowerCase()));
  list.addEventListener('click', (e) => { const b = e.target.closest('[data-i]'); if (!b) return; closeOverlay(); onPick(all[Number(b.dataset.i)]); });
  return true;
}

/** Called once when the app starts. */
export function initNative(refresh) {
  if (!N) return;
  redraw = refresh;
  if (!state.v2) { state.v2 = true; state.llm = { provider: 'anthropic', model: 'claude-haiku-5-5', url: '' }; save(); } // conversations with the owner use Claude
  if (!state.v5) { state.v5 = true; state.v4 = true; state.privateCall = true; save(); } // back on: it is now applied only when it cannot silence Bittu
  if (!state.v4) { state.v4 = true; state.privateCall = false; save(); } // it made Bittu silent on calls; the owner can switch it back on to try
  if (!state.v3) { state.v3 = true; if (state.llm.provider === 'anthropic' && (!state.llm.model || state.llm.model === 'claude-opus-5-5')) state.llm.model = 'claude-haiku-5-5'; save(); } // the owner chose the faster model
  if (!state.real) { state.real = true; state.people = []; state.notes = []; state.log = []; state._live = null; save(); } // drop the sample people and calls
  setAfterSave(() => { clearTimeout(pushConfig.t); pushConfig.t = setTimeout(pushConfig, 300); });
  pushConfig();
  N.wake(!!state.wake);
  window.addEventListener('bittu:native', (e) => {
    const ev = e.detail;
    if (['resume', 'perm', 'ready', 'gone', 'notes'].includes(ev.ev)) sync();
    if (ev.ev === 'log') toast(ev.text);
    if (ev.ev === 'models' && location.hash.startsWith('#/brain')) redraw(); // a fresh model list arrived
    if (ev.ev === 'helper') { toast(ev.text); setTimeout(sync, 800); }
    if (window.bittuPractice) { if (ev.ev === 'talk') window.bittuPractice(ev); return; } // a practice call is on: its screen shows the talk
    if (ev.ev === 'talk' && ev.state === 'listening' && !document.getElementById('heard') && window.bittuAttach) window.bittuAttach();
    if (ev.ev === 'did') { addLog(ev.icon, ev.text); sync(); }
    if (ev.ev === 'mode' && MODES[ev.mode]) { state.mode = ev.mode; state.until = ev.until || ''; if (['normal', 'off'].includes(ev.mode)) state.modeEnds = 0; save(); redraw(); }
    if (ev.ev === 'perm' && state.wake) N.wake(true);
    if (ev.ev === 'call') {
      if (ev.state === 'ringing' && ev.action !== 'ring') toast(ev.action === 'decline' ? `Declining ${ev.who} with your text` : `Bittu will answer ${ev.who}`);
      if (ev.state === 'skipped') toast(ev.why);
      if (ev.state === 'live') { state._live = { who: ev.who, number: ev.number, lines: [], on: true }; location.hash = '#/live'; }
      if (ev.state === 'ended' && state._live) { state._live.on = false; redraw(); }
    }
    if (ev.ev === 'line' && state._live) { state._live.lines.push([ev.who, ev.text]); redraw(); const p = document.getElementById('page'); if (p) p.scrollTop = p.scrollHeight; }
  });
  sync();
}
