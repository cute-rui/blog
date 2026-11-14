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

在 `content/posts/` 下新建文件。文件名就是页面路径。`hello-world.md` 对应 `/posts/hello-world/`。字段、图片和草稿规则见 [content/README.md](content/README.md)。

## 改站点信息

编辑 `src/site.config.ts` 里的 `title`、`author`、`url` 和 `nav`。`url` 现在仍是 `https://example.com`。你部署之后再改成真实域名。

另外三个字段控制侧栏和品牌标识。`bio` 是侧栏作者卡片和关于页里的一句话简介。`seal` 是印章里的那个字，页头、侧栏、页脚、404 页和网站图标（`src/pages/favicon.svg.ts`）都用它。`nav` 里现在多了 `/archive/`，对应按年份归档的页面 `src/pages/archive.astro`。

## 页面布局

首页、文章列表、标签详情页和归档页用 `src/layouts/SidebarLayout.astro`，右侧是 `src/components/Sidebar.astro`：作者卡片、文章数 / 标签数 / 字数、标签、按年归档和 Atom 订阅入口，桌面端随页面吸顶。文章页用 `PostLayout.astro`，右侧是目录，目录项随滚动高亮，顶部有一条阅读进度线（用 CSS 滚动驱动动画实现，不支持的浏览器自动隐藏）。

文章卡片 `PostCard.astro` 带预览图。有 `heroImage` 的文章在构建时裁成 16:10 缩略图（首页头条为 2:1）；没有的文章由 `CoverFallback.astro` 根据标题生成一张几何占位图，随主题变色。详见 [content/README.md](content/README.md) 的"封面图与预览图"一节。

## 改配色

编辑 `src/styles/global.css` 里 `:root` 和 `[data-theme="dark"]` 两段令牌。颜色令牌是 `--canvas`、`--surface`、`--surface-2`、`--ink`、`--muted`、`--line`、`--line-soft`、`--accent`、`--accent-hover`、`--accent-fg`、`--accent-soft`，另有 `--shadow-rgb`（阴影颜色）和 `--grain-opacity`（页面纸张纹理的强度，设为 0 即关闭）。色值以该文件为准。

## 改字体

同一文件的 `:root` 里有三组字体栈。`--font-display` 用于标题、站名和数字，默认是 Source Serif 4 加 Noto Serif SC；`--font-code` 用于日期、标签等元信息，默认是 IBM Plex Mono；`--font-body` 是正文，用系统字体。前两组从 Google Fonts 异步加载（见 `src/components/BaseHead.astro` 里的 `fontsHref`），加载不阻塞首屏，加载失败时回退到 `--font-display` 里列出的系统宋体或黑体。不想依赖外部字体，删掉 `BaseHead.astro` 里那几行 `<link>` 即可，样式不需要改。

## 部署站点

执行 `pnpm build`，产物在 `dist/`。构建机上必须同时检出框架仓库和内容仓库。两种做法写在 [content/README.md](content/README.md)。一种是分别克隆两个仓库。另一种是以后把 `content/` 改成 git submodule。

这个项目没有 Astro adapter。把 `dist/` 交给 GitHub Pages、Vercel、Netlify 或 Cloudflare Pages。

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
- `content/`
