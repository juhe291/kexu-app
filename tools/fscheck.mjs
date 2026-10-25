import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const ROOT = path.resolve(import.meta.dirname, '..');
const HTML = path.join(ROOT, '课序_V8_完整交互原型.html');
const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const PORT = 9358;
const PROFILE = path.join(ROOT, '.chrome-fs-profile');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
class CDP {
  constructor(ws) { this.ws = ws; this.id = 1; this.pending = new Map(); ws.addEventListener('message', (ev) => { const m = JSON.parse(ev.data); if (m.id && this.pending.has(m.id)) { const p = this.pending.get(m.id); this.pending.delete(m.id); m.error ? p.reject(new Error(m.error.message)) : p.resolve(m.result); } }); }
  static async connect(url) { const ws = new WebSocket(url); await new Promise((res, rej) => { ws.addEventListener('open', res, { once: true }); ws.addEventListener('error', () => rej(new Error('ws fail')), { once: true }); }); return new CDP(ws); }
  send(method, params = {}) { const id = this.id++; return new Promise((res, rej) => { this.pending.set(id, { resolve: res, reject: rej }); this.ws.send(JSON.stringify({ id, method, params })); setTimeout(() => { if (this.pending.has(id)) { this.pending.delete(id); rej(new Error('timeout ' + method)); } }, 20000); }); }
}
const VIEWS = [['home', 'home&expand=1'], ['timetable', 'timetable&wide=1'], ['tasks', 'tasks'], ['planning', 'planning'], ['settings-course', 'settings&group=course'], ['settings-schedule', 'settings&group=schedule']];
(async () => {
  try { fs.rmSync(PROFILE, { recursive: true, force: true }); } catch (e) { /* ignore */ }
  const chrome = spawn(CHROME, ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`, '--no-first-run', '--no-default-browser-check', '--disable-gpu', '--hide-scrollbars', '--disable-breakpad', '--allow-file-access-from-files', 'about:blank'], { stdio: 'ignore' });
  let fail = 0;
  try {
    let version = null;
    for (let i = 0; i < 100 && !version; i += 1) { try { version = await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json(); } catch (e) { await sleep(120); } }
    const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
    const page = list.find((t) => t.type === 'page');
    const cdp = await CDP.connect(page.webSocketDebuggerUrl);
    await cdp.send('Page.enable'); await cdp.send('Runtime.enable');
    await cdp.send('Emulation.setDeviceMetricsOverride', { width: 414, height: 820, deviceScaleFactor: 1, mobile: false });
    const measure = async () => (await cdp.send('Runtime.evaluate', {
      expression: `(() => {
        let clipped = 0, off = 0;
        document.querySelectorAll('.page.active *').forEach(n => {
          if (n.closest('.tt-block')) return;
          const cs = getComputedStyle(n);
          const txt = n.childElementCount === 0 ? (n.textContent || '').trim() : '';
          if (txt && (cs.overflow === 'hidden' || cs.overflowX === 'hidden' || cs.textOverflow === 'ellipsis') && n.scrollWidth > n.clientWidth + 2) clipped++;
          const r = n.getBoundingClientRect();
          if (r.width > 0 && r.right > 415 && cs.position !== 'fixed') off++;
        });
        return { docH: Math.round(document.body.getBoundingClientRect().height), clipped, off };
      })()`, returnByValue: true
    })).result.value;
    for (const [name, query] of VIEWS) {
      for (const fs of [16, 20]) {
        await cdp.send('Page.navigate', { url: pathToFileURL(HTML).href + '?shot=1&view=' + query });
        for (let i = 0; i < 100; i += 1) { const r = await cdp.send('Runtime.evaluate', { expression: 'String(!!window.__KEXU_READY)', returnByValue: true }); if (r.result.value === 'true') break; await sleep(100); }
        await sleep(150);
        await cdp.send('Runtime.evaluate', { expression: `document.documentElement.style.fontSize='${fs}px'` });
        await sleep(250);
        const m = await measure();
        const ok = m.clipped === 0;
        if (!ok) fail += 1;
        console.log(`${ok ? 'PASS' : 'FAIL'} ${name} @root ${fs}px  docH=${m.docH}  clipped=${m.clipped}  overflowX=${m.off}`);
      }
    }
  } finally {
    try { chrome.kill(); } catch (e) { /* ignore */ }
    await sleep(300);
    try { fs.rmSync(PROFILE, { recursive: true, force: true }); } catch (e) { /* ignore */ }
  }
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
