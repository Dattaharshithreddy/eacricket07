// Android shim: loaded before netplay.js and loader.js (the launcher from cricket07-browser, unchanged).
//
// - Game files come from the folder picked in the app, served at gamefiles/ by the app's loopback server,
//   and are streamed into the browser's private storage (OPFS) by android/import-worker.js. Android has no
//   folder picker for <input webkitdirectory>, so the launcher's "Choose game folder" button is redirected.
// - Online play is hidden (it needs a room server; pass ?netplay=<url> to bring it back).
// - When this WebView can't run the game (no WebGPU, no threads), it offers to open the game in Chrome.
(() => {
  'use strict';
  const host = window.AndroidHost || null;   // only inside the app's WebView, not in Chrome
  const byId = (id) => document.getElementById(id);
  const say = (t) => { const s = byId('status'); if (s) s.textContent = t; };
  const megabytes = (n) => (n / 1e6).toFixed(0);
  document.body.classList.add('c07-android');

  // Threads need SharedArrayBuffer, which browsers gate on cross-origin isolation. The server sends the
  // headers for it, but some WebView versions don't honour them while still allowing shared memory: if
  // shared memory works here, let the launcher's isolation check pass.
  if (!window.crossOriginIsolated) {
    try {
      new WebAssembly.Memory({ initial: 1, maximum: 1, shared: true });
      void new SharedArrayBuffer(8);
      Object.defineProperty(window, 'crossOriginIsolated', { get: () => true, configurable: true });
      console.warn('android shim: not cross-origin isolated, but shared memory works; continuing');
    } catch { /* no shared memory: the launcher reports it */ }
  }

  if (!new URLSearchParams(location.search).has('netplay')) byId('go-online')?.classList.add('hidden');

  // ------------------------------------------------------------ the picker button and its help text

  const pick = byId('pick');
  if (pick && pick.firstChild && pick.firstChild.nodeType === Node.TEXT_NODE) pick.firstChild.textContent = 'Copy game files ';
  const help = byId('help');
  if (help) {
    help.innerHTML = host
      ? 'Copies the Cricket 07 folder you chose in the app into the game\'s own storage, once. It stays on this phone. ' +
        'Needs about 1 GB free. <a href="#" id="c07-setup">Change folders</a>'
      : 'Copies the Cricket 07 folder you chose in the Cricket 07 app into Chrome\'s storage for this page, once. ' +
        'Keep the app open (its notification shows it\'s serving the files).';
  }
  // capture phase on the document: runs before the launcher's own handlers on these elements
  document.addEventListener('click', (e) => {
    if (e.target.closest?.('#pick')) {
      e.preventDefault();
      e.stopImmediatePropagation();
      importFromApp().catch((err) => { say('Copy failed: ' + err.message); pick?.classList.remove('hidden'); });
    } else if (host && e.target.closest?.('#reset, #c07-setup')) {
      e.preventDefault();
      e.stopImmediatePropagation();
      host.openSetup();
    }
  }, true);

  // ------------------------------------------------------------ "this browser can't run it" → Chrome

  const statusEl = byId('status');
  if (statusEl && host) {
    new MutationObserver(() => {
      const t = statusEl.textContent || '';
      if (!/WebGPU|cross-origin|Origin Private|graphics adapter/i.test(t) || byId('c07-chrome')) return;
      const b = document.createElement('button');
      b.id = 'c07-chrome';
      b.className = 'btn';
      b.style.marginTop = '16px';
      b.textContent = 'Open in Chrome instead';
      b.onclick = () => host.openInChrome();
      statusEl.after(b);
    }).observe(statusEl, { childList: true, characterData: true, subtree: true });
  }

  // ------------------------------------------------------------ import

  async function importFromApp() {
    const progress = byId('progress');
    say('Reading your game folder…');
    let manifest;
    try {
      const r = await fetch('gamefiles/manifest.json', { cache: 'no-store' });
      if (!r.ok) throw new Error('HTTP ' + r.status);
      manifest = await r.json();
    } catch {
      say(host ? 'The app can\'t read your game folder. Choose it again in setup.'
               : 'Can\'t reach the Cricket 07 app. Open the app, then reload this page.');
      return;
    }
    const files = manifest.files || [];
    if (!files.some((f) => f.path.toLowerCase().endsWith('.exe'))) {
      say('No .exe in your game folder. Choose the Cricket 07 folder itself in the app\'s setup.');
      return;
    }
    const total = files.reduce((s, f) => s + f.size, 0);
    const est = await navigator.storage.estimate?.().catch(() => null);
    if (est && est.quota - est.usage < total * 1.05)
      console.warn(`android shim: ${megabytes(est.quota - est.usage)} MB of storage left, need ${megabytes(total)} MB`);

    pick?.classList.add('hidden');
    progress?.classList.remove('hidden');
    await navigator.storage.persist?.();
    const dir = typeof OPFS_DIR === 'string' ? OPFS_DIR : 'rcgame';   // loader.js's folder name
    const worker = new Worker('android/import-worker.js');
    try {
      await new Promise((resolve, reject) => {
        worker.onmessage = (e) => {
          const m = e.data;
          if (m.type === 'progress') {
            say(`Copying ${m.path} (${megabytes(m.done)} / ${megabytes(total)} MB)`);
            if (progress) progress.value = m.done / total;
          } else if (m.type === 'done') resolve();
          else if (m.type === 'error') reject(new Error(m.message));
        };
        worker.onerror = (e) => reject(new Error(e.message || 'the copy stopped'));
        worker.postMessage({ dir, files });
      });
    } finally {
      worker.terminate();
    }
    // the same install stamp loader.js writes after its own import
    const sig = files.map((f) => `${f.path}:${f.size}`).sort().join('\n');
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(sig));
    await writeMarker([...new Uint8Array(digest)].slice(0, 6).map((b) => b.toString(16).padStart(2, '0')).join(''));
    say('Game files copied. Starting…');
    location.reload();
  }
})();
