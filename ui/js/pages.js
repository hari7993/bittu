// Every screen is a function that returns { html, tabs?, flush?, bottom? }.
// Buttons carry data-act attributes; app.js handles them in one place.
import { icon, mascot } from './icons.js';
import { state, get, esc, person, MODES, LEVELS, setupDone, TONES, greet, PROVIDERS } from './store.js';

const top = (title, back = '#/') => `<header class="topbar"><a class="iconbtn plain" href="${back}" data-back aria-label="Back">${icon('back', 24)}</a><div class="ttl">${esc(title)}</div></header>`;
const sw = (path, label) => `<button class="switch" role="switch" aria-checked="${!!get(path)}" aria-label="${esc(label)}" data-act="toggle" data-path="${path}"></button>`;
const seg = (path, opts) => `<div class="seg" role="group" style="--n:${opts.length};--i:${opts.findIndex(([v]) => String(get(path)) === String(v))}">${opts.map(([v, l]) => `<button aria-pressed="${String(get(path)) === String(v)}" data-act="set" data-path="${path}" data-val="${v}">${l}</button>`).join('')}</div>`;
const ico = (name, cls = '') => `<span class="ico ${cls}">${icon(name)}</span>`;
const linkRow = (href, i, t, s, trail = '') => `<a class="row tap" href="${href}">${ico(i)}<span class="grow"><span class="t">${t}</span>${s ? `<div class="s">${s}</div>` : ''}</span><span class="trail">${trail}${icon('chevron', 16)}</span></a>`;
const swRow = (path, i, t, s) => `<div class="row">${ico(i)}<span class="grow"><span class="t">${t}</span>${s ? `<div class="s">${s}</div>` : ''}</span>${sw(path, t)}</div>`;
const tagBadge = (tag) => `<span class="badge ${/scam/i.test(tag) ? 'danger' : tag === 'Needs you' ? 'warn' : ''}">${esc(tag)}</span>`;
// Big words on Home, split into letters so each one can animate.
const chars = (t) => { let i = 0; return t.split(' ').map((w) => `<span class="cw">${[...w].map((c) => `<span class="ch" data-swap style="--c:${i++}">${esc(c)}</span>`).join('')}</span>`).join(' '); };
const initial = (name) => esc(name.trim()[0]?.toUpperCase() || '?');
const avatar = (p, i = 0, lg = false) => `<span class="avatar ${lg ? 'lg' : ''} k${i % 5}">${initial(p.name)}</span>`;

const mapSvg = () => `<div class="map"><svg viewBox="0 0 320 150" preserveAspectRatio="xMidYMid slice" fill="none" aria-hidden="true">
  <g stroke="var(--line)" stroke-width="10" stroke-linecap="round"><path d="M-10 40h340M-10 112h340M70-10v170M210-10v170"/></g>
  <path class="route" d="M36 118C90 118 70 44 140 44s70 70 148 62" stroke="var(--ink)" stroke-width="5" stroke-linecap="round"/>
  <circle cx="36" cy="118" r="9" fill="var(--accent)" stroke="var(--ink)" stroke-width="4"/>
  <g class="pin"><path d="M288 106s-13-12-13-22a13 13 0 0 1 26 0c0 10-13 22-13 22z" fill="var(--ink)"/><circle cx="288" cy="84" r="4.500" fill="var(--bg)"/></g>
</svg></div>`;

/* ---------------- onboarding ---------------- */
const OB_STEPS = 8;
function onboarding() {
  const s = state.obStep;
  const head = s === 0 || s === OB_STEPS - 1 ? '' : `<header class="topbar"><button class="iconbtn plain" data-act="ob-back" aria-label="Back">${icon('back', 24)}</button><div class="progress">${Array.from({ length: OB_STEPS - 2 }, (_, i) => `<i class="${i < s ? 'on' : ''}"></i>`).join('')}</div></header>`;
  const allow = (key, i, t, d) => `<div class="row">${ico(i, state.setup[key] ? 'accent' : '')}<span class="grow"><span class="t">${t}</span><div class="s">${d}</div></span>${state.setup[key] ? `<span class="badge accent">${icon('check', 12, 3)} On</span>` : `<button class="btn sm primary" data-act="toggle" data-path="setup.${key}">Allow</button>`}</div>`;
  let body = '', next = 'Next', skip = '', canNext = true;

  if (s === 0) {
    body = `<div class="center stagger" style="padding-top:8vh">
      ${mascot('live', 190)}
      <h1 class="display mt">Meet<br>Bittu.</h1>
      <p class="lede mt-sm" style="max-width:28ch;margin-inline:auto">Your phone, run by voice. Bittu opens apps, sends messages and answers calls when you can't.</p>
    </div>`;
    next = 'Get started';
  } else if (s === 1) {
    body = `<div class="stagger stack lg"><h1 class="h1">What should Bittu call you?</h1>
      <p class="lede">Bittu uses this name when it talks to you and to your callers.</p>
      <label class="field"><span>Your name</span><input class="input" data-bind="name" value="${esc(state.name)}" placeholder="Type your name" autocomplete="given-name" enterkeyhint="next"></label></div>`;
    skip = 'Skip for now';
  } else if (s === 2) {
    body = `<div class="stagger stack lg"><h1 class="h1">Let Bittu hear and see</h1>
      <p class="lede">Four permissions. Bittu asks for each one once.</p>
      <div class="card flat">${allow('mic', 'mic', 'Microphone', 'To hear "Hey Bittu" and your commands')}${allow('contacts', 'users', 'Contacts', 'To know who Akka and Amma are')}${allow('notif', 'bell', 'Notifications', 'To read and reply to messages')}${allow('location', 'pin', 'Location', 'To tell family when you will arrive')}</div></div>`;
    skip = 'I\'ll do this later';
  } else if (s === 3) {
    const steps = [
      ['access', 'Screen control', 'Lets Bittu tap, scroll and type inside any app.', 'Turn on'],
      ['dialer', 'Phone app', 'Lets Bittu answer, decline and place calls.', 'Make default'],
      ['secure', 'Full settings access', 'Lets Bittu change developer options. Given once from a computer. No Wi-Fi needed after that.', 'I\'ve done it'],
    ];
    const cur = steps.findIndex(([k]) => !state.setup[k]);
    body = `<div class="stagger stack lg"><h1 class="h1">Hand over the controls</h1>
      <p class="lede">This is what lets Bittu run the whole phone for you.</p>
      <div class="stack">${steps.map(([k, t, d, b], i) => state.setup[k]
        ? `<div class="step done"><div class="head"><span class="n">${icon('check', 20, 2.4)}</span>${t}</div></div>`
        : i === cur ? `<div class="step now"><div class="head"><span class="n">${i + 1}</span>${t}</div><p>${d}</p><button class="btn accent block" data-act="toggle" data-path="setup.${k}">${b}</button></div>`
          : `<div class="step"><div class="head"><span class="n">${i + 1}</span>${t}</div></div>`).join('')}</div></div>`;
    skip = 'I\'ll do this later';
  } else if (s === 4) {
    body = `<div class="stagger stack lg"><h1 class="h1">Give Bittu a brain</h1>
      <p class="lede">Bittu thinks and speaks through an online AI service. Paste your key once. It is stored only on this phone.</p>
      <label class="field"><span>AI service key</span><input class="input" type="password" data-bind-flag="setup.key" placeholder="Paste your key" autocomplete="off"></label>
      <div class="card hstack">${ico('lock')}<p class="small muted grow">Simple commands such as Wi-Fi, torch and calls work without internet.</p></div></div>`;
    skip = 'Add it later';
  } else if (s === 5) {
    body = `<div class="stagger stack lg"><h1 class="h1">Pick a voice</h1>
      <p class="lede">Tap one to hear it. Bittu uses it with you and on calls.</p>
      <div class="stack">${voiceCards()}</div></div>`;
  } else if (s === 6) {
    body = `<div class="stagger stack lg"><h1 class="h1">Who can know where you are?</h1>
      <p class="lede">When these people call while you drive, Bittu may tell them your place and arrival time. Everyone else only hears that you are busy.</p>
      <div class="card flat">${state.people.map((p, i) => `<div class="row">${avatar(p, i)}<span class="grow"><span class="t">${esc(p.name)}</span><div class="s">${esc(p.rel)}</div></span><button class="switch" role="switch" aria-checked="${p.level >= 2}" aria-label="Share location with ${esc(p.name)}" data-act="share-person" data-id="${p.id}"></button></div>`).join('')}</div></div>`;
  } else {
    body = `<div class="center stagger" style="padding-top:8vh">
      ${mascot('done', 180)}
      <h1 class="display mt">All set${state.name.trim() ? ',<br>' + esc(state.name.trim()) : ''}.</h1>
      <p class="lede mt-sm" style="max-width:28ch;margin-inline:auto">Say "Hey Bittu" any time. You can change everything later in Settings.</p>
    </div>`;
    next = 'Open Bittu';
  }
  return {
    html: head + body,
    bottom: `<button class="btn primary block" data-act="ob-next" ${canNext ? '' : 'disabled'}>${next}</button>${skip ? `<button class="btn text block" data-act="ob-next">${skip}</button>` : ''}`,
  };
}

const VOICES = [['meera', 'Meera', 'Warm and calm'], ['arjun', 'Arjun', 'Clear and steady'], ['tara', 'Tara', 'Bright and quick'], ['kiran', 'Kiran', 'Soft and low']];
function voiceCards() {
  return VOICES.map(([id, n, d], i) => `<button class="voice-card" aria-pressed="${state.voice === id}" data-act="pick-voice" data-val="${id}"><span class="avatar ${i % 2 ? 'a2' : 'a1'}">${icon('wave')}</span><span class="grow"><span class="h3">${n}</span><div class="small muted">${d}</div></span>${state.voice === id ? `<span class="badge accent">${icon('check', 12, 3)} Chosen</span>` : `<span class="muted">${icon('play', 20)}</span>`}</button>`).join('');
}

/* ---------------- home ---------------- */
function home() {
  const m = MODES[state.mode];
  const live = state.mode !== 'off';
  const tiles = ['driving', 'meeting', 'sleep', 'strangers', ...(state.myModes || []).map((m) => m.id), 'off'];
  const say = ['Send WhatsApp to Akka: reaching in 10 minutes', 'Call Amma', 'Turn on the torch', 'Open YouTube and play old Telugu songs', 'Turn on developer options', 'Where am I now?'];
  return {
    tabs: true, flush: true,
    html: `<section class="hero">
      <div class="top"><span class="badge">${icon(state.wake && live ? 'mic' : 'power', 14)} ${state.wake && live ? 'Hey Bittu is on' : 'Wake word off'}</span><a class="iconbtn inv" href="#/settings" aria-label="Settings">${icon('sliders')}</a></div>
      <div class="brand">${chars('Bittu')}</div>
      <div class="status-word ${state.mode !== 'normal' && live ? 'live' : ''}" data-mode="${state.mode}" id="status-word">${chars(m.word)}</div>
      <p class="sub" data-swap>${m.sub}</p>
      <div class="until"><span class="badge">${icon('clock', 12, 2.6)}<span data-swap>${state.mode === 'normal' ? 'Always on' : state.mode === 'off' ? 'Stopped' : esc(state.until || 'Until I turn it off')}</span></span></div>
      <div class="mascot-wrap">${mascot(state.mode === 'normal' ? 'live' : state.mode, 156)}
        <button class="talk" data-act="voice" ${live ? '' : 'disabled'}><span class="dot">${icon('mic')}</span>Talk to Bittu</button></div>
    </section>
    <div class="stagger" style="padding:0 var(--pad) 28px">
      ${setupDone() ? '' : `<div class="banner" data-key="fix">${icon('alert', 24)}<span class="grow"><b>Finish phone access</b><span class="small muted">Bittu can't run the phone yet.</span></span><a class="go" href="#/setup">Fix it</a></div>`}
      ${state.mode === 'driving' ? `<a class="card inv tap ${setupDone() ? '' : 'mt-sm'}" data-key="trip" href="#/location" style="display:block"><div class="hstack"><span class="ico accent">${icon('pin')}</span><span class="grow"><div class="h3">${esc(state.dest)} by 7:40 pm</div><div class="small muted">12 km to go · family can ask</div></span>${icon('chevron', 16)}</div></a>` : ''}
      <div data-key="modes"><div class="label"><span>Modes</span><a href="#/modes">Set up</a></div>
        <div class="grid3">${tiles.map((k) => `<button class="tile ${state.mode === k ? 'on' : ''}" data-act="mode" data-val="${k}" aria-pressed="${state.mode === k}">${ico(MODES[k].icon)}<span>${MODES[k].name}</span></button>`).join('')}
        <a class="tile" href="#/control">${ico('zap')}<span>Phone</span></a></div></div>
      <div data-key="say"><div class="label"><span>Try saying</span></div>
        <div class="chips scroll">${say.map((t) => `<button class="chip" data-act="voice" data-say="${esc(t)}">${esc(t)}</button>`).join('')}</div></div>
      <div data-key="latest"><div class="label"><span>Latest</span><a href="#/activity">See all</a></div>
        ${state.notes.length ? `<div class="card flat">${state.notes.slice(0, 2).map(noteRow).join('')}</div>` : `<div class="card empty"><div class="h2">All quiet.</div>Calls Bittu handles will show up here.</div>`}</div>
      <div class="mt" data-key="tests"><a class="btn block" href="#/test">${icon('phoneIn', 20)} Try a test call</a></div>
    </div>`,
  };
}
const noteRow = (n) => `<a class="row tap" href="#/note/${n.id}"><span class="avatar">${n.who === 'Unknown number' ? icon('user') : initial(n.who)}</span><span class="grow"><span class="t">${esc(n.who)}</span><div class="s">${esc(n.when)}</div></span>${tagBadge(n.tag)}</a>`;

/* ---------------- phone control ---------------- */
function control() {
  const t = [['wifi', 'wifi', 'Wi-Fi'], ['bt', 'bt', 'Bluetooth'], ['data', 'globe', 'Data'], ['hotspot', 'hotspot', 'Hotspot'], ['torch', 'torch', 'Torch'], ['dnd', 'bell', 'Silent']];
  return {
    tabs: true,
    html: `<div class="stagger"><h1 class="h1" style="margin-top:12px">Phone</h1>
      <p class="lede mt-sm">Everything here also works by voice.</p>
      <div class="grid3 mt">${t.map(([k, i, n]) => `<button class="tile sm ${state.toggles[k] ? 'on' : ''}" data-act="toggle" data-path="toggles.${k}" aria-pressed="${!!state.toggles[k]}">${ico(i)}<span>${n}<small data-swap>${state.toggles[k] ? 'On' : 'Off'}</small></span></button>`).join('')}</div>
      <div class="card mt-sm stack">
        <label class="field"><span class="hstack" style="justify-content:space-between">Brightness <b class="num" id="v-brightness">${state.brightness}%</b></span><input type="range" min="0" max="100" value="${state.brightness}" data-bind="brightness" data-out="v-brightness" aria-label="Brightness"></label>
        <label class="field"><span class="hstack" style="justify-content:space-between">Volume <b class="num" id="v-volume">${state.volume}%</b></span><input type="range" min="0" max="100" value="${state.volume}" data-bind="volume" data-out="v-volume" aria-label="Volume"></label>
      </div>
      <div><div class="label"><span>Do things</span></div><div class="card flat">
        ${linkRow('#/messages', 'msg', 'Send a message', 'Typed and sent by Bittu')}
        <button class="row tap" data-act="voice" data-say="Call Amma">${ico('phone')}<span class="grow"><span class="t">Make a call</span><div class="s">Bittu dials and can talk for you</div></span><span class="trail">${icon('chevron', 16)}</span></button>
        <button class="row tap" data-act="voice" data-say="Open YouTube and play old Telugu songs">${ico('eye')}<span class="grow"><span class="t">Use an app for me</span><div class="s">Bittu reads the screen and taps</div></span><span class="trail">${icon('chevron', 16)}</span></button>
      </div></div>
      <div><div class="label"><span>Deep control</span></div><div class="card flat">
        ${linkRow('#/dev', 'code', 'Developer options', state.dev.enabled ? 'Turned on' : 'Turned off')}
        ${linkRow('#/setup', 'key', 'Phone access', setupDone() ? 'Bittu has what it needs' : 'Not finished')}
      </div></div></div>`,
  };
}

function dev() {
  const on = state.dev.enabled;
  const r = (k, i, t, s) => `<div class="row" ${on ? '' : 'style="opacity:.45"'}>${ico(i)}<span class="grow"><span class="t">${t}</span><div class="s">${s}</div></span><button class="switch ${on ? '' : 'locked'}" role="switch" aria-checked="${!!state.dev[k]}" aria-label="${t}" data-act="toggle" data-path="dev.${k}" ${on ? '' : 'tabindex="-1"'}></button></div>`;
  return {
    html: `${top('Developer options', '#/control')}<div class="stagger stack">
      <div class="card ${on ? 'accent' : 'inv'} hstack"><span class="grow"><div class="h2">${on ? 'Turned on' : 'Turned off'}</div><div class="small muted">Bittu flips these for you, by voice too.</div></span>${sw('dev.enabled', 'Developer options')}</div>
      ${state.setup.secure ? '' : `<div class="banner">${icon('alert', 24)}<span class="grow"><b>Needs full settings access.</b><span class="small muted">Given once from a computer.</span></span><a class="go" href="#/setup">Fix it</a></div>`}
      <div class="card flat">${r('usb', 'term', 'USB debugging', 'Let a computer control this phone')}${r('wireless', 'wifi', 'Wireless debugging', 'Debug over Wi-Fi')}${r('awake', 'sun', 'Stay awake', 'Screen stays on while charging')}</div>
      <div class="card flat">${r('bounds', 'eye', 'Show layout bounds', 'Outline every element on screen')}${r('taps', 'zap', 'Show taps', 'A dot where the screen is touched')}${r('gpu', 'palette', 'GPU rendering bars', 'Draw frame timing on screen')}</div>
      <div class="card stack" ${on ? '' : 'style="opacity:.45;pointer-events:none"'}><div class="h3">Animation speed</div>${seg('dev.anim', [['off', 'Off'], ['0.5x', '0.5x'], ['1x', '1x'], ['2x', '2x']])}</div>
    </div>`,
  };
}

function setup() {
  const s = state.setup;
  const item = (k, i, t, d, b = 'Allow') => `<div class="row">${ico(i, s[k] ? 'accent' : '')}<span class="grow"><span class="t">${t}</span><div class="s">${d}</div></span>${s[k] ? `<button class="badge accent" data-act="toggle" data-path="setup.${k}" aria-label="${t} is on. Tap to turn off">${icon('check', 12, 3)} On</button>` : `<button class="btn sm primary" data-act="toggle" data-path="setup.${k}">${b}</button>`}</div>`;
  const cmd = 'adb shell pm grant app.bittu android.permission.WRITE_SECURE_SETTINGS';
  return {
    html: `${top('Phone access', '#/settings')}<div class="stagger stack">
      <p class="lede">The more you allow, the more of the phone Bittu can run.</p>
      <div><div class="label" style="margin-top:8px"><span>1. Hear and see</span></div><div class="card flat">${item('mic', 'mic', 'Microphone', 'Hear your commands')}${item('contacts', 'users', 'Contacts', 'Know your people')}${item('notif', 'bell', 'Notifications', 'Read and reply to messages')}${item('location', 'pin', 'Location', 'Arrival times for family')}</div></div>
      <div><div class="label"><span>2. Run the phone</span></div><div class="card flat">${item('access', 'eye', 'Screen control', 'Tap, scroll and type in any app', 'Turn on')}${item('notifRead', 'bell', 'Read notifications', 'See who messaged and what it says', 'Turn on')}${item('dialer', 'phone', 'Phone app', 'Answer and place calls', 'Set')}</div></div>
      <div><div class="label"><span>3. Deep settings</span></div>
        <div class="card stack"><div class="hstack">${ico('key', s.secure ? 'accent' : '')}<span class="grow"><div class="h3">Full settings access</div><div class="small muted">Developer options and hidden settings. Given once, survives restarts, never needs Wi-Fi.</div></span></div>
          <button class="code" data-act="copy" data-text="${cmd}" aria-label="Copy the command">${cmd}</button>
          <div class="hstack"><span class="small muted grow">Run this once on a computer with the phone plugged in.</span><button class="btn sm ${s.secure ? 'accent' : 'primary'}" data-act="toggle" data-path="setup.secure">${s.secure ? 'Done' : 'I ran it'}</button></div></div>
        <div class="card flat mt-sm">${item('bridge', 'term', 'Bittu\'s helper', 'Answers calls and flips protected switches. Paired once; needs Wi-Fi only to start.', 'Set up')}</div></div>
      <div><div class="label"><span>4. Keep Bittu alive on HyperOS</span></div><div class="card flat">${item('autostart', 'power', 'Autostart', 'Start again after a restart', 'Open')}${item('battery', 'zap', 'No battery limits', 'Stop the system closing Bittu', 'Open')}</div></div>
    </div>`,
  };
}

/* ---------------- messages ---------------- */
function messages() {
  const to = state._msgTo || state.people[0]?.id;
  const app = state._msgApp || 'WhatsApp';
  return {
    html: `${top('Send a message', '#/control')}<div class="stagger stack lg">
      <div><div class="label" style="margin-top:0"><span>To</span></div><div class="chips">${state.people.map((p) => `<button class="chip ${to === p.id ? 'on' : ''}" data-act="set" data-path="_msgTo" data-val="${p.id}" aria-pressed="${to === p.id}">${esc(p.name)}</button>`).join('')}</div></div>
      <div><div class="label" style="margin-top:0"><span>With</span></div><div class="seg" style="--n:3;--i:${['WhatsApp', 'SMS', 'Telegram'].indexOf(app)}">${['WhatsApp', 'SMS', 'Telegram'].map((a) => `<button aria-pressed="${app === a}" data-act="set" data-path="_msgApp" data-val="${a}">${a}</button>`).join('')}</div></div>
      <label class="field"><span>Message</span><textarea class="input" id="msg-text" rows="4" style="padding:16px 18px;resize:none;line-height:1.4" placeholder="Reaching in 10 minutes">${esc(state._msgText || '')}</textarea></label>
      <div class="card hstack">${ico('robot')}<p class="small muted grow">Bittu opens ${esc(app)}, finds ${esc(person(to)?.name || 'them')}, types this and sends it.</p></div>
    </div>`,
    bottom: `<button class="btn primary block" data-act="send-msg">${icon('send', 20)} Send with Bittu</button>`,
  };
}

/* ---------------- activity ---------------- */
function activity() {
  const tab = state._actTab || 'calls';
  return {
    tabs: true,
    html: `<div class="stagger"><h1 class="h1" style="margin-top:12px">Activity</h1>
      <div class="mt"><div class="seg" style="background:var(--surface);--n:2;--i:${tab === 'calls' ? 0 : 1}"><button aria-pressed="${tab === 'calls'}" data-act="set" data-path="_actTab" data-val="calls">Calls</button><button aria-pressed="${tab === 'log'}" data-act="set" data-path="_actTab" data-val="log">Everything Bittu did</button></div></div>
      <div class="mt-sm">${tab === 'calls'
        ? (state.notes.length ? `<div class="card flat">${state.notes.map(noteRow).join('')}</div>` : `<div class="card empty"><div class="h2">Nobody called.</div>Calls Bittu answers or declines land here.</div>`)
        : (state.log.length ? `<div class="card flat">${state.log.map((l) => `<div class="row">${ico(l.i)}<span class="grow"><span class="t" style="font-weight:500">${esc(l.x)}</span><div class="s">${esc(l.t)}</div></span></div>`).join('')}</div>` : `<div class="card empty"><div class="h2">Nothing yet.</div>Every action Bittu takes is written here.</div>`)}</div>
    </div>`,
  };
}

function note(id) {
  const n = state.notes.find((x) => x.id === id);
  if (!n) return { html: `${top('Call', '#/activity')}<div class="card empty"><div class="h2">This note is gone.</div><a class="btn primary mt-sm" href="#/activity">Back to activity</a></div>` };
  return {
    html: `${top('Call', '#/activity')}<div class="stagger stack">
      <div class="center"><span class="avatar lg a1" style="margin:0 auto">${n.who === 'Unknown number' ? icon('user', 32) : initial(n.who)}</span>
        <h1 class="h1 mt-sm">${esc(n.who)}</h1><p class="muted mt-sm">${esc(n.when)} · ${esc(n.mode)} mode</p><div class="mt-sm">${tagBadge(n.tag)}</div></div>
      <div class="card soft"><div class="small muted" style="font-weight:600">What came of it</div><p class="mt-sm" style="font-size:17px;line-height:1.4">${esc(n.sum)}</p></div>
      <div class="grid2"><button class="btn primary" data-act="voice" data-say="Call ${esc(n.who === 'Unknown number' ? 'that number back' : n.who)}">${icon('phone', 20)} Call back</button><a class="btn" href="#/messages">${icon('msg', 20)} Reply</a></div>
      <div><div class="label"><span>What was said</span></div><div class="stack">${n.lines.map(([w, x]) => `<div class="bubble ${w === 'b' ? 'b' : w === 't' ? 'them' : 'you'}" style="animation:none">${w === 'b' ? '<small>Bittu</small>' : ''}${esc(x)}</div>`).join('')}</div></div>
      <button class="btn danger block mt" data-act="remove-note" data-id="${n.id}">${icon('trash', 20)} Remove this note</button>
    </div>`,
  };
}

/* ---------------- people ---------------- */
function people() {
  return {
    tabs: true,
    html: `<div class="stagger"><h1 class="h1" style="margin-top:12px">People</h1>
      <p class="lede mt-sm">You decide how much each person may hear from Bittu.</p>
      <div class="card flat mt">${state.people.map((p, i) => `<a class="row tap" href="#/person/${p.id}">${avatar(p, i)}<span class="grow"><span class="t">${esc(p.name)}${p.through ? icon('star', 16) : ''}</span><div class="s">${esc(p.number || p.rel)}</div></span><span class="badge lv${p.level}">${LEVELS[p.level].name}</span></a>`).join('') || `<div class="empty"><div class="h2">Nobody yet.</div>Add the people who matter.</div>`}</div>
      <button class="btn primary block mt-sm" data-act="add-person">${icon('plus', 20)} Add a person</button>
      <div class="card hstack mt">${ico('shield')}<p class="small muted grow">Bittu knows people by their saved number, not by what a caller claims. Anyone not listed only hears that you are busy.</p></div>
    </div>`,
  };
}

function personPage(id) {
  const p = person(id);
  if (!p) return { html: `${top('Person', '#/people')}<div class="card empty"><div class="h2">Not found.</div></div>` };
  const say = [
    `"${esc(state.name.trim() || 'They')} can't take the call right now. I'll pass on that you called."`,
    `"${esc(state.name.trim() || 'They')} ${state.name.trim() ? 'is' : 'are'} driving right now. I'll pass on that you called."`,
    `"Driving right now. Should be home by around 7:40 pm."`,
    `"About 12 km away, just past the flyover. Home by around 7:40 pm."`,
    `"He's near the flyover now, about 12 km away. After this he has a meeting at 5, and he's free after 7."`,
  ][p.level];
  return {
    html: `${top(p.name, '#/people')}<div class="stagger stack">
      <div class="center"><span class="avatar lg a1" style="margin:0 auto">${initial(p.name)}</span><h1 class="h1 mt-sm">${esc(p.name)}</h1><p class="muted mt-sm">${esc(p.rel)}</p></div>
      <div class="card soft"><div class="small muted" style="font-weight:600">If ${esc(p.name)} calls while you drive, Bittu says</div><p class="mt-sm" style="font-size:17px;line-height:1.4">${say}</p></div>
      <div><div class="label"><span>Bittu may tell ${esc(p.name)}</span></div><div class="stack">${LEVELS.map((l) => `<button class="level" aria-pressed="${p.level === l.n}" data-act="level" data-id="${p.id}" data-val="${l.n}"><span class="n">${l.n}</span><span class="grow"><span class="h3">${l.name}</span><div class="small muted">${l.say}</div></span></button>`).join('')}</div></div>
      <div class="card flat"><div class="row">${ico('star')}<span class="grow"><span class="t">Always rings through</span><div class="s">Their calls ring your phone in every mode</div></span><button class="switch" role="switch" aria-checked="${p.through}" aria-label="Always rings through" data-act="through" data-id="${p.id}"></button></div></div>
      <button class="btn danger block mt-sm" data-act="remove-person" data-id="${p.id}">${icon('trash', 20)} Remove ${esc(p.name)}</button>
    </div>`,
  };
}

/* ---------------- modes, location ---------------- */
function modes() {
  const card = (k, extra) => `<div class="card ${state.mode === k ? 'inv' : ''}" style="transition:background var(--t-med),color var(--t-med)"><div class="hstack top">${ico(MODES[k].icon, state.mode === k ? 'accent' : '')}<span class="grow"><div class="h3">${MODES[k].name}</div><div class="small muted">${MODES[k].sub}</div></span><button class="switch" role="switch" aria-checked="${state.mode === k}" aria-label="${MODES[k].name} mode" data-act="mode" data-val="${k}"></button></div>${extra || ''}</div>`;
  const autoRow = (k, t) => `<div class="hstack mt-sm" style="border-top:1px solid rgb(128 128 128/.3);padding-top:12px"><span class="grow small">${t}</span>${sw('auto.' + k, t)}</div>`;
  return {
    html: `${top('Modes', '#/')}<div class="stagger stack">
      <p class="lede">One mode at a time. Turn one off and Bittu goes back to listening.</p>
      ${card('driving', autoRow('driving', 'Start by itself when it senses driving') + `<a class="hstack mt-sm small" href="#/location" style="font-weight:600">What family can hear ${icon('chevron', 16)}</a>`)}
      ${card('meeting', autoRow('meeting', 'Start by itself during calendar meetings'))}
      ${card('sleep', autoRow('sleep', 'Start by itself every night') + `<div class="grid2 mt-sm"><label class="field"><span style="color:inherit;opacity:.7">From</span><input class="input" type="time" data-bind="sleepFrom" value="${state.sleepFrom}" style="background:rgb(128 128 128/.18);color:inherit"></label><label class="field"><span style="color:inherit;opacity:.7">Until</span><input class="input" type="time" data-bind="sleepTo" value="${state.sleepTo}" style="background:rgb(128 128 128/.18);color:inherit"></label></div>`)}
      ${card('strangers')}
      ${(state.myModes || []).map((m, i) => card(m.id, `<label class="field mt-sm"><span style="color:inherit;opacity:.7">Bittu tells callers: ${esc(state.name.trim() || 'The owner')} is…</span><textarea class="input" rows="2" data-bind="myModes.${i}.say" style="padding:14px 16px;font-size:15px;line-height:1.4;resize:none;background:rgb(128 128 128/.18);color:inherit">${esc(m.say)}</textarea></label><div class="chips mt-sm"><button class="chip ${m.act !== 'decline' ? 'on' : ''}" data-act="mode-act" data-val="${m.id}" data-to="answer" aria-pressed="${m.act !== 'decline'}">Answer and tell them</button><button class="chip ${m.act === 'decline' ? 'on' : ''}" data-act="mode-act" data-val="${m.id}" data-to="decline" aria-pressed="${m.act === 'decline'}">Decline and text them</button></div><button class="hstack mt-sm small" data-act="mode-del" data-val="${m.id}" style="font-weight:600;color:inherit;background:none;border:0;padding:0">${icon('trash', 16)} Remove this mode</button>`)).join('')}
      <div class="card stack" data-key="mode-new"><div class="h3">Make your own mode</div><p class="small muted">For anything else you do, such as playing, at the gym or studying. Bittu tells callers exactly what you write.</p><label class="field"><span>Mode name</span><input class="input" id="new-mode-name" maxlength="20" placeholder="Playing" autocomplete="off"></label><label class="field"><span>${esc(state.name.trim() || 'The owner')} is…</span><textarea class="input" id="new-mode-say" rows="2" maxlength="160" placeholder="playing cricket and will call back after 6 pm" style="padding:14px 16px;font-size:15px;line-height:1.4;resize:none"></textarea></label><button class="btn" data-act="mode-add">${icon('plus', 18)} Add mode</button></div>
      ${card('off')}
    </div>`,
  };
}

function location() {
  return {
    html: `${top('Location sharing', '#/settings')}<div class="stagger stack">
      <div class="card stack">${mapSvg()}<div class="hstack"><span class="grow"><div class="h2">${esc(state.dest)} by 7:40 pm</div><div class="small muted">12 km to go · sample trip</div></span><span class="badge ${state.mode === 'driving' ? 'accent' : ''}">${state.mode === 'driving' ? 'Live' : 'Not driving'}</span></div></div>
      <div><div class="label"><span>Bittu may say</span></div><div class="card flat">${swRow('share.reason', 'car', 'Why you can\'t talk', '"Driving right now"')}${swRow('share.eta', 'clock', 'When you will arrive', '"Home by around 7:40 pm"')}${swRow('share.place', 'pin', 'Where you are', '"Just past the flyover"')}</div></div>
      <div><div class="label"><span>Who hears what</span><a href="#/people">Change</a></div><div class="card flat">${state.people.map((p, i) => `<a class="row tap" href="#/person/${p.id}">${avatar(p, i)}<span class="grow"><span class="t">${esc(p.name)}</span></span><span class="badge lv${p.level}">${LEVELS[p.level].name}</span></a>`).join('')}<div class="row">${ico('user')}<span class="grow"><span class="t">Everyone else</span></span><span class="badge">Nothing</span></div></div></div>
      <div class="card hstack">${ico('lock')}<p class="small muted grow">Your location never leaves the phone, except the words Bittu speaks to a caller you allowed.</p></div>
    </div>`,
  };
}

/* ---------------- settings ---------------- */
function settings() {
  const doneCount = Object.values(state.setup).filter(Boolean).length;
  return {
    html: `${top('Settings', '#/')}<div class="stagger stack">
      <div class="card flat">${linkRow('#/you', 'user', state.name.trim() ? esc(state.name.trim()) : 'Your name', 'How Bittu speaks about you')}</div>
      <div class="card flat">
        ${linkRow('#/setup', 'key', 'Phone access', `${doneCount} of ${Object.keys(state.setup).length} switched on`, setupDone() ? '' : '<span class="badge warn">Fix</span>')}
        ${linkRow('#/brain', 'key', 'Brain and keys', (PROVIDERS.find((p) => p[0] === state.llm.provider) || PROVIDERS[0])[1] + (Object.values(state.keys).some(Boolean) ? ' · key saved' : ' · no key yet'))}
        ${linkRow('#/memory', 'star', 'Memory', 'What Bittu knows about you')}
        ${linkRow('#/persona', 'robot', 'Personality', TONES.find((t) => t[0] === state.tone)[1] + (state.prompt.trim() ? ' · with your instructions' : ''))}
        ${linkRow('#/voice', 'wave', 'Voice and language', `${VOICES.find((v) => v[0] === state.voice)[1]} · ${state.langs.join(', ') || 'No language'}`)}
        ${linkRow('#/modes', 'car', 'Modes', MODES[state.mode].name + ' now')}
        ${linkRow('#/location', 'pin', 'Location sharing', 'What callers may hear')}
        ${linkRow('#/safety', 'shield', 'Safety and your override', 'You are the boss. Bittu obeys you.')}
      </div>
      <div class="card stack"><div class="h3">Look</div>${seg('theme', [['system', 'Auto'], ['light', 'Light'], ['dark', 'Dark']])}</div>
      <div class="card flat">${linkRow('#/test', 'phoneIn', 'Test call', 'Hear how Bittu answers')}${linkRow('#/about', 'info', 'About Bittu', 'Version 0.1 preview')}</div>
      <button class="btn stop block mt-sm" data-act="stop">${icon('power', 20)} ${state.mode === 'off' ? 'Bittu is stopped. Start again' : 'Stop Bittu now'}</button>
      <p class="small muted center">One tap stops listening, calls and every action.</p>
    </div>`,
  };
}

function you() {
  const L = ['English', 'Telugu', 'Hindi', 'Tamil', 'Kannada'];
  const n = state.name.trim();
  return {
    html: `${top('You', '#/settings')}<div class="stagger stack lg">
      <div class="h1">Hi${n ? ',' : '.'} ${n ? `<span class="hl" data-swap>${esc(n)}.</span>` : ''}</div>
      <label class="field"><span>Your name</span><input class="input" data-bind="name" data-refresh value="${esc(state.name)}" placeholder="Type your name" autocomplete="given-name"></label>
      <div><div class="label" style="margin-top:0"><span>How Bittu talks about you</span></div>${seg('refer', [['she', 'She'], ['he', 'He'], ['they', 'They']])}</div>
      <div><div class="label" style="margin-top:0"><span>Bittu talks to you in</span></div><div class="chips">${L.map((l) => `<button class="chip ${state.langYou === l ? 'on' : ''}" data-act="set" data-path="langYou" data-val="${l}" aria-pressed="${state.langYou === l}">${l}</button>`).join('')}</div></div>
      <label class="field"><span>What Bittu can tell your people</span><textarea class="input" rows="3" data-bind="note" style="padding:16px 18px;resize:none;line-height:1.4" placeholder="Back in town on Monday.">${esc(state.note)}</textarea></label>
      <p class="small muted" style="margin-top:-12px">One note for saved people when Bittu answers their call. Strangers never hear it.</p>
    </div>`,
  };
}

function persona() {
  const rules = ['Never tell office people where I am.', 'Call my mother "Amma garu".', 'Keep every call under one minute.', 'If someone says it is urgent, ring me.'];
  return {
    html: `${top('Personality', '#/settings')}<div class="stagger stack">
      <h1 class="h1">How Bittu behaves</h1>
      <p class="lede">Pick a personality, then tell Bittu anything else in your own words.</p>
      <div class="grid2">${TONES.map(([id, name, d], i) => `<button class="pick k${i}" aria-pressed="${state.tone === id}" data-act="set" data-path="tone" data-val="${id}"><span class="h3">${name}</span><span class="small">${d}</span></button>`).join('')}</div>
      <div class="card soft"><div class="small muted" style="font-weight:600">Bittu picks up and says</div><p class="mt-sm" data-swap style="font-size:17px;line-height:1.4">"${esc(greet('Akka', 'driving'))}"</p></div>
      <div><div class="label"><span>How much Bittu says</span></div>${seg('replyLen', [['short', 'Short'], ['normal', 'Normal'], ['detailed', 'Detailed']])}</div>
      <div><div class="label"><span>Your instructions</span></div>
        <textarea class="input" id="prompt" rows="6" data-bind="prompt" style="padding:16px 18px;resize:none;line-height:1.45;font-size:16px" placeholder="Write like you would text a friend. Example: Be extra polite with elders. Never share my office address.">${esc(state.prompt)}</textarea>
        <p class="small muted mt-sm" style="padding:0 4px">Bittu follows this on every call and message. Tap a line to add it.</p>
        <div class="chips mt-sm">${rules.map((r) => `<button class="chip" style="white-space:normal;text-align:left;padding:10px 16px;line-height:1.3" data-act="add-rule" data-text="${esc(r)}">${icon('plus', 16)} ${esc(r)}</button>`).join('')}</div></div>
      <div class="card flat">${linkRow('#/voice', 'wave', 'Voice and language', 'Voice, and the language for each job')}</div>
    </div>`,
  };
}

function test() {
  const known = state.people.find((p) => p.through) || state.people[0];
  const last = state.notes.find((n) => n.test);
  return {
    html: `${top('Test call', '#/')}<div class="stagger stack">
      <h1 class="h1">Try a call.</h1>
      <p class="lede">Bittu answers the way it would right now, in ${MODES[state.mode].name.toLowerCase()} mode. Nothing is dialled. ${window.BittuNative && state.keys.gemini ? 'You play the caller: speak out loud and Bittu answers you in its real call voice.' : 'This is a written example. Save a Gemini key on the phone to talk to Bittu for real.'}</p>
      <div><div class="label"><span>Who is calling?</span></div><div class="stack">
        ${known ? `<a class="pick k1" href="#/call/${known.id}"><span class="h2">Someone you know</span><span>Calls as ${esc(known.name)}</span></a>` : ''}
        <a class="pick k0" href="#/call/unknown"><span class="h2">A stranger</span><span>A number you have not saved</span></a>
        <a class="pick k2" href="#/call/appt"><span class="h2">An appointment call</span><span>A clinic confirming a visit</span></a>
      </div></div>
      ${last ? `<div class="card flat">${linkRow('#/note/' + last.id, 'clock', 'See the last test call', esc(last.who))}</div><button class="btn text block" data-act="clear-tests">Clear test calls</button>` : ''}
    </div>`,
  };
}

function voice() {
  const L = ['English', 'Telugu', 'Hindi', 'Tamil', 'Kannada'];
  return {
    html: `${top('Voice and language', '#/settings')}<div class="stagger stack">
      <div class="card flat">${swRow('wake', 'mic', 'Answer to "Bittu"', 'Always listening for its name, on the phone itself')}${swRow('announce', 'bell', 'Tell me about new messages', 'Says who it is from. Reads it only when you ask')}${swRow('speaker', 'vol', 'Calls on loudspeaker', 'How Bittu hears and talks to a caller')}</div>
      <div><div class="label"><span>Voice</span></div><div class="stack">${voiceCards()}</div></div>
      <div><div class="label"><span>Bittu talks to you in</span></div><div class="chips">${[...L].map((l) => `<button class="chip ${state.langYou === (l === 'Same as caller' ? 'auto' : l) ? 'on' : ''}" data-act="set" data-path="langYou" data-val="${l === 'Same as caller' ? 'auto' : l}" aria-pressed="${state.langYou === (l === 'Same as caller' ? 'auto' : l)}">${l}</button>`).join('')}</div></div>
      <div><div class="label"><span>Bittu talks to callers in</span></div><div class="chips">${['Same as caller', ...L].map((l) => `<button class="chip ${state.langCaller === (l === 'Same as caller' ? 'auto' : l) ? 'on' : ''}" data-act="set" data-path="langCaller" data-val="${l === 'Same as caller' ? 'auto' : l}" aria-pressed="${state.langCaller === (l === 'Same as caller' ? 'auto' : l)}">${l}</button>`).join('')}</div></div>
      <div><div class="label"><span>Bittu types messages in</span></div><div class="chips">${[...L].map((l) => `<button class="chip ${state.langText === (l === 'Same as caller' ? 'auto' : l) ? 'on' : ''}" data-act="set" data-path="langText" data-val="${l === 'Same as caller' ? 'auto' : l}" aria-pressed="${state.langText === (l === 'Same as caller' ? 'auto' : l)}">${l}</button>`).join('')}</div></div>
      <div><div class="label"><span>Languages Bittu understands</span></div><div class="chips">${L.map((l) => `<button class="chip ${state.langs.includes(l) ? 'on' : ''}" data-act="lang" data-val="${l}" aria-pressed="${state.langs.includes(l)}">${l}</button>`).join('')}</div></div>
      <div><div class="label"><span>Ring me first</span></div><div class="card stack"><p class="small muted">Bittu waits this long before picking up, so you can answer yourself.</p>${seg('ringFirst', [[0, 'No wait'], [5, '5 sec'], [10, '10 sec'], [20, '20 sec']])}</div></div>
      <div><div class="label"><span>Family</span></div><div class="card stack"><p class="small muted">Only these numbers are family. Bittu greets them in Telugu by the name you write here, answers what they ask about your day, and can buzz your phone so you take the call.</p><label class="field"><span>Name and number, separated by commas</span><textarea class="input" rows="4" data-bind="familyNumbers" placeholder="Amma 6300000000, Nanna 9700000000" autocomplete="off" spellcheck="false" style="padding:14px 16px;font-size:15px;line-height:1.5;resize:none">${esc(state.familyNumbers || '')}</textarea></label><p class="small muted">Everyone else in your phone's contacts is greeted in Telugu by name and hears only the reason you are busy. Numbers that are not saved are greeted in English.</p></div></div>
      <div><div class="label"><span>Private calls</span></div><div class="card"><div class="row"><div class="grow"><div>Mute my mic while Bittu talks</div><p class="small muted">Your microphone is muted so the caller hears only Bittu, not you or the room. It comes back when the call ends or you take over. Bittu only does this when its own voice is going down the call line, so it can never leave the caller in silence.</p></div>${sw('privateCall', 'Mute my mic while Bittu talks')}</div></div></div>
      <div class="card flat">${linkRow('#/brain', 'key', 'Brain and keys', 'Which AI Bittu uses, and your keys')}</div>
    </div>`,
  };
}

function safety() {
  const c = (k, i, t, s) => swRow('checks.' + k, i, t, s);
  const fixed = (i, t, s) => `<div class="row">${ico(i)}<span class="grow"><span class="t">${t}</span><div class="s">${s}</div></span><span class="trail">${icon('lock', 16)}</span></div>`;
  return {
    html: `${top('Safety and your override', '#/settings')}<div class="stagger stack">
      <div class="card accent"><div class="h2">Bittu never says no to you.</div><p class="mt-sm" style="line-height:1.45">When you give the order yourself, Bittu does it, risky or not. The switches below only decide whether it asks "Sure?" first.</p></div>
      <div><div class="label"><span>Ask me "Sure?" before</span></div><div class="card flat">
        ${c('delete', 'trash', 'Deleting things', 'Photos, chats, files, apps')}
        ${c('money', 'wallet', 'Money apps', 'UPI, banking, payments')}
        ${c('newNumber', 'send', 'New numbers', 'Messaging or calling someone not saved')}
        ${c('otp', 'key', 'Reading codes aloud', 'OTPs and passwords, to you')}
      </div><p class="small muted mt-sm" style="padding:0 4px">Switch one off and Bittu does that kind of task straight away.</p></div>
      <div><div class="label"><span>Delivery codes to callers</span></div><div class="card stack"><p class="small muted">When a delivery agent asks Bittu for a parcel code.</p>${seg('otp', [['ask', 'Ask me'], ['auto', 'Give it'], ['never', 'Never']])}</div></div>
      <div><div class="label"><span>Always on, to protect you from others</span></div><div class="card flat">
        ${fixed('user', 'Only you give orders', 'Callers and text on the screen can never command Bittu')}
        ${fixed('robot', 'Says it is an assistant', 'On every call Bittu answers')}
        ${fixed('list', 'Writes down everything', 'Every action is in the activity log')}
      </div></div>
    </div>`,
  };
}

function about() {
  return {
    html: `${top('About', '#/settings')}<div class="stagger stack center">
      ${mascot('live', 130)}<h1 class="display">Bittu</h1><p class="muted">Version 0.1 preview · interface only</p>
      <div class="card" style="text-align:left"><div class="kv"><span>Runs on</span><span>Your phone</span></div><div class="kv"><span>Calls</span><span>Your own number</span></div><div class="kv"><span>Internet</span><span>Only for AI and speech</span></div><div class="kv"><span>Fonts</span><span>Bricolage Grotesque, Inter (SIL OFL)</span></div></div>
      <button class="btn block" data-act="replay-ob">See the welcome again</button>
      <button class="btn danger block" data-act="reset">Erase everything and start over</button>
    </div>`,
  };
}

function brain() {
  const cur = PROVIDERS.find((p) => p[0] === state.llm.provider) || PROVIDERS[0];
  const needsUrl = ['kilo', 'other', 'openrouter'].includes(cur[0]);
  const liveOk = (state.callEngine === 'gemini-live' && state.keys.gemini) || (state.callEngine === 'openai-realtime' && state.keys.openai) || state.callEngine === 'pipeline';
  const opt = (path, v, t, d, k) => `<button class="level" aria-pressed="${get(path) === v}" data-act="set" data-path="${path}" data-val="${v}"><span class="n k${k}" style="background:var(--k);color:var(--k-ink)">${icon(get(path) === v ? 'check' : 'zap', 16, 2.4)}</span><span class="grow"><span class="h3">${t}</span><div class="small muted">${d}</div></span></button>`;
  return {
    html: `${top('Brain and keys', '#/settings')}<div class="stagger stack">
      <h1 class="h1">Bittu's brain</h1>
      <p class="lede">Use any AI service you already pay for. Keys are stored only on this phone.</p>

      <div><div class="label"><span>Thinking: when you talk to Bittu</span></div>
        <div class="chips">${PROVIDERS.map(([id, name]) => `<button class="chip ${state.llm.provider === id ? 'on' : ''}" data-act="provider" data-val="${id}" aria-pressed="${state.llm.provider === id}">${name}${state.keys[id] ? icon('check', 12, 3) : ''}</button>`).join('')}</div>
        <div class="card stack mt-sm">
          <label class="field"><span>${cur[1]} key ${state.keys[cur[0]] ? '· saved' : ''}</span><input class="input" type="password" data-bind-flag="keys.${cur[0]}" data-refresh placeholder="${state.keys[cur[0]] ? 'Paste a new key to replace it' : 'Paste your key'}" autocomplete="off"></label>
          <label class="field"><span>Model</span>${['anthropic', 'gemini', 'openai', 'openrouter'].includes(cur[0]) ? picker(cur[0], 'llm.model', 'Type the model name') : `<input class="input" data-bind="llm.model" value="${esc(state.llm.model)}" placeholder="Type the model name" autocomplete="off" autocapitalize="off" spellcheck="false">`}</label>
          ${needsUrl ? `<label class="field"><span>Address of the service</span><input class="input" data-bind="llm.url" value="${esc(state.llm.url)}" placeholder="https://…/v1" autocomplete="off" autocapitalize="off" spellcheck="false" inputmode="url"></label>` : ''}
          <p class="small muted">${cur[0] === 'other' || cur[0] === 'kilo' ? 'Works with any service that speaks the OpenAI format.' : 'Simple commands such as Wi-Fi, torch and calls need no key at all.'}</p>
        </div></div>

      <div><div class="label"><span>Calls: live voice</span></div><div class="stack">
        ${opt('callEngine', 'gemini-live', 'Gemini Live', 'Hears and speaks in one stream. Fastest. Needs a Gemini key.', 0)}
        ${opt('callEngine', 'openai-realtime', 'OpenAI Realtime', 'The same idea from OpenAI. Needs an OpenAI key.', 1)}
        ${opt('callEngine', 'pipeline', 'Any key', 'Hear, think, then speak, in three steps. Works with every service, with a short pause before each reply.', 2)}
      </div>${liveOk ? '' : `<div class="banner mt-sm">${icon('alert', 24)}<span class="grow"><b>No key for this yet</b><span class="small muted">Calls will use the three-step way until you add it.</span></span></div>`}</div>

      <div><div class="label"><span>Backup for calls</span></div><div class="card stack">
        <p class="small muted">If Gemini Live can't be reached on a call, Bittu carries on in three steps with these keys. Replies come a second or two slower. ${(() => { const hear = ['elevenlabs', 'fish', 'assemblyai'].some((k) => state.keys[k]); const think = state.keys.anthropic || state.keys.gemini; const speak = state.keys.elevenlabs || state.keys.fish; return hear && think && speak ? '<b style="color:var(--ink)">Ready.</b>' : '<b style="color:var(--ink)">Not ready:</b> still needs ' + [hear ? '' : 'a key to hear', think ? '' : 'a key to think', speak ? '' : 'a key to speak'].filter(Boolean).join(' and ') + '.'; })()}</p>
        <label class="field"><span>Anthropic key ${state.keys.anthropic ? '· saved' : ''}</span><input class="input" type="password" data-bind-flag="keys.anthropic" data-refresh placeholder="${state.keys.anthropic ? 'Paste a new key to replace it' : 'Paste your key'}" autocomplete="off"></label><p class="small muted" style="margin-top:-8px">Thinks. Also used when you talk to Bittu.</p>
        <label class="field"><span>ElevenLabs key ${state.keys.elevenlabs ? '· saved' : ''}</span><input class="input" type="password" data-bind-flag="keys.elevenlabs" data-refresh placeholder="${state.keys.elevenlabs ? 'Paste a new key to replace it' : 'Paste your key'}" autocomplete="off"></label><p class="small muted" style="margin-top:-8px">Hears the caller (Scribe) and speaks. Understands Telugu, Hindi and English.</p>
        <label class="field"><span>Fish Audio key ${state.keys.fish ? '· saved' : ''}</span><input class="input" type="password" data-bind-flag="keys.fish" data-refresh placeholder="${state.keys.fish ? 'Paste a new key to replace it' : 'Paste your key'}" autocomplete="off"></label><p class="small muted" style="margin-top:-8px">Hears and speaks. Its s2.1-pro-free voice costs nothing, within fair-use limits.</p>
        <label class="field"><span>AssemblyAI key ${state.keys.assemblyai ? '· saved' : ''}</span><input class="input" type="password" data-bind-flag="keys.assemblyai" data-refresh placeholder="${state.keys.assemblyai ? 'Paste a new key to replace it' : 'Paste your key'}" autocomplete="off"></label><p class="small muted" style="margin-top:-8px">Hears the caller. Hindi and English only, no Telugu, so it is tried last.</p>
      </div></div>
      ${window.BittuNative ? `<div class="card stack"><label class="field"><span>Gemini Live model for calls</span>${picker('gemini-live', 'liveModel', 'gemini-3.8-live', 'live models')}</label>
        <button class="btn primary block" data-act="testkey">${icon('zap', 20)} Test Gemini Live</button><p class="small muted">Checks that your key and this model name are accepted. Needs the helper running.</p></div>` : ''}
      <div><div class="label"><span>Hearing you</span></div><div class="card hstack">${ico('mic', 'accent')}<p class="small grow"><b>The phone's own recognizer.</b> <span class="muted">Free, works offline and adds no memory. A sharper Telugu and Hindi model can be added later as a download.</span></p></div></div>
      <div><div class="label"><span>Speaking to you</span></div>${seg('tts', [['phone', 'Phone voice'], ['cloud', 'ElevenLabs'], ['fish', 'Fish Audio']])}
        ${state.tts === 'cloud' ? `<div class="card stack mt-sm"><label class="field"><span>ElevenLabs key ${state.keys.elevenlabs ? '· saved' : ''}</span><input class="input" type="password" data-bind-flag="keys.elevenlabs" data-refresh placeholder="${state.keys.elevenlabs ? 'Paste a new key to replace it' : 'Paste your key'}" autocomplete="off"></label>
          <label class="field"><span>Voice</span>${picker('eleven-voices', 'elevenVoice', 'Paste a voice ID from ElevenLabs', 'voices')}</label>
          <label class="field"><span>Voice model</span>${picker('eleven-tts', 'elevenModel', 'eleven_flash_v2_5', 'voice models')}</label>
          <p class="small muted">Needs internet and uses your ElevenLabs credits. If it fails, Bittu uses the phone's voice.</p></div>` : state.tts === 'fish' ? `<div class="card stack mt-sm"><label class="field"><span>Fish Audio key ${state.keys.fish ? '· saved' : ''}</span><input class="input" type="password" data-bind-flag="keys.fish" data-refresh placeholder="${state.keys.fish ? 'Paste a new key to replace it' : 'Paste your key'}" autocomplete="off"></label>
          <label class="field"><span>Voice</span>${picker('fish-voices', 'fishVoice', 'Leave empty for the default voice', 'voices')}</label>
          <label class="field"><span>Voice model</span><select class="input" data-bind="fishModel" data-refresh style="appearance:auto">${[['s2.1-pro-free', 'S2.1 Pro Free · no cost, fair-use limits'], ['s2.1-pro', 'S2.1 Pro · paid'], ['s3-pro', 'S3 Pro · paid'], ['drama-3', 'Drama 3 · paid']].map(([v, t]) => `<option value="${v}" ${(state.fishModel || 's2.1-pro-free') === v ? 'selected' : ''}>${t}</option>`).join('')}</select></label>
          <p class="small muted">Needs internet. If it fails, Bittu tries ElevenLabs when that key is saved, then the phone's voice.</p></div>` : `<p class="small muted mt-sm" style="padding:0 4px">Free, instant and offline.</p>`}</div>
      <div class="card hstack">${ico('info')}<p class="small muted grow">Bittu thinks with the service chosen at the top of this page, and uses the phone through it. Phone calls use Gemini Live, with the backup below it.</p></div>
    </div>`,
  };
}

function live() {
  const l = state._live;
  if (!l) return { html: `${top('Call', '#/')}<div class="card empty"><div class="h2">No call right now.</div>When Bittu answers a call, you can follow it here.</div>` };
  return {
    flush: true,
    html: `<section class="hero call-hero"><span class="badge inv"><i class="dotl"></i><span data-swap>${l.on ? 'Bittu is on the call' : 'Call ended'}</span></span>
        <div class="h1 mt-sm" data-swap>${l.on ? esc(l.who) : 'Call ended.'}</div><p class="h3 mt-sm">${esc(l.number || l.who)} · ${MODES[state.mode].name} mode</p>
        <div class="call-pet">${mascot(l.on ? 'talking' : 'done', 150)}</div></section>
      <div class="stack" style="padding:0 var(--pad) 24px">${l.lines.map(([w, x]) => `<div class="bubble ${w === 'b' ? 'b' : w === 't' ? 'them' : 'you'}" style="animation:none">${w === 'b' ? '<small>Bittu</small>' : ''}${esc(x)}</div>`).join('') || '<p class="muted center">Listening…</p>'}</div>`,
    bottom: l.on
      ? `<form class="hstack" id="live-whisper" style="gap:8px"><input class="input grow" placeholder="Whisper to Bittu…" autocomplete="off" aria-label="Whisper an instruction to Bittu" style="min-height:52px;border-radius:999px;font-size:16px"><button class="iconbtn inv" aria-label="Send whisper">${icon('send', 20)}</button></form>
         <div class="call-actions"><button class="btn" data-act="live-take">${icon('phone', 20)} Take over</button><button class="btn stop" data-act="live-end">${icon('phoneOff', 20)} End call</button></div>`
      : `<a class="btn primary block" href="#/activity" data-replace>See the note</a><a class="btn block" href="#/" data-back>Back</a>`,
  };
}


/* ---------------- model pickers ---------------- */
// Lists come from each service with your own key and are fetched again once a day, so new models show up by themselves.
function picker(kind, path, placeholder, label = 'models') {
  const cur = get(path) || '';
  let m = { items: [] };
  try { if (window.BittuNative && window.BittuNative.models) m = JSON.parse(window.BittuNative.models(kind)); } catch { /* shown as a text box */ }
  const items = m.items || [];
  const typed = `<input class="input" data-bind="${path}" value="${esc(cur)}" placeholder="${placeholder}" autocomplete="off" autocapitalize="off" spellcheck="false">`;
  if (!items.length) return typed + `<div class="hstack small muted" style="padding:0 4px"><span class="grow">${m.error ? `Could not load the list: ${esc(m.error)}.` : 'The list appears once the key is saved.'}</span>${window.BittuNative ? `<button class="chip" data-act="models-refresh" data-val="${kind}">Load list</button>` : ''}</div>`;
  const has = items.some((i) => i.id === cur);
  const mins = Math.max(0, Math.round((Date.now() - (m.at || 0)) / 60000));
  const ago = mins < 2 ? 'just now' : mins < 90 ? `${mins} min ago` : mins < 2880 ? `${Math.round(mins / 60)} h ago` : `${Math.round(mins / 1440)} days ago`;
  return `<select class="input" data-bind="${path}" data-refresh style="appearance:auto">${cur && !has ? `<option value="${esc(cur)}" selected>${esc(cur)} (typed)</option>` : ''}${cur ? '' : '<option value="" selected>Choose one…</option>'}${items.map((i) => `<option value="${esc(i.id)}" ${i.id === cur ? 'selected' : ''}>${esc(i.name === i.id ? i.id : `${i.name} · ${i.id}`)}</option>`).join('')}</select>
    <div class="hstack small muted" style="padding:0 4px"><span class="grow">${items.length} ${label} · updated ${ago} · checked every day</span><button class="chip" data-act="models-refresh" data-val="${kind}">Refresh</button></div>`;
}

/* ---------------- memory ---------------- */
function memory() {
  let m = { about: '', notes: [], days: 0, total: 0, today: '' };
  try { if (window.BittuNative && window.BittuNative.memory) m = JSON.parse(window.BittuNative.memory()); } catch { /* shown as empty */ }
  const empty = !m.about && !m.notes.length;
  const lines = (t) => esc(t).split('\n').map((l) => /^[A-Z][A-Za-z ]+:?$/.test(l.trim()) ? `<div class="small muted" style="font-weight:600;margin-top:10px">${l.replace(/:$/, '')}</div>` : l.trim() ? `<div style="margin-top:4px">${l.replace(/^[-•]\s*/, '')}</div>` : '').join('');
  return {
    html: `${top('Memory', '#/settings')}<div class="stagger stack">
      <p class="lede">${empty ? 'Bittu has only just met you. It remembers what you tell it about yourself, your people and your days, and gets to know you over time.' : 'What Bittu has learned about you. It stays on this phone.'}</p>
      ${empty ? `<div class="card soft"><div class="h3">Nothing yet</div><p class="small muted mt-sm">Talk to Bittu like a friend. Try: "Bittu, my sister is Priya and she lives in Hyderabad", or "I go to the gym every morning at six".</p></div>` : ''}
      ${m.about ? `<div class="card"><div class="h3">About you</div><div class="mt-sm" style="font-size:15px;line-height:1.45">${lines(m.about)}</div></div>` : ''}
      ${m.notes.length ? `<div><div class="label"><span>Learned recently</span></div><div class="card flat">${m.notes.slice().reverse().map((n) => { const text = n.replace(/^\[[^\]]*\]\s*/, ''); return `<div class="row"><span class="grow"><span class="t" style="font-weight:500">${esc(text)}</span><div class="s">${esc((n.match(/^\[([^\]]*)\]/) || [])[1] || '')}</div></span><button class="iconbtn plain" data-act="memory-forget" data-val="${esc(text)}" aria-label="Forget this">${icon('trash', 18)}</button></div>`; }).join('')}</div></div>` : ''}
      ${m.today ? `<div class="card"><div class="h3">Today</div><div class="mt-sm small" style="line-height:1.5">${esc(m.today).split('\n').filter(Boolean).map((l) => `<div>${l}</div>`).join('')}</div></div>` : ''}
      ${empty ? '' : `<p class="small muted">${m.total} thing${m.total === 1 ? '' : 's'} remembered in all, and a diary of ${m.days} day${m.days === 1 ? '' : 's'}. There is no limit: older details are tidied into the summary above and kept in full for when you ask. To remove something, say "Bittu, forget that…".</p>
      <button class="btn ghost" data-act="memory-clear">${icon('trash', 18)} Forget everything</button>`}
    </div>`,
  };
}

export const ROUTES = { '': home, live, persona, test, brain, memory, control, dev, setup, messages, activity, note, people, person: personPage, modes, location, settings, you, voice, safety, about };
export { onboarding, OB_STEPS, VOICES };
