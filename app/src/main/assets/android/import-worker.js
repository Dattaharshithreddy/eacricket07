// Streams every file from the app's loopback server (../gamefiles/<path>) into OPFS/<dir>/<path>.
// Sync access handles (worker-only) write in place without buffering a whole file in memory, which matters
// for files of a few hundred MB on a phone.
'use strict';

self.onmessage = async (e) => {
  const { dir, files } = e.data;
  try {
    const root = await navigator.storage.getDirectory();
    try { await root.removeEntry(dir, { recursive: true }); } catch { /* first install */ }
    const base = await root.getDirectoryHandle(dir, { create: true });
    let done = 0;
    let lastReport = 0;
    for (const f of files) {
      const parts = f.path.split('/');
      let d = base;
      for (const p of parts.slice(0, -1)) d = await d.getDirectoryHandle(p, { create: true });
      const fh = await d.getFileHandle(parts[parts.length - 1], { create: true });
      const h = await fh.createSyncAccessHandle();
      try {
        h.truncate(0);
        const res = await fetch('../gamefiles/' + parts.map(encodeURIComponent).join('/'), { cache: 'no-store' });
        if (!res.ok) throw new Error(`${f.path}: HTTP ${res.status}`);
        const reader = res.body.getReader();
        let at = 0;
        for (;;) {
          const { done: end, value } = await reader.read();
          if (end) break;
          let off = 0;
          while (off < value.length) off += h.write(value.subarray(off), { at: at + off });
          at += value.length;
          done += value.length;
          const now = performance.now();
          if (now - lastReport > 200) { lastReport = now; postMessage({ type: 'progress', path: f.path, done }); }
        }
        if (at !== f.size) throw new Error(`${f.path}: got ${at} of ${f.size} bytes`);
        h.flush();
      } finally {
        h.close();
      }
      postMessage({ type: 'progress', path: f.path, done });
    }
    postMessage({ type: 'done' });
  } catch (err) {
    postMessage({ type: 'error', message: String((err && err.message) || err) });
  }
};
