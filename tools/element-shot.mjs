import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const ROOT = path.resolve(import.meta.dirname, '..');
const HTML = path.join(ROOT, '课序_V8_完整交互原型.html');
const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const PORT = 9361;
const PROFILE = path.join(ROOT, '.chrome-el-profile');
const OUT = path.join(ROOT, 'V8预览长图', '_局部检查');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
class CDP {
  constructor(ws) { this.ws = ws; this.id = 1; this.pending = new Map(); ws.addEventListener('message', (ev) => { const m = JSON.parse(ev.data); if (m.id && this.pending.has(m.id)) { const p = this.pending.get(m.id); this.pending.delete(m.id); m.error ? p.reject(new Error(m.error.message)) : p.resolve(m.result); } }); }
  static async connect(url) { const ws = new WebSocket(url); await new Promise((res, rej) => { ws.addEventListener('open', res, { once: true }); ws.addEventListener('error', () => rej(new Error('ws fail')), { once: true }); }); return new CDP(ws); }
  send(method, params = {}) { const id = this.id++; return new Promise((res, rej) => { this.pending.set(id, { resolve: res, reject: rej }); this.ws.send(JSON.stringify({ id, method, params })); setTimeout(() => { if (this.pending.has(id)) { this.pending.delete(id); rej(new Error('timeout ' + method)); } }, 20000); }); }
}
const TARGETS = [
  { name: '模块图标特写.png', view: 'home&expand=1', selector: '.module:nth-of-type(2)', pad: 10, scale: 3 },
  { name: '首页统计行特写.png', view: 'home&expand=1', selector: '.home-stats', pad: 14, scale: 3 },
  { name: '课表色块特写.png', view: 'timetable&wide=1', selector: '.tt-block', pad: 8, scale: 3, wide: true }
];
(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  try { fs.rmSync(PROFILE, { recursive: true, force: true }); } catch (e) { /* ignore */ }
  const chrome = spawn(CHROME, ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`, '--no-first-run', '--no-default-browser-check', '--disable-gpu', '--hide-scrollbars', '--disable-breakpad', '--allow-file-access-from-files', 'about:blank'], { stdio: 'ignore' });
  try {
    let version = null;
    for (let i = 0; i < 100 && !version; i += 1) { try { version = await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json(); } catch (e) { await sleep(120); } }
    const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
    const page = list.find((t) => t.type === 'page');
    const cdp = await CDP.connect(page.webSocketDebuggerUrl);
    await cdp.send('Page.enable'); await cdp.send('Runtime.enable');
    for (const t of TARGETS) {
      const width = t.wide ? 744 : 414;
      await cdp.send('Emulation.setDeviceMetricsOverride', { width, height: 400, deviceScaleFactor: 2, mobile: false });
      await cdp.send('Page.navigate', { url: pathToFileURL(HTML).href + '?shot=1&view=' + t.view });
      for (let i = 0; i < 100; i += 1) { const r = await cdp.send('Runtime.evaluate', { expression: 'String(!!window.__KEXU_READY)', returnByValue: true }); if (r.result.value === 'true') break; await sleep(100); }
      await sleep(250);
      const rect = await cdp.send('Runtime.evaluate', {
        expression: `(() => { const n = document.querySelector(${JSON.stringify(t.selector)}); if (!n) return null; const r = n.getBoundingClientRect(); return { x: r.left, y: r.top, w: r.width, h: r.height, scrollY: window.scrollY }; })()`,
        returnByValue: true
      });
      const r = rect.result.value;
      if (!r) { console.log('NOT FOUND ' + t.name); continue; }
      const clip = { x: Math.max(0, r.x - t.pad), y: Math.max(0, r.y - t.pad), width: Math.min(width, r.w + t.pad * 2), height: Math.min(1600, r.h + t.pad * 2), scale: t.scale };
      const shot = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true, clip });
      fs.writeFileSync(path.join(OUT, t.name), Buffer.from(shot.data, 'base64'));
      console.log('OK ' + t.name + ' ' + Math.round(clip.width) + '×' + Math.round(clip.height) + ' @' + t.scale);
    }
  } finally {
    try { chrome.kill(); } catch (e) { /* ignore */ }
    await sleep(300);
    try { fs.rmSync(PROFILE, { recursive: true, force: true }); } catch (e) { /* ignore */ }
  }
})().catch((e) => { console.error(e); process.exit(1); });
