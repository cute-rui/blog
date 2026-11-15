import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import sharp from 'sharp';
import { DEV_PAGE_KEY, hmacPrefix, loadSchema, repoRoot, schemaErrors } from './lib/content.mjs';
import { lintPosts } from './lib/lint.mjs';
import { parsePublishTo, preparePublish, slugFromPostsPath } from './lib/prepare-publish.mjs';
import { renderSummary } from './lib/summary.mjs';
import { buildWxPages } from './lib/wx-pages.mjs';
import { redact, renderTable, runProbe, solidPng } from '../tools/wechat-probe/probe.mjs';

const root = repoRoot();

test('page key is HMAC-SHA256, first 10 hex characters', () => {
  assert.equal(hmacPrefix(DEV_PAGE_KEY, 'hello'), 'b56e83532c');
  assert.equal(hmacPrefix(DEV_PAGE_KEY, 'index'), 'c4643fb6a1');
  assert.equal(DEV_PAGE_KEY, 'dev-insecure-wx-page-key');
});

test('schema rejects unknown fields and supplies publish defaults', () => {
  const { validate, schema } = loadSchema(root);
  assert.equal(schema.version, 1);
  const bad = schemaErrors(validate, {
    title: '标题',
    description: '摘要',
    pubDate: '2026-11-14',
    extra: true,
  });
  assert.ok(bad.errors.length > 0);
  const good = schemaErrors(validate, { title: '标题', description: '摘要', pubDate: '2026-11-14' });
  assert.deepEqual(good.errors, []);
  assert.equal(good.data.publish.blog, true);
  assert.equal(good.data.publish.wechat, false);
  assert.equal(good.data.draft, false);
});

test('lint enforces the wechat rules', () => {
  const dir = mkdtempSync(path.join(tmpdir(), 'wx-lint-'));
  const posts = path.join(dir, 'posts');
  writePost(posts, 'long', {
    title: '一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五',
    description: '摘要',
    publish: { wechat: true },
    body: '正文\n',
  });
  writePost(posts, 'component', {
    title: '组件',
    description: '摘要',
    ext: '.mdx',
    publish: { wechat: true },
    body: '正文\n',
  });
  writeFileSync(path.join(posts, 'old-layout.md'), '---\ntitle: 旧\n---\n');
  const result = lintPosts(posts, { repoRoot: root });
  const messages = result.errors.map((error) => error.message).join('\n');
  assert.match(messages, /上限 32/);
  assert.match(messages, /只接受 Markdown/);
  assert.match(messages, /旧的单文件布局/);
});

test('lint rejects mermaid and warns on gif', () => {
  const dir = mkdtempSync(path.join(tmpdir(), 'wx-lint-'));
  const posts = path.join(dir, 'posts');
  writePost(posts, 'diagram', {
    title: '图',
    description: '摘要',
    body: '```mermaid\ngraph TD\n```\n',
  });
  writeFileSync(path.join(posts, 'diagram', 'anim.gif'), 'gif');
  const result = lintPosts(posts, { repoRoot: root });
  assert.ok(result.errors.some((error) => error.message.includes('mermaid') || error.message.includes('diagram')));
  assert.ok(result.warnings.some((warning) => warning.message.includes('GIF')));
});

test('copy pages are Chinese, unlisted, and keyed', async () => {
  const posts = mkdtempSync(path.join(tmpdir(), 'wx-posts-'));
  const outDir = mkdtempSync(path.join(tmpdir(), 'wx-out-'));
  const png = await sharp({
    create: { width: 48, height: 24, channels: 3, background: { r: 42, g: 90, b: 134 } },
  })
    .png()
    .toBuffer();
  writePost(posts, 'demo', {
    title: '复制页标题',
    description: '复制页摘要',
    heroImage: './dot.png',
    publish: { blog: true, wechat: true },
    body: '一段正文，见 [文档](https://example.com/docs)。\n\n![点](./dot.png)\n',
  });
  writeFileSync(path.join(posts, 'demo', 'dot.png'), png);
  writePost(posts, 'quiet', {
    title: '只发公众号',
    description: '不进网站',
    publish: { blog: false, wechat: true },
    body: '只有复制页。\n',
  });
  writePost(posts, 'hidden', {
    title: '草稿',
    description: '先不发',
    draft: true,
    publish: { wechat: true },
    body: '草稿正文。\n',
  });

  const previousKey = process.env.WX_PAGE_KEY;
  const previousMode = process.env.WECHAT_MODE;
  delete process.env.WX_PAGE_KEY;
  process.env.WECHAT_MODE = 'draft';
  try {
    const index = await buildWxPages({
      repoRoot: root,
      outDir,
      postsDir: posts,
      log() {},
      warn() {},
    });
    assert.equal(index.wechatMode, 'draft');
    assert.equal(index.devPageKey, true);
    assert.deepEqual(
      index.pages.map((page) => page.slug),
      ['demo', 'quiet'],
    );
    const key = hmacPrefix(DEV_PAGE_KEY, 'demo');
    const pageDir = path.join(outDir, 'wx', `demo-${key}`);
    const html = readFileSync(path.join(pageDir, 'index.html'), 'utf8');
    const fragment = readFileSync(path.join(pageDir, 'content.html'), 'utf8');
    const meta = JSON.parse(readFileSync(path.join(pageDir, 'meta.json'), 'utf8'));
    assert.match(html, /复制正文/);
    assert.match(html, /复制标题/);
    assert.match(html, /复制摘要/);
    assert.match(html, /下载封面/);
    assert.match(html, /复制阅读原文链接/);
    assert.match(html, /noindex/);
    assert.match(html, /正文 .+ \/ 20,000 字符 · 评论：未指定/);
    assert.doesNotMatch(fragment, /\{\{wx-img:/);
    assert.match(fragment, /https:\/\/example.com\/wx\/demo-/);
    assert.match(fragment, /参考链接/);
    assert.equal(meta.contentHash, index.pages[0].contentHash);
    assert.ok(meta.coverUrl.endsWith('/cover.jpg'));
    const quietHtml = readFileSync(path.join(outDir, 'wx', `quiet-${hmacPrefix(DEV_PAGE_KEY, 'quiet')}`, 'index.html'), 'utf8');
    assert.match(quietHtml, /这篇不发博客，没有阅读原文链接/);
    assert.doesNotMatch(quietHtml, /复制阅读原文链接/);
    const catalog = readFileSync(path.join(outDir, 'wx', hmacPrefix(DEV_PAGE_KEY, 'index'), 'index.json'), 'utf8');
    assert.equal(catalog, readFileSync(path.join(outDir, 'wx', 'index.json'), 'utf8'));
    const summary = renderSummary(index, { changedSlugs: ['demo'], narrowed: false, publishTo: null });
    assert.match(summary, /模式：`draft`/);
    assert.match(summary, /dev-insecure-wx-page-key/);
    assert.match(summary, new RegExp(`demo-.+ \\| 是`));
  } finally {
    restoreEnv('WX_PAGE_KEY', previousKey);
    restoreEnv('WECHAT_MODE', previousMode);
  }
});

test('Publish-To wechat keeps the previous blog file and the new copy-page file', () => {
  const repo = mkdtempSync(path.join(tmpdir(), 'wx-repo-'));
  const content = path.join(repo, 'content');
  const posts = path.join(content, 'posts');
  writePost(posts, 'hello', { title: '标题', description: '摘要', body: 'BODY_V1\n' });
  git(content, ['init', '-b', 'main']);
  git(content, ['add', 'posts']);
  git(content, ['commit', '-m', '第一版']);
  const before = git(content, ['rev-parse', 'HEAD']);
  writePost(posts, 'hello', { title: '标题', description: '摘要', body: 'BODY_V2\n' });
  git(content, ['add', 'posts']);
  git(content, ['commit', '-m', '更新\n\nPublish-To: wechat']);
  const after = git(content, ['rev-parse', 'HEAD']);
  const plan = preparePublish({
    repoRoot: repo,
    eventName: 'repository_dispatch',
    before,
    after,
  });
  assert.deepEqual(plan.publishTo, { blog: false, wechat: true });
  assert.deepEqual(plan.changedSlugs, ['hello']);
  const site = readFileSync(path.join(content, 'posts', 'hello', 'index.md'), 'utf8');
  const wx = readFileSync(path.join(repo, plan.wxPostsDir, 'hello', 'index.md'), 'utf8');
  assert.match(site, /BODY_V1/);
  assert.match(wx, /BODY_V2/);
  rmSync(repo, { recursive: true, force: true });
});

test('Publish-To parser only accepts the four values', () => {
  assert.equal(parsePublishTo(''), null);
  assert.deepEqual(parsePublishTo('none'), { blog: false, wechat: false });
  assert.deepEqual(parsePublishTo('blog,wechat'), { blog: true, wechat: true });
  assert.equal(slugFromPostsPath('posts/hello/index.md'), 'hello');
  assert.throws(() => parsePublishTo('twitter'));
});

test('probe redacts secrets and records a missing credential without calling WeChat', async () => {
  assert.match(solidPng().subarray(0, 8).toString('hex'), /^89504e470d0a1a0a/);
  assert.equal(redact('access_token=abc secret'), 'access_token=REDACTED secret');
  let called = false;
  const missing = await runProbe({
    appid: '',
    secret: '',
    fetchImpl: async () => {
      called = true;
      throw new Error('should not fetch');
    },
  });
  assert.equal(called, false);
  assert.match(renderTable(missing.rows), /WECHAT_APPID/);
  const secret = 'super-secret-value';
  const token = 'token-value-should-not-leak';
  const probed = await runProbe({
    appid: 'wx-test',
    secret,
    fetchImpl: async (url) => {
      const target = String(url);
      if (target.includes('stable_token')) return json({ access_token: token, expires_in: 10 });
      if (target.includes('uploadimg')) return json({ url: 'https://mmbiz.qpic.cn/probe', errcode: 0 });
      if (target.includes('add_material')) return json({ media_id: 'thumb-id', errcode: 0 });
      if (target.includes('draft/add')) return json({ media_id: 'draft-id' });
      if (target.includes('draft/delete')) return json({ errcode: 0, errmsg: 'ok' });
      throw new Error(`unexpected ${target}`);
    },
  });
  const table = renderTable(probed.rows);
  assert.equal(probed.ok, true);
  assert.equal(table.includes(secret), false);
  assert.equal(table.includes(token), false);
  assert.match(table, /draft\/delete/);
});

test('paste sample has inline styles, a code block, and an https image', () => {
  const html = readFileSync(path.join(root, 'tools/wechat-probe/sample-paste.html'), 'utf8');
  assert.match(html, /style="/);
  assert.match(html, /&nbsp;&nbsp;/);
  assert.match(html, /https:\/\/upload\.wikimedia\.org\//);
});

function writePost(postsDir, slug, { title, description, body, ext = '.md', publish, draft, heroImage }) {
  const dir = path.join(postsDir, slug);
  mkdirSync(dir, { recursive: true });
  const lines = ['---', `title: ${title}`, `description: ${description}`, 'pubDate: 2026-11-14'];
  if (draft) lines.push('draft: true');
  if (heroImage) lines.push(`heroImage: ${heroImage}`);
  if (publish) {
    lines.push('publish:');
    if (publish.blog !== undefined) lines.push(`  blog: ${publish.blog}`);
    if (publish.wechat !== undefined) lines.push(`  wechat: ${publish.wechat}`);
  }
  lines.push('---', body);
  writeFileSync(path.join(dir, `index${ext}`), lines.join('\n'));
}

function git(cwd, args) {
  return execFileSync('git', ['-c', 'user.name=Probe', '-c', 'user.email=probe@example.com', ...args], {
    cwd,
    encoding: 'utf8',
  }).trim();
}

function restoreEnv(name, value) {
  if (value === undefined) delete process.env[name];
  else process.env[name] = value;
}

function json(body) {
  return { status: 200, text: async () => JSON.stringify(body) };
}
