# 内容仓库要抄过去的文件

博客仓库不包含文章。把这一目录里的 workflow 复制到内容仓库，front matter 以博客仓库的 `schema/content-schema.json` 为准，也复制到内容仓库根目录，文件名仍是 `content-schema.json`。

```
blog-content/
├─ README.md
├─ content-schema.json
├─ posts/<slug>/index.md
├─ posts/<slug>/*.{png,jpg,webp,svg}
└─ .github/workflows/notify.yml
```

目录名就是 `/posts/<slug>/`。发给公众号的文章必须是 `index.md`，不能是 `index.mdx`。图片用相对路径，例如 `![说明](./figure.png)`。

如果还是旧布局 `posts/<slug>.md`，改成 `posts/<slug>/index.md`，并把原来跟文章放在一起的图片移进该目录。博客仓库的校验会拒绝留在 `content/posts/` 根上的单文件。

`notify.yml` 只在 `main` 被 push 时通知博客仓库。博客仓库的 workflow 从公开仓库 `https://github.com/cute-rui/blog-source` 检出 `posts/`，所以这份通知要放在那个仓库里，`client_payload.after` 必须是它的提交。内容仓库不要调用微信接口。公众号文章用同一份 `posts/` 和 `publish.wechat`，不另做一套目录。

一次 push 想少发一个目标时，在最后一个 commit 末尾加 trailer，只能收窄，不能把 front matter 里关掉的目标重新打开：

```
Publish-To: wechat
```

取值：`blog`、`wechat`、`blog,wechat`、`none`。
