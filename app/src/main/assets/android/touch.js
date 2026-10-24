// On-screen controls for phones and tablets. They press the same keys a keyboard player would, so the
// launcher's own input code (loader.js: keydown/keyup on window, by KeyboardEvent.code) needs no changes,
// solo or online.
//
//   left thumb   floating stick: arrow keys, 8 directions (aim shots, place the ball, move in menus)
//   right thumb  ✕ S  shot / stock ball / select      ○ D  advanced shot / take run
//                □ A  cancel run                       △ W  back
//                LOFT Shift (hold with a shot)        SPL Alt (special delivery, with A/D/Shift)
//   top          pause (Esc) and a small menu: hide controls, button size, app setup
(() => {
  'use strict';
  const host = window.AndroidHost || null;
  const KEYS = {
    ArrowUp: 'ArrowUp', ArrowDown: 'ArrowDown', ArrowLeft: 'ArrowLeft', ArrowRight: 'ArrowRight',
    KeyS: 's', KeyD: 'd', KeyA: 'a', KeyW: 'w', ShiftLeft: 'Shift', AltLeft: 'Alt', Escape: 'Escape', Enter: 'Enter',
  };
  const press = (code, down) => window.dispatchEvent(new KeyboardEvent(down ? 'keydown' : 'keyup',
    { code, key: KEYS[code] || code, bubbles: true, cancelable: true }));
  const held = new Set();
  const hold = (code, on) => {
    if (on === held.has(code)) return;
    if (on) held.add(code); else held.delete(code);
    press(code, on);
  };
  const releaseAll = () => { for (const c of [...held]) hold(c, false); };
  const buzz = (ms = 12) => { if (host) host.vibrate(ms); else navigator.vibrate?.(ms); };
  // used by the app's back button: a short tap of one key
  window.__c07tap = (code) => { press(code, true); setTimeout(() => press(code, false), 90); };

  const touchDevice = matchMedia('(pointer: coarse)').matches || 'ontouchstart' in window;
  if (!touchDevice) return;

  const store = {
    get(k, d) { try { const v = localStorage.getItem('c07.' + k); return v === null ? d : JSON.parse(v); } catch { return d; } },
    set(k, v) { try { localStorage.setItem('c07.' + k, JSON.stringify(v)); } catch { /* private mode */ } },
  };

  // ------------------------------------------------------------ build the overlay

  const stage = document.getElementById('stage');
  const panel = document.getElementById('panel');
  const ui = document.createElement('div');
  ui.id = 'c07-touch';
  ui.className = 'c07-off';
  ui.innerHTML = `
    <div class="c07-stickzone"><div class="c07-base"><div class="c07-knob"></div></div></div>
    <div class="c07-pad">
      <button class="c07-btn c07-tri" data-code="KeyW" aria-label="Back">△</button>
      <button class="c07-btn c07-sq"  data-code="KeyA" aria-label="Cancel run">□</button>
      <button class="c07-btn c07-ci"  data-code="KeyD" aria-label="Advanced shot / run">○</button>
      <button class="c07-btn c07-x"   data-code="KeyS" aria-label="Shot / select">✕</button>
    </div>
    <div class="c07-shoulders">
      <button class="c07-btn c07-wide" data-code="ShiftLeft" aria-label="Lofted shot">LOFT</button>
      <button class="c07-btn c07-wide" data-code="AltLeft" aria-label="Special delivery">SPL</button>
    </div>
    <div class="c07-top">
      <button class="c07-small" id="c07-menu-btn" aria-label="Menu">⋯</button>
      <button class="c07-small c07-btn" data-code="Escape" aria-label="Pause">❚❚</button>
    </div>
    <div class="c07-menu c07-hidden" id="c07-menu">
      <button data-act="toggle">Hide touch controls</button>
      <button data-act="bigger">Bigger buttons</button>
      <button data-act="smaller">Smaller buttons</button>
      <button data-act="enter">Press Enter</button>
      ${host ? '<button data-act="setup">Leave game (app setup)</button>' : ''}
      <button data-act="close">Close</button>
    </div>`;
  stage.appendChild(ui);

  let scale = store.get('scale', 1);
  let hiddenByUser = store.get('hidden', false);
  const applyLook = () => {
    ui.style.setProperty('--c07-scale', String(scale));
    ui.classList.toggle('c07-minimal', hiddenByUser);
    ui.querySelector('[data-act="toggle"]').textContent = hiddenByUser ? 'Show touch controls' : 'Hide touch controls';
  };
  applyLook();

  // only while the game itself is on screen (the launcher's panel is hidden)
  const sync = () => {
    const playing = panel && panel.classList.contains('hidden');
    ui.classList.toggle('c07-off', !playing);
    if (!playing) releaseAll();
  };
  if (panel) new MutationObserver(sync).observe(panel, { attributes: true, attributeFilter: ['class'] });
  sync();
  document.addEventListener('visibilitychange', () => { if (document.hidden) releaseAll(); });

  // ------------------------------------------------------------ buttons

  for (const b of ui.querySelectorAll('.c07-btn[data-code]')) {
    const code = b.dataset.code;
    const up = () => { b.classList.remove('c07-down'); hold(code, false); };
    b.addEventListener('pointerdown', (e) => {
      e.preventDefault();
      b.setPointerCapture(e.pointerId);
      b.classList.add('c07-down');
      hold(code, true);
      buzz();
    });
    b.addEventListener('pointerup', up);
    b.addEventListener('pointercancel', up);
    b.addEventListener('lostpointercapture', up);
    b.addEventListener('contextmenu', (e) => e.preventDefault());
  }

  // ------------------------------------------------------------ floating stick

  const zone = ui.querySelector('.c07-stickzone');
  const base = ui.querySelector('.c07-base');
  const knob = ui.querySelector('.c07-knob');
  const DIAG = 0.38;   // sin(22.5°): eight equal sectors
  let stickId = null, cx = 0, cy = 0;
  const setDirs = (up, down, left, right) => {
    hold('ArrowUp', up); hold('ArrowDown', down); hold('ArrowLeft', left); hold('ArrowRight', right);
  };
  const radius = () => base.getBoundingClientRect().width / 2;
  const centre = (x, y) => {
    const r = zone.getBoundingClientRect();
    cx = x; cy = y;
    base.style.left = `${x - r.left}px`;
    base.style.top = `${y - r.top}px`;
  };
  const resetStick = () => {
    stickId = null;
    setDirs(false, false, false, false);
    knob.style.transform = 'translate(-50%, -50%)';
    base.classList.remove('c07-active');
    base.style.left = ''; base.style.top = '';
  };
  zone.addEventListener('pointerdown', (e) => {
    if (stickId !== null) return;
    e.preventDefault();
    stickId = e.pointerId;
    zone.setPointerCapture(e.pointerId);
    centre(e.clientX, e.clientY);
    base.classList.add('c07-active');
  });
  zone.addEventListener('pointermove', (e) => {
    if (e.pointerId !== stickId) return;
    const R = radius();
    let dx = e.clientX - cx, dy = e.clientY - cy;
    const dist = Math.hypot(dx, dy);
    if (dist > R) { dx *= R / dist; dy *= R / dist; }
    knob.style.transform = `translate(calc(-50% + ${dx}px), calc(-50% + ${dy}px))`;
    if (dist < R * 0.28) { setDirs(false, false, false, false); return; }
    const nx = dx / Math.hypot(dx, dy), ny = dy / Math.hypot(dx, dy);
    const before = held.size;
    setDirs(ny < -DIAG, ny > DIAG, nx < -DIAG, nx > DIAG);
    if (held.size !== before) buzz(6);
  });
  for (const t of ['pointerup', 'pointercancel', 'lostpointercapture'])
    zone.addEventListener(t, (e) => { if (e.pointerId === stickId) resetStick(); });

  // ------------------------------------------------------------ menu

  const menu = ui.querySelector('#c07-menu');
  ui.querySelector('#c07-menu-btn').addEventListener('click', () => { releaseAll(); menu.classList.toggle('c07-hidden'); });
  menu.addEventListener('click', (e) => {
    const act = e.target.dataset?.act;
    if (!act) return;
    if (act === 'toggle') { hiddenByUser = !hiddenByUser; store.set('hidden', hiddenByUser); }
    if (act === 'bigger') { scale = Math.min(1.5, +(scale + 0.1).toFixed(2)); store.set('scale', scale); }
    if (act === 'smaller') { scale = Math.max(0.7, +(scale - 0.1).toFixed(2)); store.set('scale', scale); }
    if (act === 'enter') window.__c07tap('Enter');
    if (act === 'setup') host?.openSetup();
    applyLook();
    if (act !== 'bigger' && act !== 'smaller') menu.classList.add('c07-hidden');
  });
})();
