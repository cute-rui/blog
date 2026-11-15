import { createHmac } from 'node:crypto';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Ajv2020 } from 'ajv/dist/2020.js';
import { parse as parseYaml } from 'yaml';

export const HTML_CHAR_LIMIT = 20_000;
export const HTML_BYTE_LIMIT = 1_000_000;
export const DEV_PAGE_KEY = 'dev-insecure-wx-page-key';

const moduleDir = path.dirname(fileURLToPath(import.meta.url));

export function repoRoot() {
  return path.resolve(moduleDir, '../..');
}

export function charLength(value) {
  return Array.from(value).length;
}

export function escapeHtml(value) {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
}

export function hmacPrefix(key, message) {
  return createHmac('sha256', key).update(message).digest('hex').slice(0, 10);
}

export function resolvePageKey(env = process.env) {
  const raw = env.WX_PAGE_KEY;
  if (typeof raw === 'string' && raw.trim() !== '') return { key: raw, dev: false };
  return { key: DEV_PAGE_KEY, dev: true };
}

export function resolveWechatMode(env = process.env) {
  const raw = env.WECHAT_MODE;
  if (typeof raw !== 'string' || raw.trim() === '') return 'html';
  return raw.trim();
}

export function readSiteConfig(root) {
  const source = readFileSync(path.join(root, 'src/site.config.ts'), 'utf8');
  const author = source.match(/^\s*author:\s*'([^']*)'/m);
  const url = source.match(/^\s*url:\s*'([^']*)'/m);
  if (!author || !url) {
    throw new Error('无法从 src/site.config.ts 读取 author 或 url');
  }
  return { author: author[1], url: url[1].replace(/\/+$/, '') };
}

export function readLightTokens(root) {
  const css = readFileSync(path.join(root, 'src/styles/global.css'), 'utf8');
  const light = css.split('[data-theme="dark"]')[0];
  const pick = (name) => {
    const match = new RegExp(`--${name}:\\s*(#[0-9a-fA-F]{6})`).exec(light);
    if (!match) throw new Error(`src/styles/global.css 的浅色令牌缺少 --${name}`);
    return match[1];
  };
  return {
    canvas: pick('canvas'),
    surface: pick('surface'),
    surface2: pick('surface-2'),
    ink: pick('ink'),
    muted: pick('muted'),
    line: pick('line'),
    accent: pick('accent'),
    accentFg: pick('accent-fg'),
  };
}

export function splitFrontMatter(raw) {
  const text = raw.replace(/^\uFEFF/, '');
  const match = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?/.exec(text);
  if (!match) return { data: {}, body: text, hasFrontMatter: false };
  const parsed = parseYaml(match[1], { schema: 'core' });
  const data = parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
  return { data, body: text.slice(match[0].length), hasFrontMatter: true };
}

function dateString(value) {
  if (value instanceof Date) return value.toISOString().slice(0, 10);
  return value;
}

export function prepareFrontMatter(data) {
  const copy = { ...data };
  if ('pubDate' in copy) copy.pubDate = dateString(copy.pubDate);
  if ('updatedDate' in copy) copy.updatedDate = dateString(copy.updatedDate);
  return copy;
}

export function loadSchema(root) {
  const schemaPath = path.join(root, 'schema/content-schema.json');
  const schema = JSON.parse(readFileSync(schemaPath, 'utf8'));
  const contentCopy = path.join(root, 'content/content-schema.json');
  let versionError = null;
  if (existsSync(contentCopy)) {
    const other = JSON.parse(readFileSync(contentCopy, 'utf8'));
    if (other.version !== schema.version) {
      versionError = `content/content-schema.json 的 version 是 ${other.version}，博客仓库 schema/content-schema.json 是 ${schema.version}。两边要一起改。`;
    }
  }
  const ajv = new Ajv2020({ allErrors: true, strict: false, useDefaults: true });
  const validate = ajv.compile(schema);
  return { schema, validate, versionError };
}

export function schemaErrors(validate, data) {
  const copy = prepareFrontMatter(data);
  const ok = validate(copy);
  if (ok) return { data: copy, errors: [] };
  const errors = (validate.errors ?? []).map((error) => {
    const where = error.instancePath || '/';
    return `${where} ${error.message ?? '不符合 schema'}`;
  });
  return { data: copy, errors };
}

export function normalizePublish(data) {
  const publish = data.publish ?? {};
  return {
    blog: publish.blog !== false,
    wechat: publish.wechat === true,
  };
}

function isPostFile(name) {
  return name.endsWith('.md') || name.endsWith('.mdx');
}

export function listPosts(postsDir) {
  if (!existsSync(postsDir)) {
    return { missing: true, posts: [], errors: [] };
  }
  const errors = [];
  const posts = [];
  for (const entry of readdirSync(postsDir, { withFileTypes: true })) {
    if (entry.name.startsWith('.')) continue;
    const full = path.join(postsDir, entry.name);
    if (entry.isFile() && isPostFile(entry.name)) {
      errors.push(
        `${path.posix.join('content/posts', entry.name)} 是旧的单文件布局。请改成 content/posts/${entry.name.replace(/\.mdx?$/, '')}/index.md，图片放进同一目录。`,
      );
      continue;
    }
    if (!entry.isDirectory()) continue;
    const names = readdirSync(full);
    const indexes = names.filter((name) => name === 'index.md' || name === 'index.mdx');
    if (indexes.length === 0) {
      errors.push(`content/posts/${entry.name}/ 没有 index.md。`);
      continue;
    }
    if (indexes.length > 1) {
      errors.push(`content/posts/${entry.name}/ 不能同时有 index.md 和 index.mdx。`);
      continue;
    }
    const fileName = indexes[0];
    const filePath = path.join(full, fileName);
    const raw = readFileSync(filePath, 'utf8');
    const split = splitFrontMatter(raw);
    posts.push({
      slug: entry.name,
      ext: path.extname(fileName),
      filePath,
      dir: full,
      data: split.data,
      body: split.body,
      hasFrontMatter: split.hasFrontMatter,
    });
  }
  posts.sort((a, b) => a.slug.localeCompare(b.slug));
  return { missing: false, posts, errors };
}

export function isInside(parent, child) {
  const root = path.resolve(parent);
  const target = path.resolve(child);
  return target === root || target.startsWith(root + path.sep);
}

export function resolveLocalImage(postDir, ref) {
  if (typeof ref !== 'string' || ref.trim() === '') return null;
  if (/^(https?:|data:)/i.test(ref)) return { remote: true, ref };
  const absolute = path.resolve(postDir, ref);
  return { remote: false, ref, absolute, outside: !isInside(postDir, absolute), exists: existsSync(absolute) };
}

export function postFiles(postDir) {
  if (!existsSync(postDir)) return [];
  return readdirSync(postDir).filter((name) => statSync(path.join(postDir, name)).isFile());
}
