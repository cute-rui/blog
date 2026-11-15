#!/usr/bin/env node
// 在你自己的电脑上跑，不要放进 CI，也不要把 AppSecret 写进仓库。
//
// 1. 查当前公网 IP：curl -4 ifconfig.me
// 2. 把这个 IP 临时加进公众号的 API IP 白名单。
// 3. 在 shell 里设置环境变量，不要写进文件：
//      export WECHAT_APPID=...
//      export WECHAT_APPSECRET=...
// 4. node tools/wechat-probe/probe.mjs
// 5. 看终端和 wechat-probe-result.md 里的表。draft/add 成功后脚本会 draft/delete。
// 6. 从白名单删掉刚才的 IP。
// 7. 用浏览器打开 tools/wechat-probe/sample-paste.html，按文件顶部的说明做粘贴实测。
//
// 结果表由你写进内容仓库 README，并据此把博客仓库变量 WECHAT_MODE 留在 html，
// 或在接受维护固定 IP 的 VPS 之后再改为 draft。本脚本不修改任何仓库变量。

import { writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateSync } from 'node:zlib';

const THUMB_JPEG = Buffer.from(
  '/9j/2wBDAAYEBQYFBAYGBQYHBwYIChAKCgkJChQODwwQFxQYGBcUFhYaHSUfGhsjHBYWICwgIyYnKSopGR8tMC0oMCUoKSj/2wBDAQcHBwoIChMKChMoGhYaKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCj/wAARCAAgACADASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAb/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFQEBAQAAAAAAAAAAAAAAAAAAAAb/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIRAxEAPwCAAUSfAAAAAAf/2Q==',
  'base64',
);

export function redact(value) {
  return String(value)
    .replace(/access_token=[^&\s]+/g, 'access_token=REDACTED')
    .replace(/"secret"\s*:\s*"[^"]*"/g, '"secret":"REDACTED"')
    .replace(/"access_token"\s*:\s*"[^"]*"/g, '"access_token":"REDACTED"')
    .replace(/WECHAT_APPSECRET=\S+/g, 'WECHAT_APPSECRET=REDACTED');
}

export function renderTable(rows) {
  const header = ['| 步骤 | HTTP | errcode | 说明 |', '| --- | --- | --- | --- |'];
  const body = rows.map((row) => `| ${clean(row.step)} | ${clean(row.status)} | ${clean(row.errcode)} | ${clean(row.note)} |`);
  return [...header, ...body].join('\n');
}

function clean(value) {
  return redact(value ?? '').replaceAll('|', '\\|').replaceAll('\n', ' ');
}

function crc32(buffer) {
  let crc = ~0;
  for (const byte of buffer) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
  }
  return ~crc >>> 0;
}

function pngChunk(type, data) {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([length, body, crc]);
}

export function solidPng(width = 16, height = 16) {
  const raw = Buffer.alloc((width * 4 + 1) * height);
  for (let y = 0; y < height; y += 1) {
    const row = y * (width * 4 + 1);
    raw[row] = 0;
    for (let x = 0; x < width; x += 1) {
      const index = row + 1 + x * 4;
      raw[index] = 0x2a;
      raw[index + 1] = 0x5a;
      raw[index + 2] = 0x86;
      raw[index + 3] = 0xff;
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', deflateSync(raw)),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}

function resultRow(step, status, errcode, note) {
  return {
    step,
    status: status === undefined || status === null ? '' : String(status),
    errcode: errcode === undefined || errcode === null ? '' : String(errcode),
    note: redact(note),
  };
}

function failed(json) {
  return Boolean(json) && typeof json.errcode === 'number' && json.errcode !== 0;
}

async function postJson(url, body, request = fetch) {
  const response = await request(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json; charset=utf-8' },
    body: JSON.stringify(body),
  });
  const text = await response.text();
  let json = {};
  try {
    json = JSON.parse(text);
  } catch {
    json = { errmsg: text.slice(0, 180) };
  }
  return { status: response.status, json };
}

async function postForm(url, fileName, type, bytes, request = fetch) {
  const form = new FormData();
  form.append('media', new Blob([bytes], { type }), fileName);
  const response = await request(url, { method: 'POST', body: form });
  const text = await response.text();
  let json = {};
  try {
    json = JSON.parse(text);
  } catch {
    json = { errmsg: text.slice(0, 180) };
  }
  return { status: response.status, json };
}

export async function runProbe({ appid, secret, fetchImpl } = {}) {
  const request = fetchImpl ?? fetch;
  const rows = [];
  if (!appid || !secret) {
    rows.push(resultRow('配置', '', '', '缺少环境变量 WECHAT_APPID 或 WECHAT_APPSECRET'));
    return { rows, ok: false };
  }

  const tokenCall = await postJson(
    'https://api.weixin.qq.com/cgi-bin/stable_token',
    {
      grant_type: 'client_credential',
      appid,
      secret,
      force_refresh: false,
    },
    request,
  );
  const token = !failed(tokenCall.json) && typeof tokenCall.json.access_token === 'string' ? tokenCall.json.access_token : '';
  rows.push(
    resultRow(
      'stable_token',
      tokenCall.status,
      tokenCall.json.errcode ?? (token ? 0 : ''),
      token ? '拿到 access_token' : tokenCall.json.errmsg || '没有返回 access_token',
    ),
  );
  if (!token) {
    for (const step of ['uploadimg', 'add_material', 'draft/add', 'draft/delete']) {
      rows.push(resultRow(step, '', '', '跳过：没有 access_token'));
    }
    return { rows, ok: false };
  }

  const encodedToken = encodeURIComponent(token);
  const upload = await postForm(
    `https://api.weixin.qq.com/cgi-bin/media/uploadimg?access_token=${encodedToken}`,
    'probe.png',
    'image/png',
    solidPng(),
    request,
  );
  const imageUrl = !failed(upload.json) && typeof upload.json.url === 'string' ? upload.json.url : '';
  rows.push(
    resultRow(
      'uploadimg',
      upload.status,
      upload.json.errcode ?? (imageUrl ? 0 : ''),
      imageUrl ? '返回了图片 URL' : upload.json.errmsg || '没有返回 url',
    ),
  );

  const material = await postForm(
    `https://api.weixin.qq.com/cgi-bin/material/add_material?access_token=${encodedToken}&type=thumb`,
    'probe.jpg',
    'image/jpeg',
    THUMB_JPEG,
    request,
  );
  const thumbId = !failed(material.json) && typeof material.json.media_id === 'string' ? material.json.media_id : '';
  rows.push(
    resultRow(
      'add_material',
      material.status,
      material.json.errcode ?? (thumbId ? 0 : ''),
      thumbId ? 'type=thumb，拿到永久素材 media_id' : material.json.errmsg || '没有返回 media_id',
    ),
  );

  const imageHtml = imageUrl ? `<p><img src="${imageUrl}" /></p>` : '';
  const draftBody = {
    articles: [
      {
        article_type: 'news',
        title: 'P0探针草稿',
        author: 'probe',
        digest: '能力探针，测完删除',
        content: `<p style="font-size:16px;">P0 探针，测完即删。</p>${imageHtml}`,
        need_open_comment: 0,
        only_fans_can_comment: 0,
        ...(thumbId ? { thumb_media_id: thumbId } : {}),
      },
    ],
  };
  const draft = await postJson(
    `https://api.weixin.qq.com/cgi-bin/draft/add?access_token=${encodedToken}`,
    draftBody,
    request,
  );
  const draftId = !failed(draft.json) && typeof draft.json.media_id === 'string' ? draft.json.media_id : '';
  rows.push(
    resultRow(
      'draft/add',
      draft.status,
      draft.json.errcode ?? (draftId ? 0 : ''),
      draftId ? `新建草稿 ${draftId}` : draft.json.errmsg || '没有返回 media_id',
    ),
  );

  if (!draftId) {
    rows.push(resultRow('draft/delete', '', '', '跳过：没有草稿 media_id'));
  } else {
    const deleted = await postJson(
      `https://api.weixin.qq.com/cgi-bin/draft/delete?access_token=${encodedToken}`,
      { media_id: draftId },
      request,
    );
    const gone = !failed(deleted.json);
    rows.push(
      resultRow(
        'draft/delete',
        deleted.status,
        deleted.json.errcode ?? (gone ? 0 : ''),
        gone ? '已删除测试草稿' : `${deleted.json.errmsg || '删除失败'}。请在公众号后台手动删除 ${draftId}`,
      ),
    );
  }

  const byName = Object.fromEntries(rows.map((row) => [row.step, row]));
  const ok = ['stable_token', 'uploadimg', 'add_material', 'draft/add', 'draft/delete'].every((step) => {
    const row = byName[step];
    return row && row.note && !row.note.startsWith('跳过') && !row.note.startsWith('没有') && row.errcode !== '' && Number(row.errcode) === 0;
  });
  return { rows, ok };
}

function recommendation(ok) {
  if (ok) {
    return 'stable_token、uploadimg、add_material、draft/add 都成功，测试草稿已删除。若你愿意维护一台固定 IP 的 VPS，可以把博客仓库变量 WECHAT_MODE 设为 draft；否则保持 html。手机端是否直接粘贴，看 sample-paste.html 的实测。';
  }
  return '有步骤没有成功。建议博客仓库变量 WECHAT_MODE 保持 html。把这张表抄进内容仓库 README，作为公众号能力表。';
}

async function main() {
  if (process.argv.includes('--help')) {
    console.log('用法：设置 WECHAT_APPID 和 WECHAT_APPSECRET 后执行 node tools/wechat-probe/probe.mjs');
    console.log('可选：WECHAT_PROBE_OUT 指定结果表路径，默认 ./wechat-probe-result.md');
    return;
  }
  const { rows, ok } = await runProbe({
    appid: process.env.WECHAT_APPID ?? '',
    secret: process.env.WECHAT_APPSECRET ?? '',
  });
  const table = renderTable(rows);
  const text = `${table}\n\n${recommendation(ok)}\n`;
  const output = process.env.WECHAT_PROBE_OUT || 'wechat-probe-result.md';
  writeFileSync(output, text);
  console.log(text);
  console.log(`结果表已写入 ${output}`);
  process.exitCode = ok ? 0 : 1;
}

const invoked = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (invoked) {
  main().catch((error) => {
    console.error(redact(error instanceof Error ? error.message : String(error)));
    process.exitCode = 1;
  });
}
