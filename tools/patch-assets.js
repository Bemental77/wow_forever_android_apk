#!/usr/bin/env node
// Replace "app.gamenative" with "com.wowforever" (same length) in assets/runtime files.
// Usage: node tools/patch-assets.js [--check] <file|dir>...
// tar.zst / tar.xz (.tzst .txz .wcp ...): patched inside the decompressed tar (layout, modes and
// symlinks untouched; changed header checksums recomputed), then recompressed with the same codec.
// Other files are patched raw. Env: ZSTD (zstd.exe), XZ (xz).
'use strict';
const fs = require('fs'), path = require('path'), os = require('os'), cp = require('child_process');

const FROM = Buffer.from('app.gamenative'), TO = Buffer.from('com.wowforever');
const ZSTD = process.env.ZSTD || 'C:/Users/Admin/Dev/toolchain/dl-zstd/zstd-v1.5.7-win64/zstd.exe';
const XZ = process.env.XZ || 'xz';
const check = process.argv.includes('--check');
const args = process.argv.slice(2).filter(a => a !== '--check');

function run(cmd, argv, outFile) {
  const out = outFile ? fs.openSync(outFile, 'w') : 'ignore';
  const r = cp.spawnSync(cmd, argv, { stdio: ['ignore', out, 'inherit'] });
  if (outFile) fs.closeSync(out);
  if (r.status !== 0) throw new Error(`${cmd} ${argv.join(' ')} failed (${r.status ?? r.error})`);
}

function codec(file) {
  const h = Buffer.alloc(6), fd = fs.openSync(file, 'r');
  fs.readSync(fd, h, 0, 6, 0); fs.closeSync(fd);
  if (h.readUInt32LE(0) === 0xfd2fb528) return 'zstd';
  if (h.equals(Buffer.from([0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00]))) return 'xz';
  return 'raw';
}

// Chunked in-place replace; returns hit count (or just counts with dry=true).
function replaceInFile(file, dry) {
  const fd = fs.openSync(file, dry ? 'r' : 'r+'), size = fs.fstatSync(fd).size;
  const buf = Buffer.alloc(64 << 20);
  let pos = 0, hits = 0;
  while (pos < size) {
    const n = fs.readSync(fd, buf, 0, buf.length, pos);
    const b = buf.subarray(0, n);
    let dirty = false;
    for (let i = b.indexOf(FROM); i >= 0; i = b.indexOf(FROM, i + FROM.length)) {
      hits++; dirty = true; if (!dry) TO.copy(b, i);
    }
    if (dirty && !dry) fs.writeSync(fd, b, 0, n, pos);
    if (pos + n >= size) break;
    pos += n - (FROM.length - 1);
  }
  fs.closeSync(fd);
  return hits;
}

// Walk tar headers; recompute checksums that no longer match. Returns [entries, fixed, bad].
function fixTar(file, verifyOnly) {
  const fd = fs.openSync(file, verifyOnly ? 'r' : 'r+'), size = fs.fstatSync(fd).size, h = Buffer.alloc(512);
  let pos = 0, entries = 0, fixed = 0, bad = 0;
  while (pos + 512 <= size) {
    fs.readSync(fd, h, 0, 512, pos);
    if (h.every(x => x === 0)) break;
    let sum = 0;
    for (let i = 0; i < 512; i++) sum += i >= 148 && i < 156 ? 32 : h[i];
    const stored = parseInt(h.toString('latin1', 148, 156).replace(/[\0 ]/g, ''), 8);
    if (stored !== sum) {
      if (verifyOnly) bad++;
      else { Buffer.from(sum.toString(8).padStart(6, '0') + '\0 ', 'latin1').copy(h, 148); fs.writeSync(fd, h, 0, 512, pos); fixed++; }
    }
    const sf = h.subarray(124, 136);
    const sz = sf[0] & 0x80 ? Number(sf.subarray(4).readBigUInt64BE()) : parseInt(sf.toString('latin1').replace(/[\0 ]/g, '') || '0', 8);
    pos += 512 + Math.ceil(sz / 512) * 512;
    entries++;
  }
  fs.closeSync(fd);
  return [entries, fixed, bad];
}

function xzDict(file) {
  const r = cp.spawnSync(XZ, ['--robot', '-lvv', file], { encoding: 'utf8' });
  const m = /^summary\t(\d+)/m.exec(r.stdout || '');
  return m ? Math.max(+m[1], 1 << 20) : 64 << 20; // memusage ~= dict size for decoding
}

function processFile(file) {
  const c = codec(file);
  if (c === 'raw') {
    const hits = replaceInFile(file, check);
    if (hits) console.log(`${check ? 'HIT ' : 'patched'} ${file} raw x${hits}`);
    return hits;
  }
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'patch-assets-')), tar = path.join(tmp, 'a.tar');
  try {
    if (c === 'zstd') run(ZSTD, ['-d', '-q', '-f', file, '-o', tar]); else run(XZ, ['-dc', file], tar);
    const hits = replaceInFile(tar, check);
    if (!hits || check) { if (hits) console.log(`HIT ${file} ${c} x${hits}`); return hits; }
    const [entries, fixed] = fixTar(tar, false);
    const out = path.join(tmp, 'a.out');
    if (c === 'zstd') run(ZSTD, ['-q', '-f', '-19', '-T0', tar, '-o', out]);
    else run(XZ, ['-c', '-T1', `--lzma2=preset=9,dict=${xzDict(file)}`, tar], out);
    // verify: re-extract, 0 hits, all checksums valid
    const back = path.join(tmp, 'b.tar');
    if (c === 'zstd') run(ZSTD, ['-d', '-q', '-f', out, '-o', back]); else run(XZ, ['-dc', out], back);
    const left = replaceInFile(back, true), [e2, , bad] = fixTar(back, true);
    if (left || bad || e2 !== entries) throw new Error(`verify failed for ${file}: hits=${left} bad=${bad} entries=${e2}/${entries}`);
    fs.copyFileSync(out, file);
    console.log(`patched ${file} ${c} x${hits} (${entries} entries, ${fixed} headers)`);
    return hits;
  } finally { fs.rmSync(tmp, { recursive: true, force: true }); }
}

const walk = p => fs.statSync(p).isDirectory() ? fs.readdirSync(p).flatMap(f => walk(path.join(p, f))) : [p];
let total = 0;
for (const f of args.flatMap(walk)) total += processFile(f);
console.log(`${check ? 'found' : 'replaced'} ${total} occurrence(s)`);
if (check && total) process.exitCode = 1;
