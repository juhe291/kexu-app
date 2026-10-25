import fs from 'node:fs';
import zlib from 'node:zlib';

const file = process.argv[2];
const x = Number(process.argv[3] || 0);
const y = Number(process.argv[4] || 0);
const w = Number(process.argv[5] || 20);
const h = Number(process.argv[6] || 20);
const buf = fs.readFileSync(file);
let pos = 8, width = 0, height = 0, bitDepth = 0, colorType = 0;
const idat = [];
while (pos < buf.length) {
  const len = buf.readUInt32BE(pos); const type = buf.toString('ascii', pos + 4, pos + 8);
  const data = buf.subarray(pos + 8, pos + 8 + len);
  if (type === 'IHDR') { width = data.readUInt32BE(0); height = data.readUInt32BE(4); bitDepth = data[8]; colorType = data[9]; }
  if (type === 'IDAT') idat.push(data);
  pos += 12 + len;
  if (type === 'IEND') break;
}
const channels = colorType === 6 ? 4 : colorType === 2 ? 3 : colorType === 0 ? 1 : colorType === 4 ? 2 : 0;
if (bitDepth !== 8 || !channels) { console.error('unsupported png', { width, height, bitDepth, colorType }); process.exit(1); }
const raw = zlib.inflateSync(Buffer.concat(idat));
const stride = width * channels;
const out = Buffer.alloc(height * stride);
let src = 0;
const paeth = (a, b, c) => { const p = a + b - c; const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c); return pa <= pb && pa <= pc ? a : pb <= pc ? b : c; };
for (let row = 0; row < height; row += 1) {
  const filter = raw[src++];
  for (let i = 0; i < stride; i += 1) {
    const rawV = raw[src++];
    const left = i >= channels ? out[row * stride + i - channels] : 0;
    const up = row > 0 ? out[(row - 1) * stride + i] : 0;
    const upLeft = row > 0 && i >= channels ? out[(row - 1) * stride + i - channels] : 0;
    let v;
    if (filter === 0) v = rawV;
    else if (filter === 1) v = rawV + left;
    else if (filter === 2) v = rawV + up;
    else if (filter === 3) v = rawV + Math.floor((left + up) / 2);
    else if (filter === 4) v = rawV + paeth(left, up, upLeft);
    else throw new Error('bad filter ' + filter);
    out[row * stride + i] = v & 255;
  }
}
const px = (xx, yy) => { const i = yy * stride + xx * channels; return channels === 4 ? [out[i], out[i + 1], out[i + 2], out[i + 3]] : [out[i], out[i + 1], out[i + 2], 255]; };
const hex = (c) => '#' + c.slice(0, 3).map((v) => v.toString(16).padStart(2, '0')).join('');
const counts = new Map();
for (let yy = y; yy < Math.min(y + h, height); yy += 1) for (let xx = x; xx < Math.min(x + w, width); xx += 1) { const hx = hex(px(xx, yy)); counts.set(hx, (counts.get(hx) || 0) + 1); }
const top = [...counts.entries()].sort((a, b) => b[1] - a[1]).slice(0, 8);
console.log(JSON.stringify({ size: [width, height], region: { x, y, w, h }, top: top.map(([c, n]) => ({ color: c, count: n })) }, null, 2));
