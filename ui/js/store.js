// App state (saved on the device) and small helpers shared by every screen.
// The data here is sample data: the Rust core will replace it through the same get/set calls.
const KEY = 'bittu.ui.v1';

const defaults = () => ({
  onboarded: false,
  obStep: 0,
  name: '',
  refer: 'they', // she | he | they: how Bittu talks about the owner
  note: '', // one shared note Bittu may pass on to saved people
  tone: 'warm', // warm | polite | playful | brief
  replyLen: 'normal', // short | normal | detailed
  prompt: '', // the owner's own instructions to Bittu, in plain words
  llm: { provider: 'anthropic', model: 'claude-haiku-5-5', url: '' }, // the thinking model for commands and messages
  keys: { gemini: false, openai: false, anthropic: false, openrouter: false, kilo: false, other: false, elevenlabs: false, fish: false, assemblyai: false },
  elevenVoice: '', // an ElevenLabs voice id, when that voice is chosen
  elevenModel: 'eleven_flash_v2_5', // the ElevenLabs voice model
  fishVoice: '', // a Fish Audio voice id; empty means Fish Audio's default voice
  fishModel: 's2.1-pro-free', // the Fish Audio voice model
  announce: true, // say when a new message arrives (who from, never the content) // only whether a key is saved; the key itself is never kept here
  liveModel: 'gemini-3.8-live', // the Gemini Live model used on calls
  callEngine: 'gemini-live', // gemini-live | openai-realtime | pipeline
  stt: 'phone', // phone | bundled | cloud
  tts: 'phone',
  langYou: 'English',
  langCaller: 'auto',
  langText: 'English',
  theme: 'system',
  mode: 'normal', // normal | driving | meeting | sleep | strangers | off
  modeEnds: 0, // the moment a timed mode ends, as a clock time in milliseconds; 0 = until switched off
  until: '', // how long the current mode lasts, as shown to the owner
  auto: { driving: true, meeting: true, sleep: false },
  sleepFrom: '23:00',
  sleepTo: '07:00',
  meetingText: 'In a meeting right now. I will call you back soon.',
  setup: { notifRead: false, mic: false, contacts: false, notif: false, location: false, access: false, dialer: false, secure: false, bridge: false, autostart: false, battery: false, key: false },
  voice: 'meera',
  langs: ['English', 'Telugu'],
  wake: true,
  speaker: true,
  ringFirst: 10,
  familyNumbers: '', // family, as exact numbers with a name: "Amma 9000000001, Bava 9000000002". Full access and extra care.
  familyWords: '', // optional: contacts saved under these names also count as family
  myModes: [], // modes the owner made: { id, name, say, act }
  privateCall: true, // the caller hears only Bittu; applied only when Bittu's voice is on the call line itself
  toggles: { wifi: true, bt: false, data: true, hotspot: false, torch: false, dnd: false },
  brightness: 60,
  volume: 70,
  dev: { enabled: false, usb: false, wireless: false, awake: false, bounds: false, taps: false, gpu: false, anim: '1x' },
  checks: { delete: true, money: true, newNumber: true, otp: true },
  otp: 'ask',
  share: { place: true, eta: true, reason: true },
  dest: 'Home',
  people: [
    { id: 'akka', name: 'Akka', rel: 'Sister', level: 3, through: false },
    { id: 'amma', name: 'Amma', rel: 'Mother', level: 3, through: true },
    { id: 'nanna', name: 'Nanna', rel: 'Father', level: 2, through: true },
    { id: 'ravi', name: 'Ravi', rel: 'Office', level: 1, through: false },
  ],
  notes: [
    { id: 'n1', who: 'Amma', when: 'Today, 1:12 pm', mode: 'Meeting', tag: 'Handled', sum: 'Amma called during your meeting. Bittu declined and sent your meeting text.', lines: [['b', 'Declined the call and sent: "In a meeting right now. I will call you back soon."']] },
    { id: 'n2', who: 'Unknown number', when: 'Yesterday, 6:40 pm', mode: 'Strangers', tag: 'Scam risk', sum: 'Caller said your bank card is blocked and asked for a code. Bittu shared nothing and ended the call.', lines: [['b', 'Hello, this is Bittu, an assistant answering this phone. Who is calling?'], ['t', 'Calling from your bank. Your card is blocked. Tell me the code you just got.'], ['b', 'I never share codes or bank details. Goodbye.']] },
  ],
  log: [
    { t: 'Today, 1:12 pm', i: 'msg', x: 'Sent your meeting text to Amma' },
    { t: 'Today, 9:05 am', i: 'wifi', x: 'Turned Wi-Fi on when you asked' },
    { t: 'Yesterday, 6:41 pm', i: 'shield', x: 'Ended a call that asked for a bank code' },
  ],
});

function merge(base, saved) {
  for (const k in saved) {
    if (saved[k] && typeof saved[k] === 'object' && !Array.isArray(saved[k]) && base[k] && typeof base[k] === 'object') merge(base[k], saved[k]);
    else base[k] = saved[k];
  }
  return base;
}

function load() {
  try { return merge(defaults(), JSON.parse(localStorage.getItem(KEY) || '{}')); }
  catch { return defaults(); }
}

export const state = load();
let afterSave = null;
export const setAfterSave = (fn) => { afterSave = fn; };
export function save() { try { localStorage.setItem(KEY, JSON.stringify(state)); } catch { /* storage blocked: keep running in memory */ } if (afterSave) afterSave(); }
export function reset() { Object.assign(state, defaults()); save(); }

export function get(path) { return path.split('.').reduce((o, k) => (o == null ? o : o[k]), state); }
export function set(path, value) {
  const keys = path.split('.');
  const last = keys.pop();
  keys.reduce((o, k) => o[k], state)[last] = value;
  save();
}

export const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
export const ownerName = () => state.name.trim() || 'the owner';
export const person = (id) => state.people.find((p) => p.id === id);
export const nowLabel = () => 'Today, ' + new Date().toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }).toLowerCase();
export function addLog(i, x) { state.log.unshift({ t: nowLabel(), i, x }); state.log.length = Math.min(state.log.length, 60); save(); }

let toastTimer;
export function toast(text) {
  const el = document.getElementById('toast');
  el.textContent = text;
  el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), 2200);
}

export const MODES = {
  normal: { name: 'Listening', word: 'is listening.', sub: 'Say "Hey Bittu" or tap the button. Calls ring as usual.', icon: 'mic' },
  driving: { name: 'Driving', word: 'is driving.', sub: 'Bittu answers calls and tells your people when you will arrive.', icon: 'car', hint: 'Answers for you' },
  meeting: { name: 'Meeting', word: 'in a meeting.', sub: 'Calls are declined quietly and get your meeting text.', icon: 'cal', hint: 'Declines and texts' },
  sleep: { name: 'Sleep', word: 'is asleep.', sub: 'The phone stays silent. Only people who ring through can wake you.', icon: 'moon', hint: 'Silent nights' },
  strangers: { name: 'Strangers', word: 'screens calls.', sub: 'Bittu answers numbers you have not saved and asks what they want.', icon: 'shield', hint: 'Unknown numbers' },
  off: { name: 'Off', word: 'is off.', sub: 'Bittu is not listening and will not touch the phone.', icon: 'power', hint: 'Everything stops' },
};
/** Puts the owner's own modes next to the built-in ones. Called at start and whenever one is added, changed or removed. */
export function syncModes() {
  for (const k of Object.keys(MODES)) if (MODES[k].mine) delete MODES[k];
  for (const m of state.myModes || []) MODES[m.id] = { name: m.name, word: 'is busy.', sub: m.act === 'decline' ? 'Calls are declined and get your text.' : 'Bittu answers calls and tells them what you wrote.', icon: 'star', hint: 'Your mode', mine: true };
  if (!MODES[state.mode]) state.mode = 'normal';
}
syncModes();

export const LEVELS = [
  { n: 0, name: 'Nothing', say: 'Only that you can\'t take the call.' },
  { n: 1, name: 'Reason', say: 'What you are doing, such as driving or in a meeting.' },
  { n: 2, name: 'Arrival time', say: 'The reason and when you will arrive.' },
  { n: 3, name: 'Exact place', say: 'Where you are now and when you will arrive.' },
  { n: 4, name: 'Everything', say: 'Where you are now, what you are doing next and what is in your calendar. For family you trust fully.' },
];

export const PRONOUN = { she: ['she', 'her', 'is'], he: ['he', 'him', 'is'], they: ['they', 'them', 'are'] };
export const TONES = [['warm', 'Warm', 'Friendly and caring'], ['polite', 'Polite', 'Formal and respectful'], ['playful', 'Playful', 'Light and cheerful'], ['brief', 'Brief', 'Few words, straight to it']];
// The first thing Bittu says on a call, in the chosen personality.
export function greet(caller, busy) {
  const n = state.name.trim();
  const owner = n || 'the owner of this phone';
  const Owner = n || 'The owner of this phone';
  const hi = caller ? ' ' + caller : '';
  return {
    warm: `Hello${hi}, this is Bittu, ${n ? n + "'s" : 'the'} assistant. ${Owner} is ${busy} right now, so I picked up.`,
    polite: `Good day${hi}. This is Bittu, the assistant for ${owner}. ${Owner} is ${busy} at the moment. How may I help you?`,
    playful: `Hi${hi}! Bittu here, ${n ? n + "'s" : 'the'} little helper. ${Owner} is ${busy}, so you get me!`,
    brief: `Bittu here, assistant for ${owner}. ${Owner} is ${busy}.`,
  }[state.tone] || '';
}

export const PROVIDERS = [
  ['gemini', 'Gemini', 'gemini-flash-latest', ''],
  ['openai', 'OpenAI', '', ''],
  ['anthropic', 'Anthropic', 'claude-haiku-5-5', ''],
  ['openrouter', 'OpenRouter', '', 'https://openrouter.ai/api/v1'],
  ['kilo', 'Kilo Code', '', ''],
  ['other', 'Other', '', ''],
];

export const setupDone = () => ['mic', 'access', 'dialer'].every((k) => state.setup[k]);
