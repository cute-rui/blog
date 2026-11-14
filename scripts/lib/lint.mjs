import path from 'node:path';
import {
  HTML_BYTE_LIMIT,
  HTML_CHAR_LIMIT,
  charLength,
  listPosts,
  loadSchema,
  normalizePublish,
  postFiles,
  readSiteConfig,
  resolveLocalImage,
  schemaErrors,
} from './content.mjs';
import { findMd2wx, renderMarkdown } from './md2wx.mjs';

function issue(file, message) {
  return { file, message: `${file} ${message}` };
}

function dateOk(value) {
  return typeof value === 'string' && value.trim() !== '' && Number.isFinite(Date.parse(value));
}

export function lintPosts(postsDir, { repoRoot, requireBinary = true } = {}) {
  const found = listPosts(postsDir);
  const errors = found.errors.map((message) => ({ file: postsDir, message }));
  const warnings = [];
  if (found.missing) {
    return { missing: true, posts: [], errors, warnings };
  }

  const { validate, versionError } = loadSchema(repoRoot);
  if (versionError) errors.push({ file: 'content/content-schema.json', message: versionError });
  const site = readSiteConfig(repoRoot);
  const bin = findMd2wx(repoRoot);
  const needsRender = found.posts.some((post) => post.ext === '.md');
  if (needsRender && !bin && requireBinary) {
    errors.push({
      file: 'md2wx',
      message: '没有找到 md2wx。先执行 cargo build --release --manifest-path md2wx/Cargo.toml。',
    });
  }

  const posts = [];
  for (const post of found.posts) {
    const rel = path.posix.join('content/posts', post.slug, `index${post.ext}`);
    if (!post.hasFrontMatter) {
      errors.push(issue(rel, '缺少 front matter。'));
      continue;
    }
    const checked = schemaErrors(validate, post.data);
    for (const message of checked.errors) errors.push(issue(rel, `不符合 content-schema.json：${message}`));
    if (checked.errors.length) continue;
    if (!dateOk(checked.data.pubDate)) errors.push(issue(rel, 'pubDate 不是日期。'));
    if (checked.data.updatedDate !== undefined && !dateOk(checked.data.updatedDate)) {
      errors.push(issue(rel, 'updatedDate 不是日期。'));
    }
    const crop = checked.data.wechat?.coverCrop;
    if (crop && (crop.x2 <= crop.x1 || crop.y2 <= crop.y1)) {
      errors.push(issue(rel, 'coverCrop 需要 x1 < x2 且 y1 < y2。'));
    }

    const publish = normalizePublish(checked.data);
    const next = { ...post, data: { ...checked.data, publish } };
    if (publish.wechat && post.ext !== '.md') {
      errors.push(issue(rel, '设置了 publish.wechat，但公众号只接受 Markdown。请改成 index.md。'));
    }

    const wechat = checked.data.wechat ?? {};
    const title = wechat.title || checked.data.title;
    const author = wechat.author || site.author;
    const digest = wechat.digest || checked.data.description;
    if (publish.wechat) {
      if (charLength(title) > 32) {
        errors.push(issue(rel, `公众号标题有 ${charLength(title)} 字，上限 32。请填写 wechat.title。`));
      }
      if (charLength(author) > 16) {
        errors.push(issue(rel, `公众号作者有 ${charLength(author)} 字，上限 16。`));
      }
      if (charLength(digest) > 120) {
        errors.push(issue(rel, `公众号摘要有 ${charLength(digest)} 字，上限 120。请缩短 description 或填写 wechat.digest。`));
      }
    }

    if (post.ext === '.md' && bin) {
      const rendered = renderMarkdown(bin, post.body);
      if (!rendered.ok) {
        errors.push(issue(rel, `Markdown 不能渲染成公众号 HTML：${rendered.error}`));
      } else {
        next.rendered = rendered;
        if (publish.wechat && (rendered.charCount >= HTML_CHAR_LIMIT || rendered.byteLen >= HTML_BYTE_LIMIT)) {
          errors.push(
            issue(
              rel,
              `公众号 HTML 有 ${rendered.charCount} 字符、${rendered.byteLen} 字节，上限是 ${HTML_CHAR_LIMIT} 字符且小于 1 MB。`,
            ),
          );
        }
        for (const image of rendered.images) {
          const resolved = resolveLocalImage(post.dir, image.path);
          if (!resolved || resolved.remote) continue;
          if (resolved.outside) errors.push(issue(rel, `图片 ${image.path} 跑到了文章目录外面。`));
          else if (!resolved.exists) errors.push(issue(rel, `引用了 ${image.path}，文件不存在。`));
        }
        const remote = [...rendered.html.matchAll(/<img[^>]*src="(https?:[^"]+)"/g)].map((match) => match[1]);
        for (const url of remote) warnings.push(issue(rel, `外链图片不会在构建时压缩：${url}`));
      }
    }

    const coverRef = wechat.cover || checked.data.heroImage;
    if (typeof coverRef === 'string' && coverRef !== '') {
      const resolved = resolveLocalImage(post.dir, coverRef);
      if (resolved && !resolved.remote) {
        if (resolved.outside) errors.push(issue(rel, `封面 ${coverRef} 跑到了文章目录外面。`));
        else if (!resolved.exists) errors.push(issue(rel, `封面 ${coverRef} 不存在。`));
      }
    }
    for (const name of postFiles(post.dir)) {
      if (name.toLowerCase().endsWith('.gif')) {
        warnings.push(issue(rel, `${name} 是 GIF，复制页只保留第一帧。`));
      }
    }
    posts.push(next);
  }

  return { missing: false, posts, errors, warnings };
}
