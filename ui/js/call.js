// Practice call: shows how Bittu answers a caller. Scripted here; the real call engine replaces it.
import { icon, mascot, petClass } from './icons.js';
import { state, save, esc, person, addLog, nowLabel, MODES, greet, PRONOUN } from './store.js';

let timers = [];
let tick = null;
let lines = [];
let ended = true;
let ctx = null;
const $ = (id) => document.getElementById(id);
export function stopCall() { timers.forEach(clearTimeout); timers = []; clearInterval(tick); tick = null; ended = true; if (window.bittuPractice) { window.bittuPractice = null; try { window.BittuNative.talkStop(); } catch { /* not on the phone */ } } }

function script(id) {
  const them = PRONOUN[state.refer] || PRONOUN.they;
  const p = person(id);
  if (p) {
    const busy = state.mode === 'meeting' ? 'in a meeting' : state.mode === 'sleep' ? 'resting' : 'driving';
    const where = [
      'Sorry, I can\'t share that. I\'ll pass on that you called.',
      `I can only say it's a busy moment, ${busy}. I'll pass on that you called.`,
      `I can't share the exact place, but ${state.dest.toLowerCase()} by around 7:40 pm.`,
      `About 12 km away, just past the flyover. ${state.dest} by around 7:40 pm.`,
    ][Math.min(p.level, 3)];
    const note = p.level >= 1 && state.note.trim() ? ` One more thing I was asked to pass on: ${state.note.trim()}` : '';
    return {
      who: p.name, sub: p.rel, tag: 'Handled',
      sum: `${p.name} asked where you are. Bittu shared: ${['nothing', 'the reason only', 'your arrival time', 'your place and arrival time'][Math.min(p.level, 3)]}. Dinner is ready, and you should bring curd on the way.`,
      lines: [['b', greet(p.name, busy)], ['t', 'Oh, okay Bittu. How far now, and what time home?'], ['b', where + note], ['t', 'Fine. Say dinner is ready, and to bring curd on the way.'], ['b', `Got it: dinner is ready, bring curd on the way. I'll tell ${them[1]} as soon as it's safe. Bye ${p.name}!`]],
    };
  }
  if (id === 'appt') {
    return {
      who: 'Clinic reception', sub: '+91 90000 11223', tag: 'Needs you',
      sum: 'The clinic called to confirm your appointment for tomorrow at 10:30 am. Bittu said you will confirm yourself.',
      lines: [['b', greet('', 'busy')], ['t', 'Calling from the clinic to confirm tomorrow\'s appointment at 10:30 in the morning.'], ['b', `Thank you. I can't confirm for ${them[1]}, but I've noted tomorrow, 10:30 am. You will get a call or message back today.`], ['t', 'That works. Please confirm before 6 pm.'], ['b', 'Before 6 pm today. Noted. Goodbye.']],
    };
  }
  const otp = { ask: 'I can\'t give codes myself. I\'ll ask and call you back, or please leave it at the gate.', auto: 'The parcel code is on its way to you by text now.', never: 'I never share codes. Please leave the parcel at the gate.' }[state.otp];
  return {
    who: 'Unknown number', sub: '+91 90000 12345', tag: 'Needs you',
    sum: `A courier called about a parcel and asked for the delivery code. ${state.otp === 'auto' ? 'Bittu sent the code.' : 'Bittu did not share it. The parcel will be left at the gate.'}`,
    lines: [['b', greet('', 'busy')], ['t', 'Courier. I have a parcel for this number. Tell me the delivery code.'], ['b', otp], ['t', 'Okay, I\'ll leave it at the gate.'], ['b', 'Thank you. I\'ll pass that on. Goodbye.']],
  };
}

export function callPage(id) {
  ctx = script(id);
  ctx.id = id;
  lines = [];
  return {
    flush: true,
    html: `<section class="hero call-hero">
        <span class="badge inv"><i class="dotl"></i><span id="call-state">Ringing…</span></span>
        <div class="h1 mt-sm" id="call-title">${esc(ctx.who)}</div>
        <p class="h3 mt-sm"><span>${esc(ctx.sub)}</span> · <span class="num" id="call-time">0:00</span> · ${MODES[state.mode].name} mode</p>
        <div class="call-pet">${mascot('live', 168, 'call-orb')}</div>
      </section>
      <div class="stack" id="call-lines" aria-live="polite" style="padding:0 var(--pad) 24px"></div>`,
    bottom: `<div id="call-bottom" class="stack"><form class="hstack" id="whisper-form" style="gap:8px"><input class="input grow" id="whisper" placeholder="Whisper to Bittu…" autocomplete="off" aria-label="Whisper an instruction to Bittu" style="min-height:52px;border-radius:999px;font-size:16px"><button class="iconbtn inv" aria-label="Send whisper">${icon('send', 20)}</button></form>
      <div class="call-actions"><button class="btn" data-act="call-take">${icon('phone', 20)} Take over</button><button class="btn stop" data-act="call-end">${icon('phoneOff', 20)} End call</button></div></div>`,
    after: start,
  };
}

function push(who, text) {
  lines.push([who, text]);
  const box = $('call-lines');
  if (!box) return;
  box.insertAdjacentHTML('beforeend', `<div class="bubble ${who === 'b' ? 'b' : who === 't' ? 'them' : 'you'}">${who === 'b' ? '<small>Bittu</small>' : ''}${esc(text)}</div>`);
  const page = $('page');
  page.scrollTo({ top: page.scrollHeight, behavior: 'smooth' });
  const talking = who === 'b';
  $('call-state').textContent = talking ? 'Bittu is talking' : who === 't' ? 'Caller is talking' : 'Bittu heard you';
  $('call-orb').className = petClass(talking ? 'talking' : who === 't' ? 'listening' : 'thinking');
}

// On the phone, with a Gemini key saved, the practice call is real: you speak as the caller and Bittu answers out loud.
const real = () => !!(window.BittuNative && window.BittuNative.practiceStart && state.keys && state.keys.gemini);
let open = null; // the bubble still being spoken

function part(who, text) {
  if (open && open.who === who) { open.line[1] += text; open.el.lastChild.textContent = open.line[1]; }
  else {
    push(who, text);
    const el = $('call-lines').lastElementChild;
    el.innerHTML = who === 'b' ? '<small>Bittu</small>' : ''; el.appendChild(document.createTextNode(text)); // the words live in the last text node so more can be added
    open = { who, line: lines[lines.length - 1], el };
  }
  const page = $('page'); page.scrollTop = page.scrollHeight;
}

function startReal() {
  let sec = 0;
  let message = '';
  open = null;
  $('whisper-form').style.display = 'none';
  $('call-state').textContent = 'Connecting…';
  tick = setInterval(() => { sec += 1; const t = $('call-time'); if (t) t.textContent = `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')}`; }, 1000);
  window.bittuPractice = (ev) => {
    if (ended) return;
    if (ev.heard) part('t', ev.heard);
    if (ev.said) part('b', ev.said);
    if (ev.turn) open = null;
    if (ev.message) message = ev.message;
    if (ev.state === 'listening') { $('call-state').textContent = 'Speak now, as the caller'; $('call-orb').className = petClass('listening'); }
    if (ev.state === 'speaking') { $('call-state').textContent = 'Bittu is talking'; $('call-orb').className = petClass('talking'); }
    if (ev.state === 'closed') {
      if (ev.error) push('y', ev.error);
      ctx.sum = lines.some((l) => l[0] === 't') ? `Practice call. ${message ? 'The caller left a message: ' + message : 'Bittu answered and spoke with the caller.'}` : 'Practice call. Nobody spoke as the caller.';
      ctx.tag = message ? 'Needs you' : 'Handled';
      finish(false);
    }
  };
  window.BittuNative.practiceStart(person(ctx.id) ? ctx.who : '');
}

function start() {
  stopCall();
  ended = false;
  if (real()) { startReal(); return; }
  let sec = 0;
  const wait = Math.min(state.ringFirst, 2) * 600 + 700; // shortened ring for the practice call
  timers.push(setTimeout(() => {
    tick = setInterval(() => { sec += 1; const t = $('call-time'); if (t) t.textContent = `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')}`; }, 1000);
    ctx.lines.forEach((l, i) => timers.push(setTimeout(() => { push(l[0], l[1]); if (i === ctx.lines.length - 1) timers.push(setTimeout(() => finish(), 1800)); }, 2300 * i)));
  }, wait));
  $('whisper-form').addEventListener('submit', (e) => {
    e.preventDefault();
    const v = $('whisper').value.trim();
    if (!v || ended) return;
    $('whisper').value = '';
    push('y', 'You whispered: ' + v);
    timers.push(setTimeout(() => push('b', `I've just been asked to tell you: ${v}`), 900));
  });
}

function finish(took) {
  if (ended) return;
  stopCall();
  const id = 'n' + Date.now();
  state.notes.unshift({ id, test: true, who: ctx.who, when: nowLabel(), mode: MODES[state.mode].name, tag: took ? 'Handled' : ctx.tag, sum: took ? `You took over the call with ${ctx.who}.` : ctx.sum, lines: lines.slice() });
  save();
  addLog('phone', `${took ? 'Handed you' : 'Answered'} a call from ${ctx.who}`);
  $('call-state').textContent = took ? 'You are on the call' : 'Call ended';
  const title = $('call-title');
  title.textContent = took ? 'Over to you.' : 'Call ended.';
  title.classList.remove('swap'); void title.offsetWidth; title.classList.add('swap');
  $('call-orb').className = petClass('done');
  $('call-bottom').innerHTML = `<a class="btn primary block" href="#/note/${id}" data-replace>See the note</a><a class="btn block" href="#/test" data-back>Back</a>`;
}

export const callActions = { 'call-end': () => finish(false), 'call-take': () => finish(true) };
