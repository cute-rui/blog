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

## 还没接上的部分

- 公众号预览只调用 `WechatMarkdownRenderer`。现在的实现是 `StubWechatMarkdownRenderer`，返回固定标记，不把 Markdown 排成微信 HTML。真正的渲染要等后续把 `md2wx` 用 UniFFI 绑进来，换掉这个测试替身。
- 同步客户端打的是 GitHub REST，但这次没有对真实的 `blog-content` 仓库跑过。没有令牌时只保存在本机。
- 没有在模拟器或真机上点过界面。验证是 `assembleDebug` 和 JVM 单元测试。
- 事实核查、补全中间、搜索来源都没有做。
