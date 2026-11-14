# md2wx

把 CommonMark 加一小段 GFM 渲染成公众号能粘贴的 HTML。样式都写在元素的 `style` 上。

这是一个纯函数库，没有文件读写，也没有网络。上传图片、拼复制页、调用微信接口都在库外面做。Android 绑定以后再接这个 crate，本目录没有 UniFFI，也没有 `android/` 目标。

## 公开 API

```rust
use md2wx::{render, RenderOptions, Theme};

let theme = Theme::blog();
let rendered = render("见 [文档](https://example.com)。\n", &RenderOptions::new(&theme))?;
assert!(rendered.html.contains("参考链接"));
assert_eq!(rendered.char_count, rendered.html.chars().count());
```

| 类型 | 作用 |
| --- | --- |
| `Theme` | 六个十六进制颜色。`Theme::blog()` 对应站点浅色令牌（`src/styles/global.css` 的 `--accent` 等） |
| `RenderOptions` | `theme`，以及可选的 `image_base_url` |
| `render` | 输入 Markdown 正文，返回 `Rendered` |
| `Rendered` | `html` 片段、`images`、`char_count`（Unicode 标量）、`byte_len`（UTF-8 字节） |
| `ImageRef` | `placeholder`（`{{wx-img:N}}`）和 Markdown 里的 `path` |
| `RenderError` | 不支持的语法、高亮失败、主题色不是十六进制 |

`html` 是一段 `<section>`，不是完整文档。本地图片在 `image_base_url` 为空时，`src` 写成占位符，由调用方替换成最终地址。`image_base_url` 非空时，`src` 为 `{base}/{文件名}`，`images` 里仍保留占位符和原始路径。`https://` 图片原样保留，不进 `images`。

已启用的语法：CommonMark，以及 GFM 表格、删除线、任务列表、脚注。外链变成上标 `[1]`，文末「参考链接」写出网址原文，正文里不再放 `<a href>`。代码块按行输出，空格换成 `&nbsp;`，语言能识别时用 syntect 的 InspiredGitHub 主题写内联颜色。

这些结构会返回错误，不会悄悄丢掉：内嵌 HTML、数学公式、`mermaid` / `math` / `latex` / `katex` / `tex` / `plantuml` 代码块、`data:` 图片、定义列表、正文里的元数据块。

调用方要自己去掉 YAML front matter。本库看到 `---` 会当成 Markdown 分隔线。

## 命令行

```sh
cargo build --release --manifest-path md2wx/Cargo.toml
md2wx/target/release/md2wx --input post.md --output out.json
md2wx/target/release/md2wx --input post.md --output out.json --image-base https://example.com/wx/demo/img
```

`out.json`：

```json
{
  "html": "<section ...>",
  "images": [{ "placeholder": "{{wx-img:0}}", "path": "./cover.png" }],
  "charCount": 120,
  "byteLen": 340
}
```

退出码 `0` 成功，`1` 渲染或读写失败，`2` 参数错误。二进制只读一个文件、写一个文件。

## 测试

```sh
cargo test --manifest-path md2wx/Cargo.toml
```

`tests/fixtures/` 里是标题、段落、列表、引用、代码、表格、图片、外链脚注的快照。更新快照时设置 `UPDATE_SNAPSHOTS=1`。

构建依赖当前的 stable Rust（本仓库在 1.100 上测过）。syntect 使用 `regex-fancy`，不编译 C 的 onig。
