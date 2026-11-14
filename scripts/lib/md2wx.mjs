import { mkdtempSync, readFileSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

export function findMd2wx(root) {
  if (process.env.MD2WX_BIN && existsSync(process.env.MD2WX_BIN)) return process.env.MD2WX_BIN;
  const name = process.platform === 'win32' ? 'md2wx.exe' : 'md2wx';
  const release = path.join(root, 'md2wx', 'target', 'release', name);
  const debug = path.join(root, 'md2wx', 'target', 'debug', name);
  if (existsSync(release)) return release;
  if (existsSync(debug)) return debug;
  return null;
}

export function renderMarkdown(bin, markdown) {
  const dir = mkdtempSync(path.join(tmpdir(), 'md2wx-'));
  try {
    const input = path.join(dir, 'in.md');
    const output = path.join(dir, 'out.json');
    writeFileSync(input, markdown);
    const result = spawnSync(bin, ['--input', input, '--output', output], {
      encoding: 'utf8',
      maxBuffer: 16 * 1024 * 1024,
    });
    if (result.error) return { ok: false, error: result.error.message };
    if (result.status !== 0) {
      const message = (result.stderr || result.stdout || 'md2wx 失败').trim();
      return { ok: false, error: message.replace(/^md2wx:\s*/, '') };
    }
    const json = JSON.parse(readFileSync(output, 'utf8'));
    return { ok: true, html: json.html, images: json.images ?? [], charCount: json.charCount, byteLen: json.byteLen };
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}
