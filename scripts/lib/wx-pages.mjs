import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { renderCopyPage } from './copy-page.mjs';
import {
  HTML_BYTE_LIMIT,
  HTML_CHAR_LIMIT,
  hmacPrefix,
  readLightTokens,
  readSiteConfig,
  resolveLocalImage,
  resolvePageKey,
  resolveWechatMode,
  wechatSourceUrl,
} from './content.mjs';
import { transcodeBodyImage, writeCover } from './images.mjs';
import { lintPosts } from './lint.mjs';

function readPlan(repoRoot) {
  const planPath = path.join(repoRoot, '.publish', 'plan.json');
  if (!existsSync(planPath)) return null;
  return JSON.parse(readFileSync(planPath, 'utf8'));
}

function contentHash(html) {
  return `sha256:${createHash('sha256').update(html, 'utf8').digest('hex')}`;
}

function applyImageUrls(html, images, urls) {
  let next = html;
  images.forEach((image, index) => {
    const token = `src="${image.placeholder}"`;
    if (!next.includes(token)) {
      throw new Error(`渲染结果里没有占位符 ${image.placeholder}`);
    }
    next = next.replaceAll(token, `src="${urls[index]}"`);
  });
  if (next.includes('{{wx-img:')) throw new Error('复制页里还留着图片占位符');
  return next;
}

function sourceUrlOf(post, site) {
  return wechatSourceUrl(post, site);
}

async function writeOnePage({ post, site, tokens, secret, outDir }) {
  if (!post.rendered) throw new Error(`content/posts/${post.slug}/index.md 没有渲染结果`);
  const fields = post.data.wechat ?? {};
  const title = fields.title || post.data.title;
  const author = fields.author || site.author;
  const digest = fields.digest || post.data.description;
  const key = hmacPrefix(secret, post.slug);
  const dirName = `${encodeURIComponent(post.slug)}-${key}`;
  const pagePath = `/wx/${dirName}/`;
  const pageDir = path.join(outDir, 'wx', dirName);
  const imgDir = path.join(pageDir, 'img');
  mkdirSync(imgDir, { recursive: true });

  const urls = [];
  for (let index = 0; index < post.rendered.images.length; index += 1) {
    const image = post.rendered.images[index];
    const resolved = resolveLocalImage(post.dir, image.path);
    if (!resolved || resolved.remote || resolved.outside || !resolved.exists) {
      throw new Error(`content/posts/${post.slug} 的图片 ${image.path} 无法放入复制页`);
    }
    const encoded = await transcodeBodyImage(resolved.absolute);
    const fileName = `${String(index + 1).padStart(2, '0')}${encoded.extension}`;
    writeFileSync(path.join(imgDir, fileName), encoded.buffer);
    urls.push(`${site.url}${pagePath}img/${fileName}`);
  }
  const fragment = applyImageUrls(post.rendered.html, post.rendered.images, urls);
  const charCount = Array.from(fragment).length;
  const byteLength = Buffer.byteLength(fragment);
  if (charCount >= HTML_CHAR_LIMIT || byteLength >= HTML_BYTE_LIMIT) {
    throw new Error(
      `content/posts/${post.slug}/index.md 填入图片地址后的公众号 HTML 有 ${charCount} 字符、${byteLength} 字节，上限是 ${HTML_CHAR_LIMIT} 字符且小于 1 MB。`,
    );
  }
  const hash = contentHash(fragment);
  writeFileSync(path.join(pageDir, 'content.html'), fragment);

  const coverRef = fields.cover || post.data.heroImage || '';
  let coverUrl = null;
  if (coverRef) {
    const resolved = resolveLocalImage(post.dir, coverRef);
    if (!resolved || resolved.remote || resolved.outside || !resolved.exists) {
      throw new Error(`content/posts/${post.slug} 的封面 ${coverRef} 无法放入复制页`);
    }
    await writeCover(resolved.absolute, path.join(pageDir, 'cover.jpg'), fields.coverCrop ?? null);
    coverUrl = `${site.url}${pagePath}cover.jpg`;
  }

  const source = sourceUrlOf(post, site);
  const meta = {
    title,
    author,
    digest,
    sourceUrl: source || null,
    coverUrl,
    openComment: fields.openComment ?? null,
    fansOnlyComment: fields.fansOnlyComment ?? null,
    charCount,
    byteLength,
    contentHash: hash,
  };
  writeFileSync(path.join(pageDir, 'meta.json'), `${JSON.stringify(meta, null, 2)}\n`);
  const page = renderCopyPage({
    title,
    author,
    digest,
    sourceUrl: source,
    blog: post.data.publish.blog,
    openComment: fields.openComment ?? null,
    fansOnlyComment: fields.fansOnlyComment ?? null,
    charCount: meta.charCount,
    hasCover: Boolean(coverUrl),
    fragment,
    tokens,
  });
  writeFileSync(path.join(pageDir, 'index.html'), page);
  return {
    slug: post.slug,
    title,
    path: pagePath,
    url: `${site.url}${pagePath}`,
    contentHash: hash,
  };
}

/**
 * @param {{
 *   repoRoot: string,
 *   outDir: string,
 *   postsDir?: string,
 *   log?: (message: string) => void,
 *   warn?: (message: string) => void,
 * }} options
 */
export async function buildWxPages({ repoRoot, outDir, postsDir = undefined, log = console.log, warn = console.warn }) {
  const site = readSiteConfig(repoRoot);
  const tokens = readLightTokens(repoRoot);
  const { key, dev } = resolvePageKey();
  if (dev) warn('WX_PAGE_KEY 未设置，复制页路径使用 dev-insecure-wx-page-key。这不是密钥。');
  const plan = readPlan(repoRoot);
  const resolvedPosts = postsDir
    ? postsDir
    : plan?.wxPostsDir
      ? path.resolve(repoRoot, plan.wxPostsDir)
      : path.join(repoRoot, 'content', 'posts');
  const lint = lintPosts(resolvedPosts, { repoRoot });
  for (const item of lint.warnings) warn(item.message);
  if (lint.errors.length) {
    throw new Error(lint.errors.map((item) => item.message).join('\n'));
  }

  const eligible = lint.posts.filter((post) => post.data.publish.wechat && !post.data.draft && post.ext === '.md');
  const pages = [];
  for (const post of eligible) {
    pages.push(await writeOnePage({ post, site, tokens, secret: key, outDir }));
    log(`复制页 ${post.slug}`);
  }

  const catalogKey = hmacPrefix(key, 'index');
  const index = {
    wechatMode: resolveWechatMode(),
    devPageKey: dev,
    catalogPath: `/wx/${catalogKey}/index.json`,
    catalogUrl: `${site.url}/wx/${catalogKey}/index.json`,
    generatedAt: new Date().toISOString(),
    pages,
  };
  const json = `${JSON.stringify(index, null, 2)}\n`;
  const wxDir = path.join(outDir, 'wx');
  mkdirSync(path.join(wxDir, catalogKey), { recursive: true });
  writeFileSync(path.join(wxDir, 'index.json'), json);
  writeFileSync(path.join(wxDir, catalogKey, 'index.json'), json);
  log(`公众号模式 ${index.wechatMode}，复制页 ${pages.length} 篇`);
  return index;
}
