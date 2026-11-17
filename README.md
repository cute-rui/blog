# 维护这个博客

这是框架仓库。站点用 Astro 7 和 Tailwind v4。构建产物是静态站点。文章在 `content/`，那是第二个 git 仓库。框架仓库的 `.gitignore` 忽略整个 `content/`。frontmatter 约定见 [content/README.md](content/README.md)。

## 安装运行环境

你需要 Node `>=22.12.0` 和 pnpm 12。`pnpm-workspace.yaml` 已经设置 `allowBuilds.esbuild`。如果 `pnpm install` 拦截构建脚本，执行 `pnpm approve-builds esbuild`。

## 运行本地站点

在框架仓库根目录执行这些命令。

- `pnpm install` 安装依赖。
- `pnpm dev` 启动开发服务器。
- `pnpm build` 把静态站点写到 `dist/`。
- `pnpm preview` 在本地打开构建好的 `dist/`。
- `pnpm check` 运行 `astro check`，检查 Astro 模板和 TypeScript 类型。

先执行 `pnpm build`，再执行 `pnpm preview`。

## 写一篇文章

文章在内容仓库的 `content/posts/<slug>/index.md`。目录名就是页面路径：`content/posts/hello-world/index.md` 对应 `/posts/hello-world/`。图片放在同一目录，正文用 `./figure.png` 这样的相对路径。`content/posts/hello-world.md` 这种单文件是旧布局，校验会要求你挪进目录。

front matter 以 [schema/content-schema.json](schema/content-schema.json) 为准。在原有字段上增加了 `publish` 和 `wechat`。`publish` 整块不写时等于只发博客、不发生成公众号版本。`publish.blog: false` 的文章不进网站。`draft: true` 时两个目标都不发。发给公众号的文章必须是 `.md`。

这个框架仓库的 `.gitignore` 忽略整个 `content/`。文章在公开仓库 [cute-rui/blog-source](https://github.com/cute-rui/blog-source) 的 `posts/` 里，目录约定仍是 [templates/blog-content/README.md](templates/blog-content/README.md)。

## 改站点信息

编辑 `src/site.config.ts` 里的 `title`、`author`、`url` 和 `nav`。`author` 是 `lightwing`。`url` 现在仍是 `https://example.com`。你部署之后再改成真实域名。

站点标志是 [`public/logo.jpg`](public/logo.jpg)。页头、侧栏、页脚、首页和关于页用它，标签图标也指向它。`bio` 只出现在侧栏作者卡片。关于页的标语和正文写在 `src/pages/about.astro`。`seal` 只留给 [`src/pages/favicon.svg.ts`](src/pages/favicon.svg.ts) 那张备用图标。404 页的「迷」不读这个字段。`nav` 里有 `/archive/`，对应 `src/pages/archive.astro`。

## 页面布局

首页、文章列表、标签详情页和归档页用 `src/layouts/SidebarLayout.astro`，右侧是 `src/components/Sidebar.astro`：作者卡片、文章数 / 标签数 / 字数、标签、按年归档和 Atom 订阅入口，桌面端随页面吸顶。文章页用 `PostLayout.astro`，右侧是目录，目录项随滚动高亮，顶部有一条阅读进度线（用 CSS 滚动驱动动画实现，不支持的浏览器自动隐藏）。

文章卡片 `PostCard.astro` 带预览图。有 `heroImage` 的文章在构建时裁成 16:10 缩略图（首页头条为 2:1）；没有的文章由 `CoverFallback.astro` 根据标题生成一张几何占位图，随主题变色。详见 [content/README.md](content/README.md) 的"封面图与预览图"一节。

## 改配色

编辑 `src/styles/global.css` 里 `:root` 和 `[data-theme="dark"]` 两段令牌。颜色令牌是 `--canvas`、`--surface`、`--surface-2`、`--ink`、`--muted`、`--line`、`--line-soft`、`--accent`、`--accent-hover`、`--accent-fg`、`--accent-soft`，另有 `--shadow-rgb`（阴影颜色）和 `--grain-opacity`（页面纸张纹理的强度，设为 0 即关闭）。色值以该文件为准。

## 改字体

同一文件的 `:root` 里有三组字体栈。`--font-display` 用于标题、站名和数字，默认是 Source Serif 4 加 Noto Serif SC；`--font-code` 用于日期、标签等元信息，默认是 IBM Plex Mono；`--font-body` 是正文，用系统字体。前两组从 Google Fonts 异步加载（见 `src/components/BaseHead.astro` 里的 `fontsHref`），加载不阻塞首屏，加载失败时回退到 `--font-display` 里列出的系统宋体或黑体。不想依赖外部字体，删掉 `BaseHead.astro` 里那几行 `<link>` 即可，样式不需要改。

## 部署站点

`pnpm build` 会先编译 `md2wx`，再校验 `content/posts`，然后把静态站点写到 `dist/`。内容仓库还没检出时，`content/posts` 为空，站点仍然能构建。构建机上要同时有框架仓库和内容仓库，内容放在 `./content`。

GitHub Actions 在 [.github/workflows/publish.yml](.github/workflows/publish.yml)。它检出 [cute-rui/blog-source](https://github.com/cute-rui/blog-source)。那个仓库 push 到 `main` 之后，可以按 [templates/blog-content/.github/workflows/notify.yml](templates/blog-content/.github/workflows/notify.yml) 用 GitHub App 安装令牌发 `repository_dispatch`。没有 `CLOUDFLARE_API_TOKEN` 时，workflow 会在 summary 里说明并跳过 `wrangler pages deploy`。

同一次运行会编译 `wechat-egress` 客户端，向固定 IP 上的 gRPC 服务查询草稿箱和已发布列表，只把两边都没有的公众号文章放进草稿箱。Actions 不调用微信接口，不群发，也不发表。AppID 和 AppSecret 只在 VPS 的 TOML 里。没有 `WECHAT_EGRESS_TOKEN` 和 `WECHAT_EGRESS_SRV_HOST` 时，只要有公众号文章，同步这一步就失败；网站部署已经在它之前完成。

## 公众号复制页

`publish.wechat: true` 且不是草稿的文章，会在构建时得到 `dist/wx/<slug>-<key>/`。`<key>` 是 `HMAC-SHA256(WX_PAGE_KEY, slug)` 的前 10 位十六进制。没设置 `WX_PAGE_KEY` 时用 `dev-insecure-wx-page-key`，这个值写在日志里，不是密钥。

`dist/wx/index.json` 的顶层 `wechatMode` 来自环境变量 `WECHAT_MODE`，缺省是 `html`。同一份清单也写到 `dist/wx/<catalogKey>/index.json`，`catalogKey` 是对字符串 `index` 做同样的 HMAC。App 首次配置填这个带前缀的地址。部署步骤会从公开目录去掉可猜的 `/wx/index.json`。

复制页上的按钮是中文：复制正文、复制标题、复制摘要、下载封面、复制阅读原文链接。页面带 `noindex`，`public/_headers` 给 `/wx/*` 加了 `X-Robots-Tag: noindex`。

Markdown 转公众号 HTML 在 [md2wx/README.md](md2wx/README.md)。`pnpm test:md2wx` 跑快照，`pnpm lint:content` 跑第 3.2 节的校验。

## 在自己的电脑上做公众号探针

不要把 AppSecret 写进仓库，也不要在 CI 里跑这个探针。步骤写在 [tools/wechat-probe/probe.mjs](tools/wechat-probe/probe.mjs) 开头。粘贴样本是 [tools/wechat-probe/sample-paste.html](tools/wechat-probe/sample-paste.html)。脚本把结果表写到 `wechat-probe-result.md`，这个文件已被 gitignore。

## 查看仓库路径

这些路径现在都在仓库里。

- `astro.config.mjs`
- `package.json`
- `pnpm-workspace.yaml`
- `tsconfig.json`
- `src/pages/favicon.svg.ts`
- `src/site.config.ts`
- `src/content.config.ts`
- `src/styles/global.css`
- `src/lib/`
- `src/layouts/`
- `src/components/`
- `src/pages/`
- `src/integrations/wx-pages.ts`
- `schema/content-schema.json`
- `md2wx/`
- `templates/blog-content/`
- `tools/wechat-probe/`
- `.github/workflows/publish.yml`
- `content/`
