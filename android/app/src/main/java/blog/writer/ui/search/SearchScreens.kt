package blog.writer.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blog.writer.data.db.SearchProfileEntity
import blog.writer.data.repo.SearchRepository
import blog.writer.search.BuiltinSearchService
import blog.writer.search.SearchRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class SearchListViewModel @Inject constructor(
    private val search: SearchRepository,
) : ViewModel() {
    val profiles = search.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun move(id: String, delta: Int) = viewModelScope.launch { search.move(id, delta) }
    fun toggle(entity: SearchProfileEntity) = viewModelScope.launch { search.save(entity.copy(enabled = !entity.enabled), null, emptyMap()) }
    fun delete(id: String) = viewModelScope.launch { search.delete(id) }
}

@Composable
fun SearchListScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onCreate: (String) -> Unit,
    model: SearchListViewModel = hiltViewModel(),
) {
    val profiles by model.profiles.collectAsState()
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("搜索来源", style = MaterialTheme.typography.headlineSmall)
            Text("自动模式按此顺序尝试。点上移或下移调整优先级。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            profiles.forEachIndexed { index, profile ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}. ${profile.name}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Switch(checked = profile.enabled, onCheckedChange = { model.toggle(profile) })
                    }
                    Text(
                        "${if (profile.kind == "custom") "自定义 HTTP" else "内置服务"} · ${profile.service.ifBlank { profile.baseUrl }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        profile.testSummary ?: "未测试",
                        color = if (profile.lastTestFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Row {
                        TextButton(onClick = { onEdit(profile.id) }) { Text("编辑") }
                        TextButton(onClick = { model.move(profile.id, -1) }) { Text("上移") }
                        TextButton(onClick = { model.move(profile.id, 1) }) { Text("下移") }
                        TextButton(onClick = { model.delete(profile.id) }) { Text("删除") }
                    }
                }
            }
            Button(onClick = { onCreate("builtin") }, modifier = Modifier.fillMaxWidth()) { Text("新建内置服务") }
            OutlinedButton(onClick = { onCreate("custom") }, modifier = Modifier.fillMaxWidth()) { Text("新建自定义 HTTP") }
        }
    }
}

@HiltViewModel
class SearchEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val search: SearchRepository,
) : ViewModel() {
    private val rawId = savedStateHandle.get<String>("id").orEmpty()
    val id: String = if (rawId.startsWith("new-")) UUID.randomUUID().toString() else rawId
    private val creating = rawId.startsWith("new-")
    var kind by mutableStateOf(if (rawId.startsWith("new-custom")) "custom" else "builtin")
    var name by mutableStateOf("博查")
    var service by mutableStateOf(BuiltinSearchService.BOCHA.name)
    var baseUrl by mutableStateOf(BuiltinSearchService.BOCHA.defaultBaseUrl)
    var international by mutableStateOf(false)
    var apiKey by mutableStateOf("")
    var savedLast4 by mutableStateOf<String?>(null)
    var method by mutableStateOf("POST")
    var urlTemplate by mutableStateOf("https://search.example.com/v1/search")
    var headersText by mutableStateOf("Authorization=Bearer {{secret.apiKey}}")
    var bodyTemplate by mutableStateOf("""{"q":"{{query}}","count":"{{maxResults}}"}""")
    var itemsPath by mutableStateOf("$.data.results")
    var fieldsText by mutableStateOf("title=$.title\nurl=$.link\nsnippet=$.summary")
    var priority by mutableStateOf(0)
    var report by mutableStateOf<String?>(null)
    var testing by mutableStateOf(false)

    init {
        if (!creating) {
            viewModelScope.launch {
                val saved = search.profile(id) ?: return@launch
                kind = saved.kind
                name = saved.name
                service = saved.service
                baseUrl = saved.baseUrl
                international = saved.needsInternational
                savedLast4 = saved.keyLast4
                method = saved.method
                urlTemplate = saved.urlTemplate
                headersText = saved.headersText
                bodyTemplate = saved.bodyTemplate
                itemsPath = saved.itemsPath
                fieldsText = saved.fieldsText
                priority = saved.priority
                report = saved.testSummary
            }
        }
    }

    fun applyService(next: BuiltinSearchService) {
        service = next.name
        name = next.label
        baseUrl = next.defaultBaseUrl
        international = !next.domesticDirect
    }

    fun save(onDone: () -> Unit) {
        viewModelScope.launch {
            search.save(entity(), apiKey.ifBlank { null }, emptyMap())
            onDone()
        }
    }

    fun test() {
        viewModelScope.launch {
            testing = true
            search.save(entity(), apiKey.ifBlank { null }, emptyMap())
            val runtime = search.runtime(id)
            val result = runCatching {
                runtime?.adapter?.search(SearchRequest("Kotlin 2.0 发布日期", maxResults = 3))
            }
            val response = result.getOrNull()
            val summary = if (response != null) {
                val dated = response.results.count { it.publishedAt != null }
                val body = response.results.count { !it.content.isNullOrBlank() }
                "测试通过 · 日期 $dated/${response.results.size} · 正文 $body/${response.results.size}"
            } else {
                "测试失败 · ${result.exceptionOrNull()?.message ?: "没有结果"}"
            }
            report = summary
            search.recordTest(id, response != null && response.results.isNotEmpty(), summary)
            testing = false
        }
    }

    private fun entity(): SearchProfileEntity = SearchProfileEntity(
        id = id,
        name = name.ifBlank { "搜索来源" },
        kind = kind,
        service = service,
        baseUrl = baseUrl,
        enabled = true,
        priority = priority,
        needsInternational = international,
        timeoutSec = 10,
        dailyLimit = null,
        unitPrice = null,
        method = method,
        urlTemplate = urlTemplate,
        headersText = headersText,
        bodyTemplate = bodyTemplate,
        errorPath = "",
        itemsPath = itemsPath,
        fieldsText = fieldsText,
        capFreshness = false,
        capIncludeDomains = false,
        capLanguage = kind == "custom",
        capFullContent = false,
        keyLast4 = apiKey.takeLast(4).ifBlank { savedLast4 },
        testOk = null,
        testSummary = report,
        testAtEpochMs = null,
        lastTestFailed = false,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchEditScreen(onBack: () -> Unit, model: SearchEditViewModel = hiltViewModel()) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(if (model.kind == "custom") "自定义 HTTP" else "内置服务", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(model.name, { model.name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
            if (model.kind == "builtin") {
                Text("服务")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BuiltinSearchService.entries.forEach { service ->
                        FilterChip(selected = model.service == service.name, onClick = { model.applyService(service) }, label = { Text(service.label) })
                    }
                }
            }
            OutlinedTextField(model.baseUrl, { model.baseUrl = it }, label = { Text("接口地址") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (model.international) "需要国际网络" else "国内直连", modifier = Modifier.weight(1f))
                Switch(checked = model.international, onCheckedChange = { model.international = it })
            }
            OutlinedTextField(
                model.apiKey,
                { model.apiKey = it },
                label = { Text(if (model.savedLast4 != null) "API key · 末 4 位 ${model.savedLast4}" else "API key") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (model.kind == "custom") {
                OutlinedTextField(model.method, { model.method = it }, label = { Text("方法") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model.urlTemplate, { model.urlTemplate = it }, label = { Text("URL 模板") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model.headersText, { model.headersText = it }, label = { Text("请求头模板") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model.bodyTemplate, { model.bodyTemplate = it }, label = { Text("请求体模板") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                OutlinedTextField(model.itemsPath, { model.itemsPath = it }, label = { Text("结果数组路径") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model.fieldsText, { model.fieldsText = it }, label = { Text("字段映射") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                Text("title 和 url 必填。密钥写 {{secret.名称}}，不会导出。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            model.report?.let { Text(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = model::test, enabled = !model.testing) { Text(if (model.testing) "停止" else "连接测试") }
                Button(onClick = { model.save(onBack) }) { Text("保存") }
            }
        }
    }
}
