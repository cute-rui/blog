package blog.writer.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blog.writer.ai.ActionAssignments
import blog.writer.data.db.LlmProfileEntity
import blog.writer.data.db.SearchProfileEntity
import blog.writer.data.repo.LlmRepository
import blog.writer.data.repo.SearchRepository
import blog.writer.search.BuiltinSearchService
import blog.writer.search.CitationFamily
import blog.writer.search.JsonPath
import blog.writer.search.McpResults
import blog.writer.search.McpTool
import blog.writer.search.SearchRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class SearchListViewModel @Inject constructor(
    private val search: SearchRepository,
    private val llm: LlmRepository,
) : ViewModel() {
    val profiles = search.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val assignments = llm.observeAssignments().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActionAssignments())
    val llmProfiles = llm.observeProfiles().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun move(id: String, delta: Int) = viewModelScope.launch { search.move(id, delta) }
    fun moveToFront(id: String) = viewModelScope.launch { search.moveToFront(id) }
    fun restore() = viewModelScope.launch { search.restoreDefaultOrder() }
    fun toggle(entity: SearchProfileEntity) = viewModelScope.launch { search.save(entity.copy(enabled = !entity.enabled), null, emptyMap()) }
    fun delete(id: String) = viewModelScope.launch { search.delete(id) }

    fun toggleBuiltin(enabled: Boolean) {
        viewModelScope.launch {
            val current = llm.observeAssignments().first()
            llm.saveAssignments(current.copy(factUseBuiltin = enabled))
        }
    }
}

@Composable
fun SearchListScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onCreate: (String) -> Unit,
    model: SearchListViewModel = hiltViewModel(),
) {
    val profiles by model.profiles.collectAsState()
    val assignments by model.assignments.collectAsState()
    val llmProfiles by model.llmProfiles.collectAsState()
    var picking by remember { mutableStateOf(false) }
    val fact = assignments.resolveFactCheck()
    val factProfile = llmProfiles.firstOrNull { it.id == fact?.profileId }
    val factFamily = factProfile?.let { CitationFamily.from(it.presetId, it.protocol) }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("搜索来源", style = MaterialTheme.typography.headlineSmall)
            Text("自动模式按此顺序尝试 · 点上移或下移调整优先级", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            profiles.forEachIndexed { index, profile ->
                Column(Modifier.fillMaxWidth().alpha(if (profile.enabled) 1f else 0.5f).padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}", modifier = Modifier.padding(end = 8.dp), style = MaterialTheme.typography.labelLarge)
                        Text(profile.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Switch(checked = profile.enabled, onCheckedChange = { model.toggle(profile) })
                    }
                    Text(sourceLine(profile), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${if (profile.needsInternational) "需要国际网络" else "国内直连"} · ${profile.testSummary ?: "未测试"}",
                        color = if (profile.lastTestFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Row {
                        TextButton(onClick = { onEdit(profile.id) }) { Text("编辑") }
                        TextButton(onClick = { model.moveToFront(profile.id) }) { Text("移到最前") }
                        TextButton(onClick = { model.move(profile.id, -1) }) { Text("上移") }
                        TextButton(onClick = { model.move(profile.id, 1) }) { Text("下移") }
                        TextButton(onClick = { model.delete(profile.id) }) { Text("删除") }
                    }
                }
            }
            if (factProfile != null && factProfile.builtinSearch && factFamily != null) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("✦", color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(end = 8.dp))
                            Text("${factProfile.name} 内置联网", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Switch(checked = assignments.factUseBuiltin, onCheckedChange = model::toggleBuiltin)
                        }
                        Text("核查所用模型，自动加入候选，排在最后", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("摘录不完整", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
            Button(onClick = { picking = !picking }, modifier = Modifier.fillMaxWidth()) { Text("新建搜索来源") }
            if (picking) {
                TypeChoice("内置服务", "填 key 就能用：博查、智谱、Tavily、Brave…") { onCreate("builtin") }
                TypeChoice("自定义 HTTP", "任何返回 JSON 的搜索接口，用模板接入") { onCreate("custom") }
                TypeChoice("MCP 服务器", "远程 MCP 服务器上的搜索工具") { onCreate("mcp") }
                TypeChoice("模型内置联网", "用支持联网的 LLM 配置档检索") { onCreate("model") }
            }
            TextButton(onClick = model::restore) { Text("恢复默认顺序") }
        }
    }
}

@Composable
private fun TypeChoice(title: String, detail: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@HiltViewModel
class SearchEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val search: SearchRepository,
    private val llm: LlmRepository,
) : ViewModel() {
    private val rawId = savedStateHandle.get<String>("id").orEmpty()
    val id: String = if (rawId.startsWith("new-")) UUID.randomUUID().toString() else rawId
    private val creating = rawId.startsWith("new-")
    var kind by mutableStateOf(
        when {
            rawId.startsWith("new-custom") -> "custom"
            rawId.startsWith("new-mcp") -> "mcp"
            rawId.startsWith("new-model") -> "model"
            else -> "builtin"
        },
    )
    var name by mutableStateOf(
        when (kind) {
            "custom" -> "我的搜索"
            "mcp" -> "MCP 服务器"
            "model" -> "模型内置联网"
            else -> "博查"
        },
    )
    var service by mutableStateOf(BuiltinSearchService.BOCHA.name)
    var baseUrl by mutableStateOf(BuiltinSearchService.BOCHA.defaultBaseUrl)
    var international by mutableStateOf(false)
    var enabled by mutableStateOf(true)
    var apiKey by mutableStateOf("")
    var savedLast4 by mutableStateOf<String?>(null)
    var method by mutableStateOf("POST")
    var urlTemplate by mutableStateOf("https://search.example.com/v1/search")
    var headersText by mutableStateOf("Authorization=Bearer {{secret.apiKey}}")
    var bodyTemplate by mutableStateOf("""{"q":"{{query}}","count":"{{maxResults}}"}""")
    var itemsPath by mutableStateOf("$.data.results")
    var fieldsText by mutableStateOf("title=$.title\nurl=$.link\nsnippet=$.summary")
    var priority by mutableStateOf(0)
    var mcpAuth by mutableStateOf("none")
    var mcpTool by mutableStateOf("")
    var mcpParamMap by mutableStateOf("")
    var useModelCleanup by mutableStateOf(false)
    var cleanupProfileId by mutableStateOf("")
    var llmProfileId by mutableStateOf("")
    var boundModel by mutableStateOf("")
    var maxCitations by mutableStateOf("5")
    var mcpTools by mutableStateOf<List<McpTool>>(emptyList())
    var llmChoices by mutableStateOf<List<LlmProfileEntity>>(emptyList())
    var allLlm by mutableStateOf<List<LlmProfileEntity>>(emptyList())
    var lastTestFailed by mutableStateOf(false)
    var report by mutableStateOf<String?>(null)
    var testing by mutableStateOf(false)
    var lastRaw by mutableStateOf<String?>(null)

    init {
        viewModelScope.launch {
            if (creating && kind == "mcp") {
                headersText = ""
                itemsPath = ""
                fieldsText = ""
                baseUrl = ""
            }
            if (creating && kind == "model") {
                baseUrl = ""
            }
            val profiles = llm.observeProfiles().first()
            allLlm = profiles
            llmChoices = profiles.filter { CitationFamily.from(it.presetId, it.protocol) != null }
            if (!creating) {
                val saved = search.profile(id) ?: return@launch
                kind = saved.kind
                name = saved.name
                service = saved.service
                baseUrl = saved.baseUrl
                international = saved.needsInternational
                enabled = saved.enabled
                savedLast4 = saved.keyLast4
                method = saved.method
                urlTemplate = saved.urlTemplate
                headersText = saved.headersText
                bodyTemplate = saved.bodyTemplate
                itemsPath = saved.itemsPath
                fieldsText = saved.fieldsText
                priority = saved.priority
                mcpAuth = saved.mcpAuth
                mcpTool = saved.mcpTool
                mcpParamMap = saved.mcpParamMap
                useModelCleanup = saved.useModelCleanup
                cleanupProfileId = saved.cleanupProfileId
                llmProfileId = saved.llmProfileId
                boundModel = saved.boundModel
                maxCitations = saved.maxCitations.toString()
                report = saved.testSummary
                lastTestFailed = saved.lastTestFailed
            } else {
                val count = search.observe().first().size
                priority = when (kind) {
                    "custom" -> 1_000 + count
                    "mcp" -> 2_000 + count
                    "model" -> 3_000 + count
                    else -> count
                }
            }
        }
    }

    fun applyService(next: BuiltinSearchService) {
        service = next.name
        name = next.label
        baseUrl = next.defaultBaseUrl
        international = !next.domesticDirect
    }

    fun bindLlm(profile: LlmProfileEntity) {
        llmProfileId = profile.id
        boundModel = profile.model
        if (name.isBlank() || name == "模型内置联网") name = "${profile.name} 内置联网"
        CitationFamily.from(profile.presetId, profile.protocol)?.let { international = it.needsInternationalNetwork }
    }

    fun selectTool(tool: McpTool) {
        mcpTool = tool.name
        if (mcpParamMap.isBlank()) mcpParamMap = blog.writer.search.McpParams.autoMap(tool.inputSchema)
    }

    fun save(onDone: () -> Unit) {
        viewModelScope.launch {
            search.save(entity(), apiKey.ifBlank { null }, emptyMap())
            onDone()
        }
    }

    fun listTools() {
        viewModelScope.launch {
            testing = true
            search.save(entity(), apiKey.ifBlank { null }, emptyMap())
            val result = runCatching { search.mcpHandshake(id) }
            val handshake = result.getOrNull()
            if (handshake != null) {
                mcpTools = handshake.tools
                if (mcpTool.isBlank()) handshake.tools.firstOrNull { it.looksLikeSearch }?.let(::selectTool)
                report = "initialize ✓ ${handshake.serverName} ${handshake.serverVersion} · 工具 ${handshake.tools.size}"
            } else {
                report = "连接失败 · ${result.exceptionOrNull()?.message ?: "initialize 失败"}"
            }
            testing = false
        }
    }

    fun test() {
        viewModelScope.launch {
            testing = true
            search.save(entity(), apiKey.ifBlank { null }, emptyMap())
            val handshake = if (kind == "mcp") runCatching { search.mcpHandshake(id) }.getOrNull() else null
            handshake?.let { mcpTools = it.tools }
            val runtime = search.runtime(id)
            val result = runCatching { runtime?.adapter?.search(SearchRequest("Kotlin 2.0 发布日期", maxResults = 3)) }
            val response = result.getOrNull()
            lastRaw = response?.rawBody
            val summary = if (response != null && response.results.isNotEmpty()) {
                val dated = response.results.count { it.publishedAt != null }
                val body = response.results.count { !it.content.isNullOrBlank() }
                val extra = listOfNotNull(
                    handshake?.let { "initialize ✓ ${it.serverName} ${it.serverVersion}" },
                    response.detail,
                ).joinToString(" · ")
                val suffix = if (extra.isBlank()) "" else " · $extra"
                "测试通过 · 日期 $dated/${response.results.size} · 正文 $body/${response.results.size}$suffix"
            } else {
                val reason = result.exceptionOrNull()?.message ?: if (runtime == null) "这个来源还不能用" else "没有结果"
                "测试失败 · $reason"
            }
            report = summary
            lastTestFailed = response == null || response.results.isEmpty()
            search.recordTest(id, !lastTestFailed, summary)
            testing = false
        }
    }

    fun remap() {
        val raw = lastRaw ?: return
        val root = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return
        val fields = parseLines(fieldsText)
        val count = if (kind == "mcp") {
            McpResults.readHits(root, itemsPath, fields)?.size ?: 0
        } else {
            JsonPath.readArray(root, itemsPath.ifBlank { "$.results" }).count { item ->
                !JsonPath.readText(item, fields["url"]).isNullOrBlank()
            }
        }
        report = "重新映射 · $count 条 · 未重新请求"
    }

    private fun entity(): SearchProfileEntity = SearchProfileEntity(
        id = id,
        name = name.ifBlank { "搜索来源" },
        kind = kind,
        service = if (kind == "builtin") service else kind,
        baseUrl = baseUrl,
        enabled = enabled,
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
        lastTestFailed = lastTestFailed,
        mcpAuth = mcpAuth,
        mcpTool = mcpTool,
        mcpParamMap = mcpParamMap,
        useModelCleanup = useModelCleanup,
        cleanupProfileId = cleanupProfileId,
        llmProfileId = llmProfileId,
        boundModel = boundModel,
        maxCitations = maxCitations.toIntOrNull()?.coerceIn(1, 20) ?: 5,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchEditScreen(
    onBack: () -> Unit,
    onOpenLlm: () -> Unit,
    model: SearchEditViewModel = hiltViewModel(),
) {
    var showRaw by remember { mutableStateOf(false) }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(kindLabel(model.kind), style = MaterialTheme.typography.titleMedium)
            Text("类型 · ${kindLabel(model.kind)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(model.name, { model.name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("启用", modifier = Modifier.weight(1f))
                Switch(checked = model.enabled, onCheckedChange = { model.enabled = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (model.international) "需要国际网络" else "国内直连", modifier = Modifier.weight(1f))
                Switch(checked = model.international, onCheckedChange = { model.international = it })
            }
            when (model.kind) {
                "builtin" -> BuiltinFields(model)
                "custom" -> CustomFields(model)
                "mcp" -> McpFields(model)
                else -> ModelFields(model, onOpenLlm)
            }
            model.report?.let { Text(it) }
            if (model.lastRaw != null && (model.kind == "custom" || model.kind == "mcp")) {
                Row {
                    TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "收起原始响应" else "原始响应") }
                    TextButton(onClick = model::remap) { Text("重新映射") }
                }
            }
            if (showRaw) {
                Text(
                    mask(model.lastRaw.orEmpty(), model.apiKey),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = model::test, enabled = !model.testing) { Text(if (model.testing) "停止" else "连接测试") }
                Button(onClick = { model.save(onBack) }) { Text("保存") }
            }
        }
    }
}

@Composable
private fun BuiltinFields(model: SearchEditViewModel) {
    Text("服务")
    Text("国内直连", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    ServiceChips(model, BuiltinSearchService.entries.filter { it.domesticDirect && it != BuiltinSearchService.SEARXNG })
    Text("需要国际网络", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    ServiceChips(model, BuiltinSearchService.entries.filter { !it.domesticDirect })
    Text("自建", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    ServiceChips(model, listOf(BuiltinSearchService.SEARXNG))
    OutlinedTextField(model.baseUrl, { model.baseUrl = it }, label = { Text("接口地址") }, modifier = Modifier.fillMaxWidth())
    if (model.service != BuiltinSearchService.SEARXNG.name) {
        KeyField(model)
    }
}

@Composable
private fun ServiceChips(model: SearchEditViewModel, services: List<BuiltinSearchService>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        services.forEach { service ->
            FilterChip(selected = model.service == service.name, onClick = { model.applyService(service) }, label = { Text(service.label) })
        }
    }
}

@Composable
private fun CustomFields(model: SearchEditViewModel) {
    KeyField(model)
    OutlinedTextField(model.method, { model.method = it }, label = { Text("方法") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(model.urlTemplate, { model.urlTemplate = it }, label = { Text("URL 模板") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(model.headersText, { model.headersText = it }, label = { Text("请求头模板") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(model.bodyTemplate, { model.bodyTemplate = it }, label = { Text("请求体模板") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
    OutlinedTextField(model.itemsPath, { model.itemsPath = it }, label = { Text("结果数组路径") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(model.fieldsText, { model.fieldsText = it }, label = { Text("字段映射") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
    Text("title 和 url 必填。密钥写 {{secret.名称}}，不会导出。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun McpFields(model: SearchEditViewModel) {
    OutlinedTextField(model.baseUrl, { model.baseUrl = it }, label = { Text("服务器地址") }, modifier = Modifier.fillMaxWidth())
    Text("认证方式")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("none" to "无", "bearer" to "Bearer 令牌", "headers" to "自定义请求头").forEach { (id, label) ->
            FilterChip(selected = model.mcpAuth == id, onClick = { model.mcpAuth = id }, label = { Text(label) })
        }
    }
    if (model.mcpAuth == "bearer") KeyField(model, "Bearer 令牌")
    if (model.mcpAuth == "headers") {
        OutlinedTextField(
            model.headersText,
            { model.headersText = it },
            label = { Text("自定义请求头") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        Text("一行一个，名称=值。保存后值只留在本机。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    OutlinedButton(onClick = model::listTools, enabled = !model.testing && model.baseUrl.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        Text("连接并列出工具")
    }
    if (model.mcpTools.isNotEmpty()) {
        Text("工具")
        model.mcpTools.forEach { tool ->
            FilterChip(
                selected = model.mcpTool == tool.name,
                onClick = { model.selectTool(tool) },
                label = {
                    Text(if (tool.looksLikeSearch) "${tool.name} · 像搜索" else tool.name)
                },
            )
            if (tool.description.isNotBlank()) {
                Text(tool.description, maxLines = 2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    OutlinedTextField(model.mcpParamMap, { model.mcpParamMap = it }, label = { Text("参数映射") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
    OutlinedTextField(model.itemsPath, { model.itemsPath = it }, label = { Text("结果数组路径") }, supportingText = { Text("可留空，走自动解析") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(model.fieldsText, { model.fieldsText = it }, label = { Text("结果映射") }, supportingText = { Text("可留空，走自动解析") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("用模型整理结果", modifier = Modifier.weight(1f))
        Switch(checked = model.useModelCleanup, onCheckedChange = { model.useModelCleanup = it })
    }
    Text("每个网址都必须原样出现在工具返回的文本里，否则丢弃。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (model.useModelCleanup) {
        LlmChips(model.allLlm, model.cleanupProfileId) { model.cleanupProfileId = it.id }
    }
    Text("只调用所选的这一个工具；发送给服务器的只有检索词，不含文章正文。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ModelFields(model: SearchEditViewModel, onOpenLlm: () -> Unit) {
    if (model.llmChoices.isEmpty()) {
        Text("还没有支持内置联网检索的 LLM 配置档")
        OutlinedButton(onClick = onOpenLlm, modifier = Modifier.fillMaxWidth()) { Text("去新建配置档") }
        return
    }
    Text("绑定的 LLM 配置档")
    LlmChips(model.llmChoices, model.llmProfileId) { model.bindLlm(it) }
    OutlinedTextField(model.boundModel, { model.boundModel = it }, label = { Text("模型") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(model.maxCitations, { model.maxCitations = it }, label = { Text("每次最多取几条引用") }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun LlmChips(profiles: List<LlmProfileEntity>, selected: String, onPick: (LlmProfileEntity) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        profiles.forEach { profile ->
            FilterChip(selected = selected == profile.id, onClick = { onPick(profile) }, label = { Text(profile.name) })
        }
    }
}

@Composable
private fun KeyField(model: SearchEditViewModel, label: String = "API key") {
    OutlinedTextField(
        model.apiKey,
        { model.apiKey = it },
        label = { Text(if (model.savedLast4 != null) "$label · 末 4 位 ${model.savedLast4}" else label) },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun kindLabel(kind: String): String = when (kind) {
    "custom" -> "自定义 HTTP"
    "mcp" -> "MCP 服务器"
    "model" -> "模型内置联网"
    else -> "内置服务"
}

private fun sourceLine(profile: SearchProfileEntity): String {
    val detail = when (profile.kind) {
        "mcp" -> profile.mcpTool.ifBlank { profile.baseUrl.substringAfter("://").substringBefore("/") }
        "model" -> profile.boundModel.ifBlank { "未绑定" }
        "custom" -> profile.urlTemplate.substringAfter("://").substringBefore("/").substringBefore("?")
        else -> BuiltinSearchService.entries.firstOrNull { it.name == profile.service }?.label ?: profile.service
    }
    return "${kindLabel(profile.kind)} · $detail"
}

private fun parseLines(text: String): Map<String, String> =
    text.lineSequence().mapNotNull { line ->
        val index = line.indexOf('=')
        if (index <= 0) null else line.substring(0, index).trim() to line.substring(index + 1).trim()
    }.toMap()

private fun mask(raw: String, secret: String): String {
    if (secret.length < 4) return raw.take(1_200)
    return raw.replace(secret, "••••").take(1_200)
}
