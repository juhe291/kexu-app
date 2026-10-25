import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const ROOT = path.resolve(import.meta.dirname, '..');
const HTML = path.join(ROOT, '课序_V8_完整交互原型.html');
const OUT = path.join(ROOT, 'V8预览长图');
const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const PORT = 9337;
const PROFILE = path.join(ROOT, '.chrome-shot-profile');
fs.mkdirSync(OUT, { recursive: true });
if (!fs.existsSync(CHROME)) { console.error('未找到 Chrome：' + CHROME); process.exit(1); }

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const fileUrl = pathToFileURL(HTML).href;

class CDP {
  constructor(ws) {
    this.ws = ws; this.nextId = 1; this.pending = new Map();
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(ev.data);
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        if (msg.error) reject(new Error(msg.error.message)); else resolve(msg.result);
      }
    });
  }
  static async connect(url) {
    const ws = new WebSocket(url);
    await new Promise((resolve, reject) => {
      ws.addEventListener('open', resolve, { once: true });
      ws.addEventListener('error', () => reject(new Error('WebSocket 连接失败')), { once: true });
    });
    return new CDP(ws);
  }
  send(method, params = {}) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
      setTimeout(() => { if (this.pending.has(id)) { this.pending.delete(id); reject(new Error('CDP 超时：' + method)); } }, 30000);
    });
  }
  close() { try { this.ws.close(); } catch (e) { /* ignore */ } }
}

async function waitForChrome() {
  for (let i = 0; i < 100; i += 1) {
    try {
      const res = await fetch(`http://127.0.0.1:${PORT}/json/version`);
      if (res.ok) return await res.json();
    } catch (e) { /* 启动中 */ }
    await sleep(120);
  }
  throw new Error('Chrome 调试端口未就绪');
}

async function capture(cdp, url, width, height, opts = {}) {
  const minHeight = opts.minHeight || 200;
  const scale = opts.scale || 2;
  const mobile = !!opts.mobile;
  await cdp.send('Emulation.setDeviceMetricsOverride', { width, height: minHeight, deviceScaleFactor: scale, mobile });
  await cdp.send('Page.navigate', { url });
  for (let i = 0; i < 120; i += 1) {
    try {
      const r = await cdp.send('Runtime.evaluate', { expression: 'document.readyState + "|" + String(!!window.__KEXU_READY) + "|" + document.fonts.status', returnByValue: true });
      if (r.result.value === 'complete|true|loaded') break;
    } catch (e) { /* 页面加载中 */ }
    await sleep(120);
  }
  await sleep(opts.wait || 350);
  let contentHeight = 0;
  for (let i = 0; i < 4; i += 1) {
    const r = await cdp.send('Runtime.evaluate', {
      expression: `(() => { const dev = document.querySelector('.device'); const stage = document.querySelector('.stage'); const h = Math.max(document.body.getBoundingClientRect().height, dev ? dev.getBoundingClientRect().height : 0, stage ? stage.getBoundingClientRect().height : 0); return Math.ceil(h); })()`,
      returnByValue: true
    });
    contentHeight = Number(r.result.value) || 0;
    await cdp.send('Emulation.setDeviceMetricsOverride', { width, height: Math.min(Math.max(contentHeight, minHeight), 8000), deviceScaleFactor: scale, mobile });
    await sleep(180);
  }
  const shot = await cdp.send('Page.captureScreenshot', {
    format: 'png', fromSurface: true, captureBeyondViewport: true,
    clip: { x: 0, y: 0, width, height: contentHeight, scale }
  });
  fs.writeFileSync(path.join(OUT, opts.name), Buffer.from(shot.data, 'base64'));
  console.log(`OK ${opts.name}  ${width}×${contentHeight} CSS px @${scale}x`);
}

const SHOTS = [
  { name: '00_桌面总览.png', url: fileUrl, width: 1280, height: 1000, opts: { scale: 1, mobile: false, minHeight: 1000 } },
  { name: '01_今天.png', url: fileUrl + '?shot=1&view=home&expand=1', width: 414, opts: { mobile: true } },
  { name: '02_课表.png', url: fileUrl + '?shot=1&view=timetable&wide=1', width: 744, opts: { mobile: false } },
  { name: '03_待办.png', url: fileUrl + '?shot=1&view=tasks', width: 414, opts: { mobile: true } },
  { name: '04_智能规划.png', url: fileUrl + '?shot=1&view=planning', width: 414, opts: { mobile: true } },
  { name: '05_设置_总览.png', url: fileUrl + '?shot=1&view=settings', width: 414, opts: { mobile: true } },
  { name: '06_设置_课程与学期.png', url: fileUrl + '?shot=1&view=settings&group=course', width: 414, opts: { mobile: true } },
  { name: '07_设置_日程与规划.png', url: fileUrl + '?shot=1&view=settings&group=schedule', width: 414, opts: { mobile: true } },
  { name: '08_设置_提醒与闹钟.png', url: fileUrl + '?shot=1&view=settings&group=reminders', width: 414, opts: { mobile: true } },
  { name: '09_设置_外观与主题.png', url: fileUrl + '?shot=1&view=settings&group=appearance', width: 414, opts: { mobile: true } },
  { name: '10_设置_数据管理.png', url: fileUrl + '?shot=1&view=settings&group=data', width: 414, opts: { mobile: true } },
  { name: '11_今天_深色.png', url: fileUrl + '?shot=1&view=home&expand=1&theme=dark', width: 414, opts: { mobile: true } },
  { name: '12_课表_深色.png', url: fileUrl + '?shot=1&view=timetable&theme=dark&wide=1', width: 744, opts: { mobile: false } }
];

(async () => {
  try { fs.rmSync(PROFILE, { recursive: true, force: true }); } catch (e) { /* ignore */ }
  const chrome = spawn(CHROME, [
    '--headless=new',
    `--remote-debugging-port=${PORT}`,
    `--user-data-dir=${PROFILE}`,
    '--no-first-run', '--no-default-browser-check', '--disable-gpu', '--hide-scrollbars',
    '--disable-breakpad', '--disable-dev-shm-usage', '--force-color-profile=srgb',
    '--allow-file-access-from-files', 'about:blank'
  ], { stdio: 'ignore' });
  let failed = 0;
  try {
    await waitForChrome();
    const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
    const page = list.find((t) => t.type === 'page');
    if (!page) throw new Error('没有可用的页面目标');
    const cdp = await CDP.connect(page.webSocketDebuggerUrl);
    await cdp.send('Page.enable');
    await cdp.send('Runtime.enable');
    for (const shot of SHOTS) {
      const { name, url, width, height, opts } = shot;
      try { await capture(cdp, url, width, height || 900, { ...opts, name }); }
      catch (e) { failed += 1; console.error('FAIL ' + name + ' — ' + e.message); }
    }
    cdp.close();
  } finally {
    try { chrome.kill(); } catch (e) { /* ignore */ }
    await sleep(500);
    try { fs.rmSync(PROFILE, { recursive: true, force: true }); } catch (e) { /* ignore */ }
  }
  console.log(failed ? `完成，${failed} 张失败` : '全部长图导出完成');
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error('渲染失败：' + e.message); process.exit(1); });
