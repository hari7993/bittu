// Stroke icons drawn for Bittu (24px grid, round caps). icon('mic') returns an inline SVG string.
const P = {
  home: '<path d="M4 11 12 4l8 7v8a1 1 0 0 1-1 1h-4v-6H9v6H5a1 1 0 0 1-1-1z"/>',
  list: '<path d="M8 6h12M8 12h12M8 18h12"/><circle cx="4" cy="6" r=".6"/><circle cx="4" cy="12" r=".6"/><circle cx="4" cy="18" r=".6"/>',
  users: '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20c.6-3.4 3.2-5.5 6.5-5.5s5.900 2.100 6.500 5.500"/><path d="M16 4.800a3.500 3.500 0 0 1 0 6.400M18.500 14.800c1.700.8 2.700 2.600 3 5.200"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4.500 20.500c.8-3.800 3.800-6 7.500-6s6.700 2.200 7.500 6"/>',
  sliders: '<path d="M4 7h9M17 7h3M4 17h3M11 17h9"/><circle cx="15" cy="7" r="2"/><circle cx="9" cy="17" r="2"/>',
  mic: '<rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5.500 11.500a6.500 6.500 0 0 0 13 0M12 18v3"/>',
  phone: '<path d="M6.500 3.500h3l1.500 4-2 1.500a11 11 0 0 0 6 6l1.500-2 4 1.500v3a2 2 0 0 1-2.200 2A16.500 16.500 0 0 1 4.500 5.700a2 2 0 0 1 2-2.200z"/>',
  phoneOff: '<path d="M4 15.500c4.500-4.300 11.500-4.300 16 0l-2 2.500-3.500-1.500v-2.700a8 8 0 0 0-5 0v2.700L6 18z"/>',
  msg: '<path d="M4 5.500h16v11H10l-4.500 3.500v-3.500H4z"/>',
  car: '<path d="M4 16v-3.500l2-5h12l2 5V16M3 16h18v2.500H3z"/><circle cx="7.500" cy="14" r=".7"/><circle cx="16.500" cy="14" r=".7"/><path d="M5 12.500h14"/>',
  cal: '<rect x="4" y="5.500" width="16" height="14.500" rx="2.500"/><path d="M4 10h16M8.500 3.500v4M15.500 3.500v4"/>',
  moon: '<path d="M19.500 14.500A8 8 0 1 1 9.500 4.500a6.500 6.500 0 0 0 10 10z"/>',
  shield: '<path d="M12 3.500 5 6v5.500c0 4.300 2.900 7.400 7 9 4.100-1.600 7-4.700 7-9V6z"/><path d="m9 12 2.200 2.200L15 10.400"/>',
  zap: '<path d="M13 3 5 13.500h6L10.500 21 19 10.500h-6z"/>',
  chevron: '<path d="m9 5 7 7-7 7"/>',
  back: '<path d="M20 12H5M11 5.500 4.500 12l6.500 6.500"/>',
  check: '<path d="m5 12.500 4.500 4.500L19 7.500"/>',
  x: '<path d="M6 6l12 12M18 6 6 18"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  pin: '<path d="M12 21s-6.500-5.800-6.500-11a6.500 6.500 0 0 1 13 0c0 5.200-6.500 11-6.500 11z"/><circle cx="12" cy="10" r="2.300"/>',
  bell: '<path d="M6 16.500V11a6 6 0 0 1 12 0v5.500l1.500 2h-15zM10 21h4"/>',
  lock: '<rect x="5" y="10.500" width="14" height="10" rx="2.500"/><path d="M8.500 10.500V8a3.500 3.500 0 0 1 7 0v2.500"/>',
  wifi: '<path d="M2.500 9.500a14 14 0 0 1 19 0M6 13a9 9 0 0 1 12 0M9.300 16.300a4.200 4.200 0 0 1 5.400 0"/><circle cx="12" cy="19.300" r=".7"/>',
  bt: '<path d="m7 7.500 10 9-5 4.500V3l5 4.500-10 9"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2.500v2M12 19.500v2M2.500 12h2M19.500 12h2M5.300 5.300l1.400 1.400M17.300 17.300l1.400 1.400M18.700 5.300l-1.400 1.400M6.700 17.300l-1.400 1.400"/>',
  vol: '<path d="M4 9.500h3.500L12 5.500v13l-4.500-4H4z"/><path d="M15.500 9a4.500 4.500 0 0 1 0 6M18 6.500a8 8 0 0 1 0 11"/>',
  torch: '<path d="M8 3.500h8v3.500l-2 3v10.500h-4V10L8 7zM8 7h8"/>',
  code: '<path d="m8 7-5 5 5 5M16 7l5 5-5 5M13.500 4.500l-3 15"/>',
  trash: '<path d="M4.500 7h15M9.500 7V4.500h5V7M6.500 7l1 13h9l1-13M10 11v5.500M14 11v5.500"/>',
  wallet: '<path d="M4 7.500A2.500 2.500 0 0 1 6.500 5H18v3.500M4 7.500v10A2.500 2.500 0 0 0 6.500 20H20V8.500H6.500A2.500 2.500 0 0 1 4 7.500z"/><circle cx="16" cy="14.300" r=".8"/>',
  key: '<circle cx="8" cy="15.500" r="4"/><path d="m11 12.500 8.500-8.500M16 7.500l2.500 2.500"/>',
  play: '<path d="M8 5.500v13l10.500-6.500z"/>',
  send: '<path d="M20.500 3.500 3.500 10.500l6.500 3 3 6.500zM10 13.500l4.500-4.500"/>',
  alert: '<circle cx="12" cy="12" r="9"/><path d="M12 7.500v5.500"/><circle cx="12" cy="16.300" r=".7"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5.500"/><circle cx="12" cy="7.700" r=".7"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5.300l3.500 2"/>',
  kbd: '<rect x="2.500" y="6" width="19" height="12" rx="2.500"/><path d="M6.500 10h.01M10 10h.01M13.500 10h.01M17 10h.01M7.500 14h9"/>',
  power: '<path d="M12 3.500v8M7 6.500a7.500 7.500 0 1 0 10 0"/>',
  eye: '<path d="M2.500 12S6 5.500 12 5.500 21.500 12 21.500 12 18 18.500 12 18.500 2.500 12 2.500 12z"/><circle cx="12" cy="12" r="2.800"/>',
  term: '<rect x="3" y="4.500" width="18" height="15" rx="2.500"/><path d="m7 9.500 3 2.500-3 2.500M12.500 14.500H17"/>',
  hotspot: '<circle cx="12" cy="12" r="1.500"/><path d="M8.500 15.500a5 5 0 0 1 0-7M15.500 8.500a5 5 0 0 1 0 7M5.500 18.500a9 9 0 0 1 0-13M18.500 5.500a9 9 0 0 1 0 13"/>',
  star: '<path d="m12 3.500 2.600 5.400 5.900.8-4.300 4.100 1 5.900-5.200-2.800-5.200 2.800 1-5.900-4.300-4.100 5.900-.800z"/>',
  wave: '<path d="M4 10v4M8 6.500v11M12 3.500v17M16 7.500v9M20 10.500v3"/>',
  phoneIn: '<path d="M6.500 3.500h3l1.500 4-2 1.500a11 11 0 0 0 6 6l1.500-2 4 1.500v3a2 2 0 0 1-2.200 2A16.500 16.500 0 0 1 4.500 5.700a2 2 0 0 1 2-2.200z"/><path d="M20 4l-5 5M15 5v4h4"/>',
  robot: '<rect x="5" y="8" width="14" height="11" rx="3.500"/><path d="M12 8V4.500"/><circle cx="12" cy="3.700" r="1"/><circle cx="9.300" cy="13" r=".8"/><circle cx="14.700" cy="13" r=".8"/><path d="M10 16h4"/>',
  globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c3 3.200 3 14.800 0 18M12 3c-3 3.200-3 14.800 0 18"/>',
  palette: '<path d="M12 3a9 9 0 1 0 0 18c1.500 0 2-1 1.500-2.200-.6-1.500.3-2.800 2-2.800H18a3 3 0 0 0 3-3c0-5.500-4-10-9-10z"/><circle cx="7.500" cy="11.500" r=".8"/><circle cx="10.500" cy="7.500" r=".8"/><circle cx="15" cy="8" r=".8"/>',
};

// Sizes used in the app: 12 (inside badges), 16 (trailing), 20 (default), 24 (bars), 32 (large).
export function icon(name, size = 20, sw = 2) {
  const body = (P[name] || P.info).replace(/<(path|circle|rect)/g, '<$1 pathLength="1"');
  return `<svg data-i="${name}" width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${body}</svg>`;
}

// Bittu the character: a soft toy blob with a face. All motion is CSS (see "character" in app.css).
// mood: live | driving | meeting | sleep | strangers | off | listening | thinking | talking | done
export const petClass = (mood) => `bt bt-${mood}`;
export function mascot(mood = 'live', size = 150, id = '') {
  return `<div class="${petClass(mood)}" ${id ? `id="${id}"` : ''} style="--s:${size}px" role="img" aria-label="Bittu">
  <i class="bt-ring"></i><i class="bt-ring two"></i>
  <i class="bt-shadow"></i>
  <div class="bt-jump"><div class="bt-body"><i class="bt-shine"></i>
      <div class="bt-face"><i class="bt-eye l"></i><i class="bt-eye r"></i><i class="bt-cheek l"></i><i class="bt-cheek r"></i><i class="bt-mouth"></i></div>
    </div></div>
  <span class="bt-dots"><i></i><i></i><i></i></span>
  <span class="bt-lines"><i></i><i></i><i></i></span>
  <span class="bt-z"><i>z</i><i>z</i></span>
</div>`;
}
