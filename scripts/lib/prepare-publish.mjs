import { spawnSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';

function git(cwd, args, { encoding = 'utf8', input, maxBuffer = 64 * 1024 * 1024 } = {}) {
  return spawnSync('git', args, { cwd, encoding, input, maxBuffer });
}

export function parsePublishTo(raw) {
  if (typeof raw !== 'string' || raw.trim() === '') return null;
  const value = raw.trim().toLowerCase();
  if (value === 'none') return { blog: false, wechat: false };
  const parts = value.split(/[,\s]+/).filter(Boolean);
  const blog = parts.includes('blog');
  const wechat = parts.includes('wechat');
  if (!blog && !wechat) {
    throw new Error(`Publish-To 无法识别：${raw.trim()}`);
  }
  return { blog, wechat };
}

export function slugFromPostsPath(filePath) {
  const norm = filePath.replaceAll('\\', '/').replace(/^\.\//, '');
  if (!norm.startsWith('posts/')) return null;
  const first = norm.slice('posts/'.length).split('/')[0];
  if (!first || first === '.' || first === '..') return null;
  return first.replace(/\.mdx?$/, '');
}

function isZeroSha(sha) {
  return typeof sha === 'string' && /^0+$/.test(sha);
}

function copyTree(src, dest) {
  rmSync(dest, { recursive: true, force: true });
  mkdirSync(dest, { recursive: true });
  if (!existsSync(src)) return;
  for (const name of readdirSync(src)) {
    cpSync(path.join(src, name), path.join(dest, name), { recursive: true });
  }
}

function removeSlug(postsDir, slug) {
  rmSync(path.join(postsDir, slug), { recursive: true, force: true });
  rmSync(path.join(postsDir, `${slug}.md`), { force: true });
  rmSync(path.join(postsDir, `${slug}.mdx`), { force: true });
}

function extractSlug(contentDir, sha, slug, postsDir) {
  const specs = [`posts/${slug}`, `posts/${slug}.md`, `posts/${slug}.mdx`];
  let found = false;
  for (const spec of specs) {
    const exists = git(contentDir, ['cat-file', '-e', `${sha}:${spec}`]);
    if (exists.status !== 0) continue;
    const archive = git(contentDir, ['archive', sha, spec], { encoding: 'buffer' });
    if (archive.status !== 0 || !archive.stdout || archive.stdout.length === 0) continue;
    const tmp = mkdtempSync(path.join(tmpdir(), 'wx-archive-'));
    try {
      const extracted = spawnSync('tar', ['-x', '-C', tmp], { input: archive.stdout });
      if (extracted.status !== 0) {
        throw new Error(extracted.stderr?.toString() || `无法解出 ${spec}`);
      }
      const fromDir = path.join(tmp, 'posts', slug);
      if (existsSync(fromDir)) cpSync(fromDir, path.join(postsDir, slug), { recursive: true });
      for (const ext of ['.md', '.mdx']) {
        const fromFile = path.join(tmp, 'posts', `${slug}${ext}`);
        if (existsSync(fromFile)) cpSync(fromFile, path.join(postsDir, `${slug}${ext}`));
      }
      found = true;
    } finally {
      rmSync(tmp, { recursive: true, force: true });
    }
  }
  return found;
}

function withOldSlugs(snapshot, contentDir, before, slugs, dest) {
  copyTree(snapshot, dest);
  for (const slug of slugs) {
    removeSlug(dest, slug);
    extractSlug(contentDir, before, slug, dest);
  }
  return dest;
}

function changedSlugs(contentDir, before, after) {
  const diff = git(contentDir, ['diff', '--name-only', before, after, '--', 'posts']);
  if (diff.status !== 0) {
    throw new Error((diff.stderr || `git diff ${before} ${after} 失败`).trim());
  }
  const slugs = new Set();
  for (const line of diff.stdout.split('\n')) {
    const slug = slugFromPostsPath(line.trim());
    if (slug) slugs.add(slug);
  }
  return [...slugs];
}

function allSlugs(postsDir) {
  if (!existsSync(postsDir)) return [];
  return readdirSync(postsDir, { withFileTypes: true })
    .filter((entry) => !entry.name.startsWith('.'))
    .map((entry) => entry.name.replace(/\.mdx?$/, ''));
}

function trailer(contentDir) {
  const result = git(contentDir, ['log', '-1', '--format=%(trailers:key=Publish-To,valueonly)']);
  if (result.status !== 0) throw new Error((result.stderr || '无法读取 Publish-To').trim());
  const lines = result.stdout.split('\n').map((line) => line.trim()).filter(Boolean);
  if (!lines.length) return null;
  return parsePublishTo(lines[lines.length - 1]);
}

function writePlan(repoRoot, plan) {
  const dir = path.join(repoRoot, '.publish');
  mkdirSync(dir, { recursive: true });
  writeFileSync(path.join(dir, 'plan.json'), `${JSON.stringify(plan, null, 2)}\n`);
}

export function preparePublish({
  repoRoot,
  eventName = '',
  before = '',
  after = '',
  targetsInput = '',
  slugsInput = '',
}) {
  const contentDir = path.join(repoRoot, 'content');
  const postsDir = path.join(contentDir, 'posts');
  if (eventName !== 'repository_dispatch') {
    const requested = parsePublishTo(targetsInput === 'both' ? 'blog,wechat' : targetsInput);
    const plan = {
      event: eventName,
      publishTo: targetsInput && targetsInput !== 'both' ? requested : null,
      changedSlugs: slugsInput.split(',').map((slug) => slug.trim()).filter(Boolean),
      wxPostsDir: null,
      narrowed: false,
    };
    writePlan(repoRoot, plan);
    return plan;
  }

  if (!existsSync(path.join(contentDir, '.git'))) {
    throw new Error('content 不是 git 检出，无法按 Publish-To 选择文章版本。');
  }
  const head = after || git(contentDir, ['rev-parse', 'HEAD']).stdout.trim();
  let publishTo = null;
  let slugs = [];
  if (!before || isZeroSha(before)) {
    slugs = allSlugs(postsDir);
  } else {
    publishTo = trailer(contentDir);
    slugs = changedSlugs(contentDir, before, head);
  }

  const publishDir = path.join(repoRoot, '.publish');
  rmSync(publishDir, { recursive: true, force: true });
  mkdirSync(publishDir, { recursive: true });
  const snapshot = path.join(publishDir, 'new-posts');
  copyTree(postsDir, snapshot);

  const siteNeedsOld = publishTo && !publishTo.blog ? slugs : [];
  const wxNeedsOld = publishTo && !publishTo.wechat ? slugs : [];
  const siteDir = siteNeedsOld.length
    ? withOldSlugs(snapshot, contentDir, before, siteNeedsOld, path.join(publishDir, 'site-posts'))
    : null;
  const wxDir = wxNeedsOld.length
    ? withOldSlugs(snapshot, contentDir, before, wxNeedsOld, path.join(publishDir, 'wx-posts'))
    : null;
  if (siteDir) copyTree(siteDir, postsDir);

  let wxPostsDir = null;
  if (wxDir) wxPostsDir = path.relative(repoRoot, wxDir);
  else if (siteDir) wxPostsDir = path.relative(repoRoot, snapshot);

  const plan = {
    event: eventName,
    publishTo,
    changedSlugs: slugs,
    wxPostsDir,
    narrowed: Boolean(publishTo),
  };
  writePlan(repoRoot, plan);
  return plan;
}
