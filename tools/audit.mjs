import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const ROOT = path.resolve(import.meta.dirname, '..');
const HTML = path.join(ROOT, '课序_V8_完整交互原型.html');
const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const PORT = 9351;
const PROFILE = path.join(ROOT, '.chrome-audit-profile');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
class CDP {
  constructor(ws) { this.ws = ws; this.id = 1; this.pending = new Map(); ws.addEventListener('message', (ev) => { const m = JSON.parse(ev.data); if (m.id && this.pending.has(m.id)) { const p = this.pending.get(m.id); this.pending.delete(m.id); m.error ? p.reject(new Error(m.error.message)) : p.resolve(m.result); } }); }
  static async connect(url) { const ws = new WebSocket(url); await new Promise((res, rej) => { ws.addEventListener('open', res, { once: true }); ws.addEventListener('error', () => rej(new Error('ws fail')), { once: true }); }); return new CDP(ws); }
  send(method, params = {}) { const id = this.id++; return new Promise((res, rej) => { this.pending.set(id, { resolve: res, reject: rej }); this.ws.send(JSON.stringify({ id, method, params })); setTimeout(() => { if (this.pending.has(id)) { this.pending.delete(id); rej(new Error('timeout ' + method)); } }, 20000); }); }
}

const VIEWS = [
  ['home', 'home&expand=1'],
  ['timetable', 'timetable'],
  ['tasks', 'tasks'],
  ['planning', 'planning'],
  ['settings', 'settings'],
  ['settings-course', 'settings&group=course'],
  ['settings-schedule', 'settings&group=schedule'],
  ['settings-appearance', 'settings&group=appearance']
];

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
    await cdp.send('Emulation.setDeviceMetricsOverride', { width: 414, height: 200, deviceScaleFactor: 1, mobile: false });
    for (const [name, query] of VIEWS) {
      await cdp.send('Page.navigate', { url: pathToFileURL(HTML).href + '?shot=1&view=' + query });
      for (let i = 0; i < 100; i += 1) { const r = await cdp.send('Runtime.evaluate', { expression: 'String(!!window.__KEXU_READY)', returnByValue: true }); if (r.result.value === 'true') break; await sleep(100); }
      await sleep(250);
      const expr = `(() => {
        const q = (s) => document.querySelector(s);
        const rect = (n) => { if (!n) return null; const r = n.getBoundingClientRect(); return { x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height), right: Math.round(r.right), bottom: Math.round(r.bottom) }; };
        const clipped = [];
        document.querySelectorAll('.page.active *, .topbar *, .bottomnav *').forEach((n) => {
          const cs = getComputedStyle(n);
          const txt = (n.childElementCount === 0 ? (n.textContent || '').trim() : '');
          if (!txt) return;
          if ((cs.overflow === 'hidden' || cs.overflowX === 'hidden') && n.scrollWidth > n.clientWidth + 2) clipped.push({ txt: txt.slice(0, 24), sw: n.scrollWidth, cw: n.clientWidth });
        });
        const outside = [];
        document.querySelectorAll('.page.active *').forEach((n) => { const r = n.getBoundingClientRect(); if (r.width > 0 && (r.right > 415 || r.left < -1) && getComputedStyle(n).position !== 'fixed') outside.push({ cls: String(n.className || '').slice(0, 28), tag: n.tagName, right: Math.round(r.right), left: Math.round(r.left) }); });
        const hero = q('.hero');
        const fontOf = (n) => n ? getComputedStyle(n).fontSize + '/' + getComputedStyle(n).fontWeight : null;
        const heroName = q('.hero-name'), heroTime = q('.hero-time'), heroMeta = q('.hero-meta'), heroCount = q('.hero-count');
        const periods = [...document.querySelectorAll('.tt-row')].map((n) => Math.round(n.getBoundingClientRect().height));
        const gaps = [...document.querySelectorAll('.tt-gaprow')].map((n) => Math.round(n.getBoundingClientRect().height));
        return {
          view: '${name}',
          docH: document.body.getBoundingClientRect().height | 0,
          hero: hero ? { rect: rect(hero), name: heroName ? heroName.textContent : null, nameFont: fontOf(heroName), time: heroTime ? heroTime.textContent : null, timeFont: fontOf(heroTime), meta: heroMeta ? heroMeta.textContent : null, metaFont: fontOf(heroMeta), count: heroCount ? heroCount.textContent : null, countRect: rect(heroCount) } : null,
          clippedCount: clipped.length, clipped: clipped.slice(0, 6),
          outsideCount: outside.length, outside: outside.slice(0, 8),
          periods, gaps,
          ttHead: q('.tt-head') ? rect(q('.tt-head')) : null,
          ttScroll: q('.tt-scroll') ? rect(q('.tt-scroll')) : null,
          courseBlocks: document.querySelectorAll('.tt-block').length,
          settingsGroups: document.querySelectorAll('.group-row').length
        };
      })()`;
      const r = await cdp.send('Runtime.evaluate', { expression: expr, returnByValue: true });
      console.log(JSON.stringify(r.result.value));
    }
  } finally {
    try { chrome.kill(); } catch (e) { /* ignore */ }
    await sleep(400);
    try { fs.rmSync(PROFILE, { recursive: true, force: true }); } catch (e) { /* ignore */ }
  }
})().catch((e) => { console.error(e); process.exit(1); });
