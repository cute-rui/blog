# 写作

Lightwing 自用的 Android 写作 App，P2。侧载安装，不上架 Play。界面是简体中文。仓库里没有 API key。

## 编译

需要 JDK 21 和 Android SDK（`compileSdk` 36）。在本目录写 `local.properties`：

```
sdk.dir=/path/to/Android/sdk
```

然后：

```
./gradlew :app:assembleDebug test
```

调试包在 `app/build/outputs/apk/debug/app-debug.apk`。装到自己的手机即可，`applicationId` 是 `blog.writer`，`minSdk` 29。

## 这一版有什么

- 文章列表、`TextFieldState` 编辑器、front matter 表单。
- GitHub Git Data API 同步：blob、tree、commit，更新 ref 时要求仍停在拉取时的 commit，对不上就拒绝并做段落级三方合并。
- 发布目标选择。公众号是个人主体，按钮是「生成公众号版本」，不是自动群发。
- 「复制公众号格式」读取 CI 复制页的 `content.html`，并对照内容 hash。hash 还是上一版时按钮不会把旧内容放进剪贴板。
- LLM 配置档：计划 5.5 的预设、自定义 Base URL、连接测试、默认模型和「给我思路继续写」可以分开指定。Key 用 Android Keystore 里的 AES-GCM 密钥加密，不用 EncryptedSharedPreferences。流式调用走 OkHttp SSE。
- 唯一可用的 AI 动作是「给我思路继续写」。生成的段落保持待审，接受前不写入 Markdown，也不参与同步。

## P3

- 「补全中间」：标出开头和结尾，或在正文写 `<!-- fill -->`。生成的文字先作为待审草稿。
- 事实核查分两步：先抽出陈述，再按检索结果逐条判定。检索全部失败时标成「无法核实（检索失败）」，不会靠模型记忆下结论。
- 搜索来源：博查、Tavily、Brave、SearXNG，以及自定义 HTTP。自动模式按优先级尝试，跳过测不通的来源。
- AI 历史记下动作、模型和提示词模板版本。Anthropic 与 Gemini 协议在 P2 已经接上。

## P3b

- MCP 搜索走 Streamable HTTP：无认证、Bearer、自定义请求头。没有 OAuth。官方 Kotlin MCP SDK 会带上另一套 HTTP 客户端，这里用 OkHttp 自己发 JSON-RPC，方便用假服务器测 `initialize`、`tools/list` 和 `tools/call`。
- 模型内置联网只读引用元数据。Anthropic、Gemini，然后是通义千问、Kimi、智谱 GLM。跳转链接会再解析一次。摘录很短时标「摘录不完整」。
- 内置服务补上智谱 Web Search、百度千帆 AI 搜索、阿里云 IQS、Exa、Serper。
- 多源合并会同时查前几个可用来源，按网址合并，优先域名上浮。

## 还没接上的部分

- 公众号预览只调用 `WechatMarkdownRenderer`。现在的实现是 `StubWechatMarkdownRenderer`，返回固定标记，不把 Markdown 排成微信 HTML。真正的渲染要等后续把 `md2wx` 用 UniFFI 绑进来，换掉这个测试替身。
- 同步客户端打的是 GitHub REST，但这次没有对真实的 `blog-content` 仓库跑过。没有令牌时只保存在本机。
- 没有在模拟器或真机上点过界面。验证是 `assembleDebug` 和 JVM 单元测试。
- 百度千帆只解析 `v2/ai_search/web_search` 的 `references`。阿里云 IQS 只做 Bearer 的 `search/unified` 和 `pageItems`，没有 AccessKey 签名。Kimi 的引用字段不稳定，只认 `references`、`search_results` 和 `message.citations`；只有工具调用、没有这些字段时返回 0 条。
