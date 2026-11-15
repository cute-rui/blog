import { escapeHtml } from './content.mjs';

function commentLabel(openComment, fansOnly) {
  if (openComment === true && fansOnly === true) return '评论：仅粉丝可评论';
  if (openComment === true) return '评论：开启';
  if (openComment === false) return '评论：关闭';
  return '评论：未指定';
}

function commentHint(openComment, fansOnly) {
  if (openComment === true && fansOnly === true) return '粘贴后在公众号后台把评论设为仅粉丝可评论。';
  if (openComment === true) return '粘贴后在公众号后台打开评论。';
  if (openComment === false) return '粘贴后在公众号后台关闭评论。';
  return '这篇没有单独指定评论。在公众号后台按需要设置。';
}

export function renderCopyPage({
  title,
  author,
  digest,
  sourceUrl,
  blog,
  openComment,
  fansOnlyComment,
  charCount,
  hasCover,
  fragment,
  tokens,
}) {
  const count = charCount.toLocaleString('zh-CN');
  const comment = commentLabel(openComment, fansOnlyComment);
  const buttons = [
    '<button type="button" class="primary" id="copy-body">复制正文</button>',
    `<button type="button" data-copy="title" data-value="${escapeHtml(title)}">复制标题</button>`,
    `<button type="button" data-copy="digest" data-value="${escapeHtml(digest)}">复制摘要</button>`,
  ];
  if (hasCover) buttons.push('<a class="btn" href="./cover.jpg" download>下载封面</a>');
  if (sourceUrl) {
    buttons.push(
      `<button type="button" data-copy="source" data-value="${escapeHtml(sourceUrl)}">复制阅读原文链接</button>`,
    );
  }
  const sourceNote = !sourceUrl && blog === false ? '<p class="meta">这篇不发博客，没有阅读原文链接。</p>' : '';
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="robots" content="noindex, nofollow">
  <title>复制到公众号 · ${escapeHtml(title)}</title>
  <style>
    :root { color-scheme: light; }
    body {
      margin: 0;
      background: ${tokens.canvas};
      color: ${tokens.ink};
      font-family: ui-sans-serif, system-ui, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
    }
    .wrap { max-width: 677px; margin: 0 auto; padding: 28px 16px 72px; }
    .panel {
      background: ${tokens.surface2};
      border: 1px solid ${tokens.line};
      border-radius: 12px;
      padding: 16px;
    }
    h1 { margin: 8px 0; font-size: 22px; line-height: 1.35; font-weight: 700; }
    .meta { margin: 0; color: ${tokens.muted}; font-size: 13px; line-height: 1.6; }
    .actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
    button, .btn {
      appearance: none;
      border: 1px solid ${tokens.line};
      background: ${tokens.surface2};
      color: ${tokens.ink};
      border-radius: 999px;
      padding: 8px 14px;
      font: inherit;
      font-size: 14px;
      line-height: 1.2;
      text-decoration: none;
      cursor: pointer;
    }
    button.primary { background: ${tokens.accent}; color: ${tokens.accentFg}; border-color: ${tokens.accent}; }
    .status { min-height: 1.4em; margin: 10px 0 0; color: ${tokens.accent}; font-size: 13px; }
    .article {
      margin-top: 16px;
      background: ${tokens.surface2};
      border: 1px solid ${tokens.line};
      border-radius: 12px;
      padding: 20px 16px 8px;
    }
  </style>
</head>
<body>
  <div class="wrap">
    <div class="panel">
      <p class="meta">公众号复制页。站点导航、订阅和站点地图里都没有这一页。</p>
      <h1>${escapeHtml(title)}</h1>
      <p class="meta">作者：${escapeHtml(author)}</p>
      <p class="meta">正文 ${count} / 20,000 字符 · ${escapeHtml(comment)}</p>
      <div class="actions">
        ${buttons.join('\n        ')}
      </div>
      <p class="status" id="status" aria-live="polite"></p>
      <p class="meta">在公众号后台新建图文，把正文粘贴进去。编辑器会把图片转存到微信。</p>
      <p class="meta">${escapeHtml(commentHint(openComment, fansOnlyComment))}</p>
      ${sourceNote}
    </div>
    <article class="article" id="wx-body">
      ${fragment}
    </article>
  </div>
  <script>
    const status = document.getElementById('status');
    const article = document.getElementById('wx-body');
    function say(text) { status.textContent = text; }
    async function copyText(text, label) {
      try {
        await navigator.clipboard.writeText(text);
        say('已复制' + label);
      } catch (err) {
        say('复制失败，请手动选择');
      }
    }
    async function copyBody() {
      let html = article.innerHTML;
      try {
        const response = await fetch('content.html');
        if (response.ok) html = await response.text();
      } catch (err) {}
      const plain = article.innerText;
      try {
        if (navigator.clipboard && window.ClipboardItem) {
          await navigator.clipboard.write([
            new ClipboardItem({
              'text/html': new Blob([html], { type: 'text/html' }),
              'text/plain': new Blob([plain], { type: 'text/plain' })
            })
          ]);
          say('已复制正文');
          return;
        }
      } catch (err) {}
      const range = document.createRange();
      range.selectNodeContents(article);
      const selection = window.getSelection();
      selection.removeAllRanges();
      selection.addRange(range);
      const ok = document.execCommand('copy');
      say(ok ? '已复制正文' : '复制失败，请手动选择正文');
    }
    document.getElementById('copy-body').addEventListener('click', copyBody);
    for (const button of document.querySelectorAll('[data-copy]')) {
      button.addEventListener('click', () => {
        const kind = button.getAttribute('data-copy');
        const label = kind === 'title' ? '标题' : kind === 'digest' ? '摘要' : '阅读原文链接';
        copyText(button.getAttribute('data-value') || '', label);
      });
    }
  </script>
</body>
</html>
`;
}
