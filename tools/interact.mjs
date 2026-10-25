import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const ROOT = path.resolve(import.meta.dirname, '..');
const HTML = path.join(ROOT, '课序_V8_完整交互原型.html');
const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const PORT = 9355;
const PROFILE = path.join(ROOT, '.chrome-interact-profile');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const URL_BASE = pathToFileURL(HTML).href;

class CDP {
  constructor(ws) { this.ws = ws; this.id = 1; this.pending = new Map(); ws.addEventListener('message', (ev) => { const m = JSON.parse(ev.data); if (m.id && this.pending.has(m.id)) { const p = this.pending.get(m.id); this.pending.delete(m.id); m.error ? p.reject(new Error(m.error.message)) : p.resolve(m.result); } }); }
  static async connect(url) { const ws = new WebSocket(url); await new Promise((res, rej) => { ws.addEventListener('open', res, { once: true }); ws.addEventListener('error', () => rej(new Error('ws fail')), { once: true }); }); return new CDP(ws); }
  send(method, params = {}) { const id = this.id++; return new Promise((res, rej) => { this.pending.set(id, { resolve: res, reject: rej }); this.ws.send(JSON.stringify({ id, method, params })); setTimeout(() => { if (this.pending.has(id)) { this.pending.delete(id); rej(new Error('timeout ' + method)); } }, 20000); }); }
}

(async () => {
  try { fs.rmSync(PROFILE, { recursive: true, force: true }); } catch (e) { /* ignore */ }
  const chrome = spawn(CHROME, ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`, '--no-first-run', '--no-default-browser-check', '--disable-gpu', '--hide-scrollbars', '--disable-breakpad', '--allow-file-access-from-files', 'about:blank'], { stdio: 'ignore' });
  let pass = 0, fail = 0;
  const ok = (name, cond, detail = '') => { if (cond) { pass += 1; console.log('PASS ' + name); } else { fail += 1; console.log('FAIL ' + name + (detail ? ' — ' + detail : '')); } };
  try {
    let version = null;
    for (let i = 0; i < 100 && !version; i += 1) { try { version = await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json(); } catch (e) { await sleep(120); } }
    const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
    const page = list.find((t) => t.type === 'page');
    const cdp = await CDP.connect(page.webSocketDebuggerUrl);
    await cdp.send('Page.enable'); await cdp.send('Runtime.enable');
    await cdp.send('Emulation.setDeviceMetricsOverride', { width: 414, height: 820, deviceScaleFactor: 1, mobile: false });
    const load = async (suffix = '') => {
      await cdp.send('Page.navigate', { url: URL_BASE + suffix });
      for (let i = 0; i < 120; i += 1) { const r = await cdp.send('Runtime.evaluate', { expression: 'String(!!window.__KEXU_READY)', returnByValue: true }); if (r.result.value === 'true') break; await sleep(100); }
      await sleep(220);
    };
    const evalJson = async (expression) => { const r = await cdp.send('Runtime.evaluate', { expression: `JSON.stringify((() => { ${expression} })())`, returnByValue: true }); try { return JSON.parse(r.result.value); } catch (e) { return null; } };
    const click = async (selector, idx = 0) => evalJson(`const ns = document.querySelectorAll(${JSON.stringify(selector)}); if (!ns[${idx}]) return false; ns[${idx}].click(); return true;`);
    const setVal = async (selector, idx, value) => evalJson(`const ns = document.querySelectorAll(${JSON.stringify(selector)}); if (!ns[${idx}]) return false; ns[${idx}].value = ${JSON.stringify(value)}; ns[${idx}].dispatchEvent(new Event('change', { bubbles: true })); return true;`);

    await load();
    /* ---- 首页层次 ---- */
    const home = await evalJson(`
      const g = (s) => document.querySelector(s);
      const cs = (s) => { const n = g(s); return n ? getComputedStyle(n) : null; };
      const hero = g('.hero'); const heroR = hero.getBoundingClientRect();
      return {
        heroName: g('.hero-name') ? g('.hero-name').textContent : null,
        heroWidth: Math.round(heroR.width),
        nameFont: cs('.hero-name') ? cs('.hero-name').fontSize + '/' + cs('.hero-name').fontWeight : null,
        timeFont: cs('.hero-time') ? cs('.hero-time').fontSize : null,
        metaFont: cs('.hero-meta') ? cs('.hero-meta').fontSize : null,
        count: g('.hero-count') ? g('.hero-count').textContent : null,
        heroBg: cs('.hero') ? cs('.hero').getPropertyValue('--tone-bg').trim() : null,
        moduleOpen: [...document.querySelectorAll('.module')].map(n => n.classList.contains('open')),
        clipped: [...document.querySelectorAll('.page.active *')].filter(n => n.childElementCount === 0 && n.textContent.trim() && getComputedStyle(n).overflow === 'hidden' && n.scrollWidth > n.clientWidth + 2).length
      };`);
    ok('首页下一节课程为计算机网络', home.heroName === '计算机网络', JSON.stringify(home.heroName));
    ok('即将开始卡片占满内容宽度', home.heroWidth >= 380, 'width=' + home.heroWidth);
    ok('课程名字号 32sp 加粗', home.nameFont === '32px/820', home.nameFont);
    ok('时间字号 19sp 层次次之', home.timeFont && Math.abs(parseFloat(home.timeFont) - 19) < 0.5, home.timeFont);
    ok('地点辅助字号 13sp', home.metaFont && Math.abs(parseFloat(home.metaFont) - 13) < 0.5, home.metaFont);
    ok('显示倒计时', /分钟后|小时后|即将开始/.test(home.count || ''), home.count);
    ok('默认展开今日日程与截止提醒', home.moduleOpen[0] === true && home.moduleOpen[1] === true && home.moduleOpen[2] === false, JSON.stringify(home.moduleOpen));
    ok('首页无明显文字裁切', home.clipped === 0, String(home.clipped));
    const design = await evalJson(`
      const stats = document.querySelector('.home-stats');
      return {
        statsText: stats ? stats.innerText.replace(/\\s+/g, ' ').trim() : null,
        statColors: [...document.querySelectorAll('.home-stats b')].map(n => getComputedStyle(n).color),
        seps: document.querySelectorAll('.home-stats .sep').length,
        svgIcons: document.querySelectorAll('.module-ico svg').length,
        iconText: [...document.querySelectorAll('.module-ico')].map(n => n.textContent.trim()).filter(Boolean).length,
        footnotes: document.querySelectorAll('.page.active .note, .page.active .lead, .page.active .note-box').length,
        iconBg: document.querySelector('.module-ico') ? getComputedStyle(document.querySelector('.module-ico')).backgroundColor : null
      };`);
    ok('统计数据为单行且包含三项指标', /门课/.test(design.statsText || '') && /项任务/.test(design.statsText || '') && /小时/.test(design.statsText || '') && design.seps === 2, JSON.stringify(design.statsText));
    ok('三个统计数字使用不同颜色', new Set(design.statColors).size >= 3, JSON.stringify(design.statColors));
    ok('折叠模块使用 SVG 图标而非文字', design.svgIcons >= 5 && design.iconText === 0, JSON.stringify({ svgIcons: design.svgIcons, iconText: design.iconText }));
    ok('模块图标没有彩色底块', design.iconBg === 'rgba(0, 0, 0, 0)' || design.iconBg === 'transparent', String(design.iconBg));
    ok('首页不残留说明性脚注', design.footnotes === 0, String(design.footnotes));

    /* ---- 设置中的使用说明弹窗 ---- */
    await click('[data-nav="settings"]'); await sleep(250);
    const hasAbout = await evalJson(`const b = document.querySelector('.about-link'); if (!b) return false; b.click(); return true;`);
    await sleep(250);
    const about = await evalJson(`return { title: document.querySelector('#modalTitle') ? document.querySelector('#modalTitle').textContent : null, rows: document.querySelectorAll('.about-row').length, visible: !document.querySelector('#modalBackdrop').hidden };`);
    ok('设置内提供使用说明弹窗', hasAbout === true && about.visible === true && about.title === '使用说明与关于' && about.rows >= 5, JSON.stringify(about));
    await evalJson(`document.querySelector('#modalClose').click(); return true;`);
    await sleep(150);
    await click('[data-nav="home"]'); await sleep(200);

    /* ---- 课表对齐与日期联动 ---- */
    await click('[data-nav="timetable"]'); await sleep(300);
    const tt = await evalJson(`
      const head = [...document.querySelectorAll('.tt-day')].map(n => { const r = n.getBoundingClientRect(); return { x: Math.round(r.left), w: Math.round(r.width) }; });
      const row = document.querySelector('.tt-row');
      const cells = row ? [...row.querySelectorAll('.tt-cell')].map(n => { const r = n.getBoundingClientRect(); return { x: Math.round(r.left), w: Math.round(r.width) }; }) : [];
      const sc = document.querySelector('.tt-scroll');
      const headEl = document.querySelector('.tt-head');
      const before = { headTop: Math.round(headEl.getBoundingClientRect().top), axisLeft: Math.round(document.querySelector('.tt-axis').getBoundingClientRect().left), scrollLeft: Math.round(sc.scrollLeft) };
      sc.scrollLeft = 240; sc.scrollTop = 260;
      sc.dispatchEvent(new Event('scroll'));
      const after = { headTop: Math.round(headEl.getBoundingClientRect().top), axisLeft: Math.round(document.querySelector('.tt-axis').getBoundingClientRect().left), scrollLeft: Math.round(sc.scrollLeft) };
      return { head, cells, before, after, range: document.querySelector('.tt-range').textContent, month: document.querySelector('.tt-month').textContent, weekLabel: document.querySelector('.tt-weeklabel').textContent, gridW: Math.round(document.querySelector('.tt-body').getBoundingClientRect().width), scrollW: Math.round(sc.getBoundingClientRect().width) };`);
    const alignOk = tt.head.length === 7 && tt.cells.length === 7 && tt.head.every((h, i) => Math.abs(h.x - tt.cells[i].x) <= 1 && Math.abs(h.w - tt.cells[i].w) <= 1);
    ok('日期栏 7 列与网格列严格对齐', alignOk, JSON.stringify({ head: tt.head, cells: tt.cells }));
    ok('纵向滚动后日期栏保持固定', tt.after.headTop === tt.before.headTop, `before=${tt.before.headTop} after=${tt.after.headTop}`);
    ok('横向滚动后左侧节次轴保持可见', tt.after.axisLeft === tt.before.axisLeft, `before=${tt.before.axisLeft} after=${tt.after.axisLeft}`);
    ok('跨月周显示两个月份', /10 月 — 11 月|10 月—11 月/.test(tt.month), tt.month);
    ok('教学周与周范围正确', tt.weekLabel.includes('第 8 教学周') && tt.range.includes('10 月 26 日') && tt.range.includes('11 月 1 日'), tt.weekLabel + ' / ' + tt.range);
    ok('完整一周列宽大于手机视口（可横向滚动）', tt.gridW > tt.scrollW, `grid=${tt.gridW} view=${tt.scrollW}`);
    const gaps = await evalJson(`return { labels: [...document.querySelectorAll('.tt-gaprow .gap-tag')].map(n => n.textContent), heights: [...document.querySelectorAll('.tt-gaprow')].map(n => Math.round(n.getBoundingClientRect().height)) };`);
    ok('空档标签区分午休与晚餐', gaps.labels.includes('午休') && gaps.labels.includes('晚餐'), JSON.stringify(gaps.labels));
    ok('大空档显示高度被压缩', gaps.heights.length > 0 && Math.max(...gaps.heights) <= 32, JSON.stringify(gaps.heights));

    /* ---- 点击日期高亮 ---- */
    await evalJson(`document.querySelector('.tt-scroll').scrollLeft = 0; return true;`);
    await click('.tt-day', 3); await sleep(250);
    const sel = await evalJson(`return { selDays: document.querySelectorAll('.tt-day.sel').length, selCells: document.querySelectorAll('.tt-cell.sel').length, headDate: document.querySelectorAll('.tt-day b')[3] ? document.querySelectorAll('.tt-day b')[3].textContent : null };`);
    ok('点击日期后仅该列高亮', sel.selDays === 1 && sel.selCells >= 12, JSON.stringify(sel));

    /* ---- 切换上一周，日期与内容同步 ---- */
    const weekBefore = await evalJson(`return { firstDate: document.querySelector('.tt-day b').textContent, range: document.querySelector('.tt-range').textContent };`);
    await click('.tt-nav .ghost.icon', 0); await sleep(300);
    const weekAfter = await evalJson(`return { firstDate: document.querySelector('.tt-day b').textContent, range: document.querySelector('.tt-range').textContent, cur: getComputedStyle(document.querySelector('.tt-day.sel') || document.createElement('div')).backgroundColor };`);
    ok('切换上一周后日期与周范围同步更新', weekBefore.firstDate !== weekAfter.firstDate && weekBefore.range !== weekAfter.range, JSON.stringify({ weekBefore, weekAfter }));

    /* ---- 课程颜色跨页面同步 + 持久化 ---- */
    await click('.tt-nav .ghost', 1); await sleep(250); // 本周
    await click('[data-nav="settings"]'); await sleep(250);
    await click('.group-row', 0); await sleep(250);
    await click('.color-row', 0); await sleep(300);
    const chosen = await evalJson(`const btns = document.querySelectorAll('.swatch-grid button'); const c = window.__KEXU.state.courses[0].color; btns[4].click(); return { chosen: window.__KEXU.state.courses[0].color, old: ${JSON.stringify(null)}, swatches: btns.length };`);
    await sleep(300);
    await click('.modal-close'); await sleep(200);
    await click('[data-nav="home"]'); await sleep(300);
    const homeTone = await evalJson(`const rows = [...document.querySelectorAll('.agenda-card')]; const row = rows.find(n => n.textContent.includes('数据结构')); return row ? getComputedStyle(row).getPropertyValue('--tone').trim() : null;`);
    await click('[data-nav="timetable"]'); await sleep(300);
    const ttTone = await evalJson(`const b = [...document.querySelectorAll('.tt-block')].find(n => n.textContent.includes('数据结构')); return b ? getComputedStyle(b).getPropertyValue('--tone').trim() : null;`);
    ok('修改课程颜色后首页与课表同步', homeTone && ttTone && homeTone.toLowerCase() === ttTone.toLowerCase() && homeTone.toLowerCase() === chosen.chosen.toLowerCase(), JSON.stringify({ homeTone, ttTone, chosen: chosen.chosen }));
    await click('[data-nav="tasks"]'); await sleep(250);
    const taskTone = await evalJson(`const c = [...document.querySelectorAll('.task-meta .course')].find(n => n.textContent.includes('数据结构')); return c ? getComputedStyle(c).getPropertyValue('--tone').trim() : null;`);
    await click('[data-nav="planning"]'); await sleep(250);
    const planTone = await evalJson(`const n = document.querySelector('.plan-focus'); return n ? getComputedStyle(n).getPropertyValue('--tone').trim() : null;`);
    ok('课程颜色同步到待办与规划页', taskTone && planTone && taskTone.toLowerCase() === chosen.chosen.toLowerCase() && planTone.toLowerCase() === chosen.chosen.toLowerCase(), JSON.stringify({ taskTone, planTone, chosen: chosen.chosen }));
    const stored = await evalJson(`return JSON.parse(localStorage.getItem('kexu-v8-courses'));`);
    ok('课程颜色写入本地存储', Array.isArray(stored) && stored[0].color.toLowerCase() === chosen.chosen.toLowerCase(), JSON.stringify(stored && stored[0] && stored[0].color));
    await load();
    const persisted = await evalJson(`const rows = [...document.querySelectorAll('.agenda-card')]; const row = rows.find(n => n.textContent.includes('数据结构')); return { tone: row ? getComputedStyle(row).getPropertyValue('--tone').trim() : null };`);
    ok('重新打开后课程颜色保持', persisted.tone && persisted.tone.toLowerCase() === chosen.chosen.toLowerCase(), JSON.stringify(persisted.tone));

    /* ---- 节次时间轴修改后重排 ---- */
    await click('[data-nav="settings"]'); await sleep(200);
    await click('.group-row', 0); await sleep(250);
    const periodWidgets = await evalJson(`const items = [...document.querySelectorAll('.period-item')]; return { count: items.length, before: window.__KEXU.state.periods[0] };`);
    await setVal('.period-item .field', 0, '08:00');
    await setVal('.period-item .field', 1, '08:45');
    await sleep(150);
    const saveBtn = await evalJson(`const btns = [...document.querySelectorAll('button')]; const b = btns.find(x => x.textContent === '保存时间轴'); if (b) { b.click(); return true; } return false;`);
    await sleep(350);
    const afterSave = await evalJson(`return { p0: window.__KEXU.state.periods[0], saved: JSON.parse(localStorage.getItem('kexu-v8-periods'))[0] };`);
    ok('修改节次时间并保存生效', afterSave.p0.from === '08:00' && afterSave.p0.to === '08:45' && afterSave.saved.from === '08:00', JSON.stringify(afterSave));
    await click('[data-nav="timetable"]'); await sleep(300);
    const layoutCheck = await evalJson(`
      const st = window.__KEXU.state; const block = [...document.querySelectorAll('.tt-block')].find(n => n.textContent.includes('计算机网络'));
      const rect = window.__KEXU.CORE.meetingRect(st.periods, 1, 2);
      return { styleTop: block ? parseFloat(block.style.top) : null, expected: Math.round((rect.top + 3) * 100) / 100, firstAxis: document.querySelector('.tt-axis span').textContent };`);
    ok('节次变化后课程块按真实时间重排', layoutCheck.styleTop !== null && Math.abs(layoutCheck.styleTop - layoutCheck.expected) < 1.5 && layoutCheck.firstAxis === '08:00', JSON.stringify(layoutCheck));

    /* ---- 折叠状态持久化 ---- */
    await click('[data-nav="home"]'); await sleep(250);
    await evalJson(`document.querySelectorAll('.module-head')[1].click(); return true;`); await sleep(200);
    const modStored = await evalJson(`return JSON.parse(localStorage.getItem('kexu-v8-modules'));`);
    await load();
    const modReloaded = await evalJson(`return { open: [...document.querySelectorAll('.module')].map(n => n.classList.contains('open')) };`);
    ok('折叠状态持久化', modStored.deadline === false && modReloaded.open[1] === false, JSON.stringify({ stored: modStored, reloaded: modReloaded }));

    /* ---- 深色模式 ---- */
    await click('[data-nav="settings"]'); await sleep(200);
    await click('.group-row', 3); await sleep(250);
    await evalJson(`const b = [...document.querySelectorAll('.seg button')].find(x => x.textContent === '深色'); if (b) b.click(); return true;`); await sleep(300);
    const dark = await evalJson(`return { theme: document.documentElement.dataset.theme, stored: localStorage.getItem('kexu-v8-theme') };`);
    ok('切换深色模式并持久化', dark.theme === 'dark' && JSON.parse(dark.stored) === 'dark', JSON.stringify(dark));

    /* ---- 自检结果 ---- */
    const st = await evalJson(`const r = window.__KEXU.CORE.runSelfTests(); return { total: r.length, failed: r.filter(x => !x.ok).length };`);
    ok('页面内自检全部通过', st.total >= 24 && st.failed === 0, JSON.stringify(st));

    console.log(`\n${pass} 项通过，${fail} 项失败`);
  } finally {
    try { chrome.kill(); } catch (e) { /* ignore */ }
    await sleep(400);
    try { fs.rmSync(PROFILE, { recursive: true, force: true }); } catch (e) { /* ignore */ }
  }
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
