# 微信草稿固定出口

公众号打开 API IP 白名单之后，只有白名单里的 IP 能调用 `api.weixin.qq.com`。GitHub 托管的 runner 没有固定 IP。草稿同步因此分成两截：

- `wechat-egress server` 跑在固定 IP 的 VPS 上，是唯一会请求微信的进程。`WECHAT_APPID` 和 `WECHAT_APPSECRET` 只放在这台机器的环境变量里。
- 工作流里的客户端先查一条 SRV，再连记录里的主机和端口。客户端每次调用带 bearer token，不带微信凭据。

出口发现用 DNS SRV。当前的 `publish.yml` 不调用这一步，草稿 job 以后再接。

## 服务器

在 VPS 上准备证书，证书的 SAN 要覆盖 SRV 目标主机名。然后：

```sh
export WECHAT_APPID=...
export WECHAT_APPSECRET=...
export WECHAT_EGRESS_TOKEN=$(openssl rand -hex 32)
export WECHAT_EGRESS_TLS_CERT=/etc/wechat-egress/cert.pem
export WECHAT_EGRESS_TLS_KEY=/etc/wechat-egress/key.pem
go build -o wechat-egress ./cmd/wechat-egress
./wechat-egress server --listen 0.0.0.0:8443
```

把 VPS 的公网 IP 填进公众号的 API IP 白名单。同一个 `WECHAT_EGRESS_TOKEN` 放到博客仓库的 Actions secret。不要把 AppSecret 放进 GitHub。

进程只请求这五个路径，不会按调用方给的 URL 转发：

- `POST /cgi-bin/stable_token`（`force_refresh` 固定为 false）
- `POST /cgi-bin/media/uploadimg`（正文图，返回可放进 HTML 的 URL）
- `POST /cgi-bin/material/add_material?type=image`（封面永久素材，返回 `media_id`）
- `POST /cgi-bin/draft/add`
- `POST /cgi-bin/draft/update`

封面的素材类型固定是 `image`。调用方不能改 type，也不能指定别的微信地址。`UploadCover` 返回的 `media_id` 填进草稿的 `thumb_media_id`。手机和 CI 都不直接调用微信。

`--insecure` 只允许监听回环地址，给本机测试用。

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

以后的草稿 job 在检出本仓库、并且环境里已经有 Go 1.22 之后，加上这一步。它不读取微信 AppSecret。已有草稿时，把最后一行换成 `update-draft --file "$DRAFT_JSON"`。`add-draft` 的文件是微信 `draft/add` 的 JSON（`articles` 数组）；`update-draft` 的文件是 `draft/update` 的 JSON（`media_id`、`index`、`articles` 对象）。

```yaml
- name: 经固定 IP 出口同步微信草稿
  working-directory: wechat-egress
  env:
    WECHAT_EGRESS_TOKEN: ${{ secrets.WECHAT_EGRESS_TOKEN }}
    WECHAT_EGRESS_SRV_HOST: ${{ vars.WECHAT_EGRESS_SRV_HOST }}
  run: |
    go build -o "$RUNNER_TEMP/wechat-egress" ./cmd/wechat-egress
    "$RUNNER_TEMP/wechat-egress" upload-image --file "$IMAGE_FILE"
    "$RUNNER_TEMP/wechat-egress" upload-cover --file "$COVER_FILE"
    "$RUNNER_TEMP/wechat-egress" add-draft --file "$DRAFT_JSON"
```

`stable-token` 会把 access token 打到标准输出，不要写进 workflow summary。

手机上的写作 App 走同一套 gRPC，不走上面的命令行。它查询 `_wechat._tcp.<域名>`，每次调用带出口令牌。正文图用 `UploadImage`，封面用 `UploadCover`，然后 `AddDraft`；已经有草稿 id 时改用 `UpdateDraft`。手机不调用 `stable_token`，微信 access token 留在 VPS。App 不保存 AppID 和 AppSecret，也不请求 `api.weixin.qq.com`。

## 测试

```sh
cd wechat-egress
go test ./...
```

测试覆盖 SRV 报文解析（含压缩指针）、相对目标、优先级和权重，以及在回环上让客户端调用服务器。微信请求打到本机的假服务器，不会访问 `api.weixin.qq.com`。
