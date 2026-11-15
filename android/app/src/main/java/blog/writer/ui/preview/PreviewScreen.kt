package blog.writer.ui.preview

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blog.writer.content.slugOf
import blog.writer.data.repo.GithubSettingsRepository
import blog.writer.data.repo.PostRepository
import blog.writer.data.repo.WechatDraftSync
import blog.writer.egress.EgressException
import blog.writer.render.WechatMarkdownRenderer
import blog.writer.render.WechatRenderOptions
import blog.writer.render.WechatTheme
import blog.writer.sync.CopyDecision
import blog.writer.sync.WechatCopy
import blog.writer.ui.nav.decodePath
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

@HiltViewModel
class PreviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val posts: PostRepository,
    private val github: GithubSettingsRepository,
    private val renderer: WechatMarkdownRenderer,
    private val http: OkHttpClient,
    private val drafts: WechatDraftSync,
) : ViewModel() {
    private val path = decodePath(savedStateHandle.get<String>("path").orEmpty())
    val note = MutableStateFlow("公众号预览使用 md2wx。")
    val html = MutableStateFlow("")
    val mode = MutableStateFlow("公众号")
    val hasDraft = MutableStateFlow(false)
    private val sending = AtomicBoolean(false)

    init {
        viewModelScope.launch(Dispatchers.IO) { hasDraft.value = drafts.hasDraft(path) }
        showWechat()
    }

    fun showWechat() {
        viewModelScope.launch {
            mode.value = "公众号"
            val document = posts.read(path)
            val rendered = runCatching {
                renderer.render(
                    markdown = document.body,
                    theme = WechatTheme(accent = "#2D5786", ink = "#1E1C19", paper = "#FAF7F0", muted = "#4C463E"),
                    options = WechatRenderOptions(imageUrlPrefix = ""),
                )
            }
            rendered.fold(
                onSuccess = { result ->
                    html.value = "<html><body style=\"background:#FAF7F0;color:#1E1C19;font-family:serif;padding:24px\">${result.htmlFragment}<p>图片 ${result.images.size} · ${result.length.characters} 字符</p></body></html>"
                    note.value = "公众号预览"
                },
                onFailure = { error ->
                    html.value = ""
                    note.value = error.message ?: "md2wx 没有渲染"
                },
            )
        }
    }

    fun sendDraft(onResult: (String) -> Unit) {
        if (!sending.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val result = runCatching { drafts.send(path) }
                result.fold(
                    onSuccess = { sent ->
                        hasDraft.value = true
                        onResult(if (sent.updated) "已更新草稿箱里的这一篇" else "已放入草稿箱")
                    },
                    onFailure = { error -> onResult(draftFailure(error)) },
                )
            } finally {
                sending.set(false)
            }
        }
    }

    fun showBlog() {
        viewModelScope.launch {
            mode.value = "博客"
            val document = posts.read(path)
            html.value = blogHtml(document.body)
            note.value = "这是博客阅读的近似效果，不是公众号 HTML。"
        }
    }

    fun copyWechat(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val settings = github.current()
            if (settings.wxIndexUrl.isBlank()) {
                onResult("还没有配置复制页清单")
                return@launch
            }
            val slug = slugOf(path)
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val index = WechatCopy.parseIndex(httpGet(settings.wxIndexUrl))
                    github.rememberMode(index.wechatMode)
                    val page = index.pages[slug] ?: error("清单里没有这篇文章")
                    val meta = WechatCopy.parseMeta(httpGet(WechatCopy.metaUrl(page)))
                    val html = httpGet(WechatCopy.contentUrl(page))
                    val decision = WechatCopy.decide(posts.contentHash(path), meta.contentHash)
                    Triple(decision, html, page)
                }
            }
            outcome.fold(
                onSuccess = { (decision, body, page) ->
                    when (decision) {
                        CopyDecision.Ready -> onResult("READY\n$page\n$body")
                        is CopyDecision.Waiting -> onResult(decision.reason)
                    }
                },
                onFailure = { onResult(it.message ?: "复制页没有取到") },
            )
        }
    }

    private fun httpGet(url: String): String {
        val request = Request.Builder().url(url).build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }
}

@Composable
fun PreviewScreen(
    onBack: () -> Unit,
    model: PreviewViewModel = hiltViewModel(),
) {
    val note by model.note.collectAsState()
    val html by model.html.collectAsState()
    val mode by model.mode.collectAsState()
    val hasDraft by model.hasDraft.collectAsState()
    var status by remember { mutableStateOf("") }
    var pageUrl by remember { mutableStateOf("") }
    val context = LocalContext.current
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("返回") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = model::showBlog) { Text(if (mode == "博客") "博客 · 当前" else "博客") }
                TextButton(onClick = model::showWechat) { Text(if (mode == "公众号") "公众号 · 当前" else "公众号") }
            }
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary)
            Button(onClick = {
                model.copyWechat { result ->
                    if (result.startsWith("READY\n")) {
                        val page = result.lineSequence().drop(1).first()
                        val body = result.lineSequence().drop(2).joinToString("\n")
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newHtmlText("公众号正文", body, body))
                        pageUrl = page
                        status = "已复制正文"
                    } else {
                        status = result
                    }
                }
            }) { Text("复制公众号格式") }
            OutlinedButton(onClick = {
                if (pageUrl.isBlank()) {
                    status = "还没有复制页链接"
                } else {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, pageUrl),
                            "把复制页链接发到电脑",
                        ),
                    )
                }
            }) { Text("把复制页链接发到电脑") }
            OutlinedButton(onClick = {
                status = "正在放进草稿箱…"
                model.sendDraft { result -> status = result }
            }) { Text(if (hasDraft) "更新公众号草稿箱里的这一篇" else "放进公众号草稿箱") }
            Text(
                "不会群发。放进草稿箱之后，在公众号助手里检查再发。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { context -> WebView(context).apply { settings.javaScriptEnabled = false } },
                update = { web ->
                    web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                },
            )
        }
    }
}

private fun draftFailure(error: Throwable): String {
    val message = (error as? EgressException)?.message ?: error.message.orEmpty()
    return when {
        message.contains("出口令牌") || message.contains("WECHAT_EGRESS_TOKEN") -> "先在设置里填写出口令牌"
        message.contains("SRV") -> "先在设置里填写 SRV 域名"
        message.contains("libmd2wx") -> "还没有放进 libmd2wx_android.so。复制公众号格式仍读取 CI 生成的复制页。"
        message.isBlank() -> "没有放进草稿箱"
        else -> message
    }
}

private fun blogHtml(markdown: String): String {
    val escaped = markdown.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val blocks = escaped.split("\n\n").joinToString("") { block ->
        val line = block.trim()
        when {
            line.startsWith("# ") -> "<h1>${line.removePrefix("# ")}</h1>"
            line.startsWith("## ") -> "<h2>${line.removePrefix("## ")}</h2>"
            line.startsWith("### ") -> "<h3>${line.removePrefix("### ")}</h3>"
            else -> "<p>${line.replace("\n", "<br>")}</p>"
        }
    }
    return """
        <html><body style="background:#FAF7F0;color:#1E1C19;font-family:serif;padding:24px;line-height:1.75">
        $blocks
        </body></html>
        """.trimIndent()
}
