import fs from 'node:fs';
import vm from 'node:vm';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const htmlPath = path.join(root, '课序_V8_完整交互原型.html');
const html = fs.readFileSync(htmlPath, 'utf8');

/* 1. 语法检查：编译整段脚本（不执行，避免依赖浏览器环境） */
const scriptMatch = html.match(/<script>([\s\S]*?)<\/script>/);
if (!scriptMatch) {
  console.error('FAIL 未找到 <script> 块');
  process.exit(1);
}
const scriptSrc = scriptMatch[1];
try {
  new vm.Script(scriptSrc, { filename: 'kexu-app.js' });
  console.log('PASS 脚本语法检查（vm.Script 编译）');
} catch (e) {
  console.error('FAIL 脚本语法错误：\n' + String(e && e.stack ? e.stack : e));
  process.exit(1);
}

/* 2. 抽取纯逻辑 CORE，跑内置自检 */
const begin = html.indexOf('CORE-BEGIN');
const beginEnd = html.indexOf('*/', begin) + 2;
const end = html.indexOf('CORE-END');
const endStart = html.lastIndexOf('/*', end);
const coreSrc = html.slice(beginEnd, endStart);
if (!coreSrc || begin < 0 || end < 0) {
  console.error('FAIL 未找到 CORE 区块');
  process.exit(1);
}
const sandbox = vm.createContext({ console });
vm.runInContext(coreSrc + '\n;globalThis.__CORE = CORE;', sandbox, { filename: 'kexu-core.js' });
const CORE = sandbox.__CORE;
if (!CORE) {
  console.error('FAIL CORE 未导出');
  process.exit(1);
}
const results = CORE.runSelfTests();
let failed = 0;
for (const r of results) {
  if (r.ok) console.log(`PASS ${r.name}`);
  else { failed += 1; console.log(`FAIL ${r.name}${r.detail ? ' — ' + r.detail : ''}`); }
}
console.log(`\n${results.length - failed}/${results.length} 项通过`);
if (results.length < 24) {
  console.error(`FAIL 自检项不足 24（当前 ${results.length}）`);
  process.exit(1);
}
if (failed) process.exit(1);
console.log('OK 全部核心逻辑测试通过');
