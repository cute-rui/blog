function cell(value) {
  return String(value).replaceAll('|', '\\|');
}

export function renderSummary(index, plan) {
  const lines = ['## 公众号复制页', '', `模式：\`${index.wechatMode}\``];
  if (index.devPageKey) {
    lines.push(
      '',
      '未设置 `WX_PAGE_KEY`，复制页路径使用开发用假密钥 `dev-insecure-wx-page-key`。这不是密钥。正式发布前在博客仓库的 Actions 密钥里设置 `WX_PAGE_KEY`。',
    );
  }
  lines.push('', `App 首次配置填这个清单地址：${index.catalogUrl}`);
  if (plan?.narrowed && plan.publishTo) {
    lines.push(
      '',
      `这次 push 的 Publish-To 把目标收窄为 blog=${plan.publishTo.blog}、wechat=${plan.publishTo.wechat}。front matter 里关掉的目标不会被重新打开。`,
    );
  } else if (plan?.publishTo && plan.event === 'workflow_dispatch') {
    lines.push('', `手动触发的 targets 记为 blog=${plan.publishTo.blog}、wechat=${plan.publishTo.wechat}。复制页仍随整站生成。`);
  }
  lines.push('', '| 文章 | 复制页 | 本次改动 |', '| --- | --- | --- |');
  const changed = new Set(plan?.changedSlugs ?? []);
  if (!index.pages.length) lines.push('| （没有要生成复制页的文章） | | |');
  for (const page of index.pages) {
    lines.push(`| ${cell(page.slug)} | ${page.url} | ${changed.has(page.slug) ? '是' : ''} |`);
  }
  lines.push('');
  return lines.join('\n');
}
