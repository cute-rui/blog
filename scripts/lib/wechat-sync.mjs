import { spawnSync } from 'node:child_process';
import { appendFileSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { HTML_BYTE_LIMIT, HTML_CHAR_LIMIT, charLength, readSiteConfig, resolveLocalImage, wechatSourceUrl } from './content.mjs';
import { transcodeBodyImage, writeCover } from './images.mjs';
import { lintPosts } from './lint.mjs';

export function egressConfigured(env) {
  const token = typeof env.WECHAT_EGRESS_TOKEN === 'string' ? env.WECHAT_EGRESS_TOKEN.trim() : '';
  const host = typeof env.WECHAT_EGRESS_SRV_HOST === 'string' ? env.WECHAT_EGRESS_SRV_HOST.trim() : '';
  const address = typeof env.WECHAT_EGRESS_ADDRESS === 'string' ? env.WECHAT_EGRESS_ADDRESS.trim() : '';
  return token !== '' && (host !== '' || address !== '');
}

export function wechatIdentity(post, site) {
  const fields = post.data?.wechat ?? {};
  return {
    title: String(fields.title || post.data?.title || '').trim(),
    author: String(fields.author || site.author || '').trim(),
    digest: String(fields.digest || post.data?.description || '').trim(),
    sourceUrl: wechatSourceUrl(post, site).trim(),
    openComment: fields.openComment,
    fansOnlyComment: fields.fansOnlyComment,
    cover: fields.cover || post.data?.heroImage || '',
    coverCrop: fields.coverCrop ?? null,
  };
}

function normUrl(value) {
  return String(value || '').trim().replace(/\/+$/, '');
}

export function classifyExisting(candidate, existing) {
  const title = candidate.title.trim();
  const source = normUrl(candidate.sourceUrl);
  let where = null;
  for (const item of existing) {
    const itemTitle = String(item.title || '').trim();
    const itemSource = normUrl(item.content_source_url);
    const sameTitle = title !== '' && itemTitle === title;
    const sameSource = source !== '' && itemSource === source;
    if (!sameTitle && !sameSource) continue;
    if (item.where === 'published') return 'published';
    if (item.where === 'draft') where = 'draft';
  }
  return where;
}

export function selectMissing(candidates, existing) {
  const missing = [];
  const skipped = [];
  for (const candidate of candidates) {
    const where = classifyExisting(candidate.wx, existing);
    if (where) skipped.push({ ...candidate, where });
    else missing.push(candidate);
  }
  return { missing, skipped };
}

export function draftPayload(wx, content, thumbMediaId) {
  const article = { title: wx.title, content };
  if (wx.author) article.author = wx.author;
  if (wx.digest) article.digest = wx.digest;
  if (wx.sourceUrl) article.content_source_url = wx.sourceUrl;
  if (thumbMediaId) article.thumb_media_id = thumbMediaId;
  if (wx.openComment === true) article.need_open_comment = 1;
  else if (wx.openComment === false) article.need_open_comment = 0;
  if (wx.fansOnlyComment === true) article.only_fans_can_comment = 1;
  else if (wx.fansOnlyComment === false) article.only_fans_can_comment = 0;
  return { articles: [article] };
}

function applyImageUrls(html, images, urls) {
  let next = html;
  images.forEach((image, index) => {
    const token = `src="${image.placeholder}"`;
    if (!next.includes(token)) throw new Error(`渲染结果里没有占位符 ${image.placeholder}`);
    next = next.replaceAll(token, `src="${urls[index]}"`);
  });
  if (next.includes('{{wx-img:')) throw new Error('草稿正文里还留着图片占位符');
  return next;
}

function defaultRun(env) {
  const bin = env.WECHAT_EGRESS_BIN;
  if (!bin) throw new Error('WECHAT_EGRESS_BIN 未设置。');
  return (args) => {
    const result = spawnSync(bin, args, {
      encoding: 'utf8',
      env,
      maxBuffer: 32 * 1024 * 1024,
    });
    return {
      code: result.status ?? 1,
      stdout: result.stdout || '',
      stderr: (result.stderr || result.error?.message || '').trim(),
    };
  };
}

function callJson(run, args) {
  const result = run([...args, '--timeout', '180s']);
  if (result.code !== 0) {
    throw new Error(result.stderr || result.stdout || `${args[0]} 失败`);
  }
  return JSON.parse(result.stdout);
}

function tagged(articles, where) {
  return (articles ?? []).map((article) => ({ ...article, where }));
}

function candidatesFromLint(lint, site) {
  return lint.posts
    .filter((post) => post.data.publish?.wechat && !post.data.draft && post.ext === '.md')
    .map((post) => ({ post, wx: wechatIdentity(post, site) }));
}

function appendSummary(env, text) {
  if (!env.GITHUB_STEP_SUMMARY) return;
  appendFileSync(env.GITHUB_STEP_SUMMARY, text.endsWith('\n') ? text : `${text}\n`);
}

function summaryText(result) {
  const lines = ['### 公众号草稿', ''];
  if (result.note) lines.push(result.note, '');
  lines.push('| 文章 | 结果 |', '| --- | --- |');
  if (!result.rows.length) lines.push('| （没有要同步的公众号文章） | |');
  for (const row of result.rows) lines.push(`| ${row.slug} | ${row.result} |`);
  lines.push('');
  return `${lines.join('\n')}\n`;
}

async function materialize(candidate, run, deps) {
  const { post, wx } = candidate;
  if (!post.rendered) throw new Error(`content/posts/${post.slug}/index.md 没有渲染结果`);
  const urls = [];
  for (const image of post.rendered.images) {
    const resolved = resolveLocalImage(post.dir, image.path);
    if (!resolved || resolved.remote || resolved.outside || !resolved.exists) {
      throw new Error(`content/posts/${post.slug} 的图片 ${image.path} 不能上传到公众号`);
    }
    const encoded = await deps.transcodeBodyImage(resolved.absolute);
    const file = path.join(deps.dir, `${post.slug}-${urls.length + 1}${encoded.extension}`);
    writeFileSync(file, encoded.buffer);
    const uploaded = callJson(run, ['upload-image', '--file', file]);
    if (!uploaded.url) throw new Error(`content/posts/${post.slug} 的图片没有返回地址`);
    urls.push(uploaded.url);
  }
  const content = applyImageUrls(post.rendered.html, post.rendered.images, urls);
  for (const match of content.matchAll(/src="([^"]+)"/g)) {
    if (!urls.includes(match[1])) {
      throw new Error(`content/posts/${post.slug} 还有外链图片，不能放进草稿`);
    }
  }
  const chars = charLength(content);
  const bytes = Buffer.byteLength(content);
  if (chars >= HTML_CHAR_LIMIT || bytes >= HTML_BYTE_LIMIT) {
    throw new Error(`content/posts/${post.slug} 的公众号 HTML 超过上限`);
  }
  let thumb = '';
  if (wx.cover) {
    const resolved = resolveLocalImage(post.dir, wx.cover);
    if (!resolved || resolved.remote || resolved.outside || !resolved.exists) {
      throw new Error(`content/posts/${post.slug} 的封面 ${wx.cover} 不能上传到公众号`);
    }
    const cover = path.join(deps.dir, `${post.slug}-cover.jpg`);
    await deps.writeCover(resolved.absolute, cover, wx.coverCrop);
    const uploaded = callJson(run, ['upload-cover', '--file', cover]);
    if (!uploaded.media_id) throw new Error(`content/posts/${post.slug} 的封面没有返回 media_id`);
    thumb = uploaded.media_id;
  }
  const payload = draftPayload(wx, content, thumb);
  const file = path.join(deps.dir, `${post.slug}-draft.json`);
  writeFileSync(file, JSON.stringify(payload));
  const added = callJson(run, ['add-draft', '--file', file]);
  if (!added.media_id) throw new Error(`content/posts/${post.slug} 没有返回草稿 media_id`);
  return added.media_id;
}

/**
 * @param {{
 *   repoRoot?: string,
 *   postsDir?: string,
 *   posts?: { post: any, wx: any }[],
 *   site?: { author: string, url: string },
 *   env?: NodeJS.ProcessEnv,
 *   run?: (args: string[]) => { code: number, stdout: string, stderr: string },
 *   log?: (message: string) => void,
 * }} options
 */
export async function syncWechatDrafts(options = {}) {
  const env = options.env ?? process.env;
  const log = options.log ?? console.log;
  const repoRoot = options.repoRoot ?? path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
  const site = options.site ?? readSiteConfig(repoRoot);
  let candidates = options.posts;
  if (!candidates) {
    const postsDir = options.postsDir ?? path.join(repoRoot, 'content', 'posts');
    const lint = lintPosts(postsDir, { repoRoot });
    if (lint.errors.length) throw new Error(lint.errors.map((item) => item.message).join('\n'));
    candidates = candidatesFromLint(lint, site);
  }
  if (candidates.length === 0) {
    const result = { added: [], skipped: [], rows: [], note: '内容仓库里没有要同步的公众号文章。' };
    appendSummary(env, summaryText(result));
    log(result.note);
    return result;
  }
  if (!egressConfigured(env)) {
    throw new Error('有公众号文章需要同步，但没有配置 WECHAT_EGRESS_TOKEN，以及 WECHAT_EGRESS_SRV_HOST 或 WECHAT_EGRESS_ADDRESS。AppID 和 AppSecret 不在这里。');
  }
  const run = options.run ?? defaultRun(env);
  const drafts = callJson(run, ['list-drafts']);
  const published = callJson(run, ['list-published']);
  const existing = [...tagged(drafts.articles, 'draft'), ...tagged(published.articles, 'published')];
  const { missing, skipped } = selectMissing(candidates, existing);
  const rows = skipped.map((item) => ({
    slug: item.post.slug,
    result: item.where === 'published' ? '已发布，跳过' : '已在草稿箱，跳过',
  }));
  const added = [];
  const failures = [];
  const dir = mkdtempSync(path.join(tmpdir(), 'wx-draft-'));
  const deps = { dir, transcodeBodyImage, writeCover };
  try {
    for (const candidate of missing) {
      try {
        const mediaId = await materialize(candidate, run, deps);
        added.push({ slug: candidate.post.slug, mediaId });
        rows.push({ slug: candidate.post.slug, result: '已放入草稿箱' });
        log(`草稿 ${candidate.post.slug}`);
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        failures.push(message);
        rows.push({ slug: candidate.post.slug, result: `失败：${message}` });
      }
    }
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
  const result = {
    added,
    skipped,
    rows,
    note: '只把既不在草稿箱、也未发布的文章放进草稿箱。不群发，也不调用发表接口。',
  };
  appendSummary(env, summaryText(result));
  if (failures.length) throw new Error(failures.join('\n'));
  return result;
}
