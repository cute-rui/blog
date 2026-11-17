# 微信草稿固定出口

公众号打开 API IP 白名单之后，只有白名单里的 IP 能调用 `api.weixin.qq.com`。GitHub 托管的 runner 没有固定 IP。草稿同步因此分成两截：

- `wechat-egress server` 跑在固定 IP 的 VPS 上，是唯一会请求微信的进程。AppID、AppSecret 和出口令牌写在这台机器的 TOML 配置里，不放进环境变量。
- 工作流里的客户端先查一条 SRV，再连记录里的主机和端口。客户端每次调用带 bearer token，不带微信凭据。

出口发现用 DNS SRV。`publish.yml` 在部署之后运行客户端：先列出草稿和已发布文章，再只为两边都没有的文章调用 `add-draft`。

## 服务器

在 VPS 上准备证书，证书的 SAN 要覆盖 SRV 目标主机名。把 [`config.example.toml`](config.example.toml) 复制到仓库外面，例如 `/etc/wechat-egress/config.toml`，填上真实值，然后 `chmod 0600`。仓库里的那份只有占位符，不要把填好的文件提交回来。

```sh
go build -o wechat-egress ./cmd/wechat-egress
./wechat-egress server --config /etc/wechat-egress/config.toml
```

`--config` 是配置文件的路径。环境变量只用来指出同一个路径，里面不放密钥：

```sh
WECHAT_EGRESS_CONFIG=/etc/wechat-egress/config.toml ./wechat-egress server
```

命令行如果也写了 `--config`，以命令行为准。没给路径就不能启动，即使环境里还有以前的 `WECHAT_APPID` 之类的变量。

| 键 | 作用 |
| --- | --- |
| `app_id` | 微信 AppID |
| `app_secret` | 微信 AppSecret |
| `token` | 出口 bearer token。同一串放到博客仓库的 Actions secret，并填进手机「微信出口」 |
| `listen` | 监听地址。缺省 `0.0.0.0:8443` |
| `tls_cert` | 证书 PEM |
| `tls_key` | 私钥 PEM |
| `insecure` | 明文监听，只能绑回环地址。缺省 `false` |
| `api_base` | 空字符串表示 `https://api.weixin.qq.com`。不要改成别的公网地址 |

不认识的键会拒绝启动。文件必须是普通文件，并且不能被同组或其他用户读取。

把 VPS 的公网 IP 填进公众号的 API IP 白名单。不要把 AppSecret 放进 GitHub，也不要放进环境变量。

进程只请求下面这些路径，不会按调用方给的 URL 转发：

- `POST /cgi-bin/stable_token`（`force_refresh` 固定为 false）
- `POST /cgi-bin/media/uploadimg`（正文图，返回可放进 HTML 的 URL）
- `POST /cgi-bin/material/add_material?type=image`（封面永久素材，返回 `media_id`）
- `POST /cgi-bin/draft/add`
- `POST /cgi-bin/draft/update`
- `POST /cgi-bin/draft/batchget`（只读草稿箱，`no_content` 固定为 1）
- `POST /cgi-bin/freepublish/batchget`（只读已发布列表）

不调用 `freepublish/submit`，也不调用群发接口。封面的素材类型固定是 `image`。调用方不能改 type，也不能指定别的微信地址。`UploadCover` 返回的 `media_id` 填进草稿的 `thumb_media_id`。手机和 CI 都不直接调用微信。

连到 `api.weixin.qq.com` 时必须校验 TLS 证书。正式路径上的 HTTP 客户端把 `InsecureSkipVerify` 固定为 false，并且拒绝任何对这个主机关掉校验的客户端。gRPC 客户端同样校验服务器证书。配置里的 `insecure = true` 只允许监听回环地址，给本机测试用。客户端的 `--insecure` 也只能拨回环地址。回环上的微信假服务器如果自己关掉证书校验，可以保留；正式主机不行。

## 发布 SRV

客户端默认查询 `_wechat._tcp.$WECHAT_EGRESS_SRV_HOST`。优先级数字小的先试；同一优先级里按权重决定尝试顺序，权重 0 排在该优先级的正权重之后。目标不可达就试下一条。

绝对目标（建议写成这样）：

```dns
_wechat._tcp.example.com. 300 IN SRV 0 10 8443 egress.example.com.
_wechat._tcp.example.com. 300 IN SRV 10 5 8443 backup.example.com.
```

相对目标写在该域的 zone 里时，权威服务器会把它展开成绝对名。客户端如果收到没有结尾点的目标，会把它补到查询名的域上：`egress` 配 `example.com` 变成 `egress.example.com`。

```dns
$ORIGIN example.com.
_wechat._tcp 300 IN SRV 0 10 8443 egress
```

仓库变量 `WECHAT_EGRESS_SRV_HOST` 填 `example.com`，不要填真实地址进仓库。DNS 坏了或做测试时，用 `--address host:port`（或 `WECHAT_EGRESS_ADDRESS`）跳过 SRV。

## 工作流里的那一步

[`publish.yml`](../.github/workflows/publish.yml) 在部署之后编译这个客户端，再运行 [`scripts/sync-wechat-drafts.mjs`](../scripts/sync-wechat-drafts.mjs)。脚本用已有的 `md2wx` 渲染，然后只通过客户端做这几件事：`list-drafts`、`list-published`、必要时 `upload-image`、`upload-cover` 和 `add-draft`。已经在草稿箱或已发布列表里的文章不会再次 `add-draft`，也不会 `update-draft`。匹配看公众号标题，以及非空的 `content_source_url`。

环境里只放出口令牌和 SRV 域名：`WECHAT_EGRESS_TOKEN`、`WECHAT_EGRESS_SRV_HOST`。证书不是公共 CA 签发时，把 PEM 放进 `WECHAT_EGRESS_TLS_CA`，workflow 把它写成文件再交给 `--tls-ca`。不要设置 `WECHAT_EGRESS_INSECURE`。不要把 AppID 或 AppSecret 放进 workflow。

`stable-token` 会把 access token 打到标准输出，workflow 不调用它。

手机上的写作 App 走同一套 gRPC，不走上面的命令行。它查询 `_wechat._tcp.<域名>`，每次调用带出口令牌。正文图用 `UploadImage`，封面用 `UploadCover`，然后 `AddDraft`；已经有草稿 id 时改用 `UpdateDraft`。手机不调用 `stable_token`，微信 access token 留在 VPS。App 不保存 AppID 和 AppSecret，也不请求 `api.weixin.qq.com`。

## 测试

```sh
cd wechat-egress
go test ./...
```

测试覆盖 SRV 报文解析（含压缩指针）、相对目标、优先级和权重，以及在回环上让客户端调用服务器。微信请求打到本机的假服务器，不会访问 `api.weixin.qq.com`。
