import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const ROOT = path.resolve(import.meta.dirname, '..');
const HTML = path.join(ROOT, '课序_V8_完整交互原型.html');
const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const PORT = 9346;
const PROFILE = path.join(ROOT, '.chrome-probe-profile');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
class CDP {
  constructor(ws) { this.ws = ws; this.id = 1; this.pending = new Map(); ws.addEventListener('message', (ev) => { const m = JSON.parse(ev.data); if (m.id && this.pending.has(m.id)) { const p = this.pending.get(m.id); this.pending.delete(m.id); m.error ? p.reject(new Error(m.error.message)) : p.resolve(m.result); } }); }
  static async connect(url) { const ws = new WebSocket(url); await new Promise((res, rej) => { ws.addEventListener('open', res, { once: true }); ws.addEventListener('error', () => rej(new Error('ws fail')), { once: true }); }); return new CDP(ws); }
  send(method, params = {}) { const id = this.id++; return new Promise((res, rej) => { this.pending.set(id, { resolve: res, reject: rej }); this.ws.send(JSON.stringify({ id, method, params })); setTimeout(() => { if (this.pending.has(id)) { this.pending.delete(id); rej(new Error('timeout ' + method)); } }, 20000); }); }
}
(async () => {
  try { fs.rmSync(PROFILE, { recursive: true, force: true }); } catch (e) { /* ignore */ }
  const chrome = spawn(CHROME, ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`, '--no-first-run', '--no-default-browser-check', '--disable-gpu', '--hide-scrollbars', '--disable-breakpad', '--allow-file-access-from-files', 'about:blank'], { stdio: 'ignore' });
  try {
    let version = null;
    for (let i = 0; i < 100 && !version; i += 1) { try { version = await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json(); } catch (e) { await sleep(120); } }
    const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
    const page = list.find((t) => t.type === 'page');
    const cdp = await CDP.connect(page.webSocketDebuggerUrl);
    await cdp.send('Page.enable'); await cdp.send('Runtime.enable');
    await cdp.send('Emulation.setDeviceMetricsOverride', { width: 414, height: 200, deviceScaleFactor: 2, mobile: true });
    await cdp.send('Page.navigate', { url: pathToFileURL(HTML).href + '?shot=1&view=timetable' });
    for (let i = 0; i < 100; i += 1) { const r = await cdp.send('Runtime.evaluate', { expression: 'String(!!window.__KEXU_READY)', returnByValue: true }); if (r.result.value === 'true') break; await sleep(120); }
    await sleep(300);
    await cdp.send('Emulation.setDeviceMetricsOverride', { width: 414, height: 1585, deviceScaleFactor: 2, mobile: true });
    await sleep(300);
    const expr = `(() => {
      const vh = window.innerHeight, out = [];
      document.querySelectorAll('body *').forEach(n => {
        const r = n.getBoundingClientRect();
        if (r.height > 0 && r.bottom > vh + 2) out.push({ cls: (n.className||'').toString().slice(0,40), tag: n.tagName, top: Math.round(r.top), bottom: Math.round(r.bottom), h: Math.round(r.height), pos: getComputedStyle(n).position });
      });
      out.sort((a,b)=>b.bottom-a.bottom);
      return { vh, doc: document.documentElement.scrollHeight, deH: getComputedStyle(document.documentElement).height, bodyH: getComputedStyle(document.body).height, bodyRect: Math.round(document.body.getBoundingClientRect().height), offenders: out.slice(0,12) };
    })()`;
    const r = await cdp.send('Runtime.evaluate', { expression: expr, returnByValue: true });
    console.log(JSON.stringify(r.result.value, null, 2));
  } finally {
    try { chrome.kill(); } catch (e) { /* ignore */ }
    await sleep(400);
    try { fs.rmSync(PROFILE, { recursive: true, force: true }); } catch (e) { /* ignore */ }
  }
})().catch((e) => { console.error(e); process.exit(1); });
