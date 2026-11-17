package blog.writer.ui.preview
import blog.writer.copy.Copy

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
import blog.writer.content.wechatBody
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
    val note = MutableStateFlow(Copy["note_using_md2wx"])
    val html = MutableStateFlow("")
    val mode = MutableStateFlow(Copy["wechat"])
    val hasDraft = MutableStateFlow(false)
    private val sending = AtomicBoolean(false)

    init {
        viewModelScope.launch(Dispatchers.IO) { hasDraft.value = drafts.hasDraft(path) }
        showWechat()
    }

    fun showWechat() {
        viewModelScope.launch {
            mode.value = Copy["wechat"]
            val document = posts.read(path)
            val rendered = runCatching {
                renderer.render(
                    markdown = document.wechatBody(),
                    theme = WechatTheme(accent = "#2D5786", ink = "#1E1C19", paper = "#FAF7F0", muted = "#4C463E"),
                    options = WechatRenderOptions(imageUrlPrefix = ""),
                )
            }
            rendered.fold(
                onSuccess = { result ->
                    val caption = Copy.format("preview_image_count", "count" to result.images.size, "chars" to result.length.characters)
                    html.value = "<html><body style=\"background:#FAF7F0;color:#1E1C19;font-family:serif;padding:24px\">${result.htmlFragment}<p>$caption</p></body></html>"
                    note.value = Copy["note_wechat"]
                },
                onFailure = { error ->
                    html.value = ""
                    note.value = error.message ?: Copy["note_render_failed"]
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
                        onResult(if (sent.updated) Copy["draft_updated"] else Copy["draft_added"])
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
            mode.value = Copy["blog"]
            val document = posts.read(path)
            html.value = blogHtml(document.body)
            note.value = Copy["note_blog"]
        }
    }

    fun copyWechat(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val settings = github.current()
            if (settings.wxIndexUrl.isBlank()) {
                onResult(Copy["no_copy_index"])
                return@launch
            }
            val slug = slugOf(path)
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val index = WechatCopy.parseIndex(httpGet(settings.wxIndexUrl))
                    github.rememberMode(index.wechatMode)
                    val page = index.pages[slug] ?: error(Copy["slug_missing"])
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
                onFailure = { onResult(it.message ?: Copy["copy_page_failed"]) },
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
            TextButton(onClick = onBack) { Text(Copy["back"]) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = model::showBlog) { Text(if (mode == Copy["blog"]) Copy["mode_blog_current"] else Copy["blog"]) }
                TextButton(onClick = model::showWechat) { Text(if (mode == Copy["wechat"]) Copy["mode_wechat_current"] else Copy["wechat"]) }
            }
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary)
            Button(onClick = {
                model.copyWechat { result ->
                    if (result.startsWith("READY\n")) {
                        val page = result.lineSequence().drop(1).first()
                        val body = result.lineSequence().drop(2).joinToString("\n")
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newHtmlText(Copy["clipboard_wechat"], body, body))
                        pageUrl = page
                        status = Copy["copied_body"]
                    } else {
                        status = result
                    }
                }
            }) { Text(Copy["copy_wechat_format"]) }
            OutlinedButton(onClick = {
                if (pageUrl.isBlank()) {
                    status = Copy["no_copy_link"]
                } else {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, pageUrl),
                            Copy["send_copy_link"],
                        ),
                    )
                }
            }) { Text(Copy["send_copy_link"]) }
            OutlinedButton(onClick = {
                status = Copy["putting_draft"]
                model.sendDraft { result -> status = result }
            }) { Text(if (hasDraft) Copy["update_draft"] else Copy["put_in_drafts"]) }
            Text(
                Copy["no_broadcast"],
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
        message.contains(Copy["egress_token"]) || message.contains("WECHAT_EGRESS_TOKEN") -> Copy["need_egress_token"]
        message.contains("SRV") -> Copy["need_srv_domain"]
        message.contains("libmd2wx") -> Copy["missing_lib_copy_still"]
        message.isBlank() -> Copy["draft_not_sent"]
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
