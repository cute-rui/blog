package blog.writer.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Slider
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
import blog.writer.ai.ActionAssignments
import blog.writer.ai.ConnectionTester
import blog.writer.ai.LlmClients
import blog.writer.ai.LlmEndpoint
import blog.writer.ai.LlmPreset
import blog.writer.ai.LlmPresets
import blog.writer.ai.LlmProtocol
import blog.writer.ai.ModelChoice
import blog.writer.ai.endpointHost
import blog.writer.ai.validateBaseUrl
import blog.writer.data.db.LlmProfileEntity
import blog.writer.data.db.SyncStateEntity
import blog.writer.data.repo.AppearanceRepository
import blog.writer.data.repo.GithubSettingsRepository
import blog.writer.data.repo.LlmRepository
import blog.writer.data.repo.headersToText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appearance: AppearanceRepository,
) : ViewModel() {
    val appearanceState = appearance.appearance
    fun update(block: (blog.writer.data.repo.Appearance) -> blog.writer.data.repo.Appearance) {
        viewModelScope.launch { appearance.update(block) }
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLlm: () -> Unit,
    onGithub: () -> Unit,
    onSearch: () -> Unit,
    model: SettingsViewModel = hiltViewModel(),
) {
    val appearance by model.appearanceState.collectAsState(blog.writer.data.repo.Appearance())
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("设置", style = MaterialTheme.typography.headlineSmall)
            SettingsRow("AI 模型", "配置档、连接测试、每个动作用哪个模型", onLlm)
            SettingsRow("搜索来源", "事实核查用的检索配置档", onSearch)
            SettingsRow("GitHub", "内容仓库、令牌、复制页清单", onGithub)
            Text("外观", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            ToggleRow("跟随系统配色", appearance.dynamicColor) { model.update { current -> current.copy(dynamicColor = it) } }
            ToggleRow("触感反馈", appearance.haptics) { model.update { current -> current.copy(haptics = it) } }
            ToggleRow("正文用宋体", appearance.serif) { model.update { current -> current.copy(serif = it) } }
            Text("编辑器字号 ${appearance.fontSizeSp.roundToInt()} sp")
            Slider(
                value = appearance.fontSizeSp,
                onValueChange = { model.update { current -> current.copy(fontSizeSp = it) } },
                valueRange = 15f..22f,
            )
            Text("动态配色默认关闭。打开后也只换按钮的黛蓝，纸面和赭石色不变。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsRow(title: String, detail: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@HiltViewModel
class LlmHomeViewModel @Inject constructor(
    private val llm: LlmRepository,
    history: blog.writer.data.repo.AiHistoryRepository,
) : ViewModel() {
    val profiles = llm.observeProfiles()
    val assignments = llm.observeAssignments()
    val history = history.observe()
    private val _confirmClear = MutableStateFlow(false)
    val confirmClear = _confirmClear

    fun delete(id: String) = viewModelScope.launch { llm.delete(id) }
    fun askClear() { _confirmClear.value = true }
    fun clear() = viewModelScope.launch {
        llm.clearSecrets()
        _confirmClear.value = false
    }
    fun dismissClear() { _confirmClear.value = false }
    fun saveAssignments(value: ActionAssignments) = viewModelScope.launch { llm.saveAssignments(value) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmHomeScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onCreate: () -> Unit,
    model: LlmHomeViewModel = hiltViewModel(),
) {
    val profiles by model.profiles.collectAsState(emptyList())
    val assignments by model.assignments.collectAsState(ActionAssignments())
    val history by model.history.collectAsState(emptyList())
    val confirm by model.confirmClear.collectAsState()
    if (confirm) {
        AlertDialog(
            onDismissRequest = model::dismissClear,
            title = { Text("清除全部凭据？") },
            text = { Text("API key 和 GitHub 令牌会从这台手机删掉。配置档还在。") },
            confirmButton = { TextButton(onClick = model::clear) { Text("清除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = model::dismissClear) { Text("取消") } },
        )
    }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onBack) { Text("返回") }
                TextButton(onClick = model::askClear) { Text("清除全部凭据", color = MaterialTheme.colorScheme.error) }
            }
            Text("AI 模型", style = MaterialTheme.typography.headlineSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("配置档", color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = onCreate) { Text("新建") }
            }
            profiles.forEach { profile ->
                ProfileRow(profile, assignments, onClick = { onEdit(profile.id) }, onDelete = { model.delete(profile.id) })
            }
            if (profiles.isEmpty()) Text("还没有配置档。选一个预设就能测连接。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("AI 动作分配", color = MaterialTheme.colorScheme.primary)
            AssignmentRow("默认", assignments.defaultProfileId, assignments.defaultModel, profiles) { id, name ->
                model.saveAssignments(assignments.copy(defaultProfileId = id, defaultModel = name))
            }
            Text(
                if (assignments.continueWriting.followDefault) "给我思路继续写 · 跟随默认 · 温度 ${assignments.continueWriting.temperature}"
                else "给我思路继续写 · ${assignments.continueWriting.model} · 温度 ${assignments.continueWriting.temperature}",
            )
            TextButton(onClick = {
                model.saveAssignments(
                    assignments.copy(
                        continueWriting = assignments.continueWriting.copy(followDefault = !assignments.continueWriting.followDefault),
                    ),
                )
            }) { Text(if (assignments.continueWriting.followDefault) "改为指定配置档" else "改为跟随默认") }
            if (!assignments.continueWriting.followDefault && profiles.isNotEmpty()) {
                AssignmentRow("续写", assignments.continueWriting.profileId, assignments.continueWriting.model, profiles) { id, name ->
                    model.saveAssignments(
                        assignments.copy(continueWriting = ModelChoice(id, name, followDefault = false, temperature = assignments.continueWriting.temperature)),
                    )
                }
            }
            Text("温度 ${assignments.continueWriting.temperature}")
            Slider(
                value = assignments.continueWriting.temperature.toFloat(),
                onValueChange = { value ->
                    val stepped = (value * 10).roundToInt() / 10.0
                    model.saveAssignments(assignments.copy(continueWriting = assignments.continueWriting.copy(temperature = stepped)))
                },
                valueRange = 0f..2f,
            )
            Text(
                "补全中间 · ${if (assignments.fillMiddle.followDefault) "跟随默认" else assignments.fillMiddle.model ?: "未指定"} · 温度 ${assignments.fillMiddle.temperature} · ${assignments.fillTargetWords} 字",
            )
            Text("事实核查 · ${if (assignments.factCheck.followDefault) "跟随默认" else assignments.factCheck.model ?: "未指定"} · 温度固定 0.1 · ${assignments.factMode}")
            Text("AI 历史", color = MaterialTheme.colorScheme.primary)
            if (history.isEmpty()) Text("还没有记录。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            history.take(8).forEach { entry ->
                Text("${entry.action} · ${entry.model} · 模板 v${entry.promptVersion}", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = {
                model.saveAssignments(assignments.copy(fillMiddle = assignments.fillMiddle.copy(followDefault = !assignments.fillMiddle.followDefault)))
            }) { Text(if (assignments.fillMiddle.followDefault) "补全中间改为指定配置档" else "补全中间改为跟随默认") }
            TextButton(onClick = {
                model.saveAssignments(assignments.copy(factCheck = assignments.factCheck.copy(followDefault = !assignments.factCheck.followDefault)))
            }) { Text(if (assignments.factCheck.followDefault) "事实核查改为指定配置档" else "事实核查改为跟随默认") }
            if (!assignments.fillMiddle.followDefault) {
                AssignmentRow("补全中间", assignments.fillMiddle.profileId, assignments.fillMiddle.model, profiles) { id, name ->
                    model.saveAssignments(assignments.copy(fillMiddle = assignments.fillMiddle.copy(profileId = id, model = name, followDefault = false)))
                }
            }
            if (!assignments.factCheck.followDefault) {
                AssignmentRow("事实核查", assignments.factCheck.profileId, assignments.factCheck.model, profiles) { id, name ->
                    model.saveAssignments(assignments.copy(factCheck = assignments.factCheck.copy(profileId = id, model = name, followDefault = false)))
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(profile: LlmProfileEntity, assignments: ActionAssignments, onClick: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(profile.name, style = MaterialTheme.typography.titleSmall)
        Text(
            "${protocolLabel(profile.protocol)} · ${endpointHost(profile.baseUrl)} · ${profile.model}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val test = profile.testSummary ?: "未测试"
        Text(test, style = MaterialTheme.typography.labelMedium, color = if (profile.testOk == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (assignments.defaultProfileId == profile.id) Text("默认", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = onDelete) { Text("删除") }
    }
}

@Composable
private fun AssignmentRow(
    label: String,
    selectedId: String?,
    selectedModel: String?,
    profiles: List<LlmProfileEntity>,
    onPick: (String, String) -> Unit,
) {
    Text(label)
    profiles.forEach { profile ->
        TextButton(onClick = { onPick(profile.id, profile.model) }) {
            Text(if (profile.id == selectedId) "已选 ${profile.name} · ${selectedModel ?: profile.model}" else profile.name)
        }
    }
}

@HiltViewModel
class LlmEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val llm: LlmRepository,
    private val clients: LlmClients,
) : ViewModel() {
    val id: String = savedStateHandle.get<String>("id").orEmpty().let { if (it == "new" || it.isBlank()) UUID.randomUUID().toString() else it }
    var name by mutableStateOf("DeepSeek")
    var presetId by mutableStateOf(LlmPresets.deepSeek.id)
    var protocol by mutableStateOf(LlmProtocol.OpenAiCompatible)
    var baseUrl by mutableStateOf(LlmPresets.deepSeek.baseUrl)
    var modelName by mutableStateOf(LlmPresets.deepSeek.defaultModel)
    var keyInput by mutableStateOf("")
    var savedLast4 by mutableStateOf<String?>(null)
    var stream by mutableStateOf(true)
    var jsonOutput by mutableStateOf(true)
    var allowHttp by mutableStateOf(false)
    var contextLength by mutableStateOf(65_536)
    var maxOutput by mutableStateOf(2048)
    var urlError by mutableStateOf<String?>(null)
    var testing by mutableStateOf(false)
    var report by mutableStateOf<String?>(null)
    private var existing = false

    init {
        viewModelScope.launch {
            val saved = llm.profile(id) ?: return@launch
            existing = true
            name = saved.name
            presetId = saved.presetId
            protocol = when (saved.protocol) {
                "anthropic" -> LlmProtocol.Anthropic
                "gemini" -> LlmProtocol.Gemini
                else -> LlmProtocol.OpenAiCompatible
            }
            baseUrl = saved.baseUrl
            modelName = saved.model
            savedLast4 = saved.keyLast4
            stream = saved.stream
            jsonOutput = saved.jsonOutput
            allowHttp = saved.allowHttp
            contextLength = saved.contextLength
            maxOutput = saved.maxOutput
        }
    }

    fun applyPreset(preset: LlmPreset) {
        presetId = preset.id
        if (preset.id != "custom") {
            name = if (name.isBlank() || LlmPresets.byId(presetId)?.name == name) preset.name else name
            protocol = preset.protocol
            baseUrl = preset.baseUrl
            modelName = preset.defaultModel
            contextLength = preset.contextLength
            stream = preset.stream
            jsonOutput = preset.jsonOutput
        }
    }

    fun test() {
        viewModelScope.launch {
            urlError = validateBaseUrl(baseUrl, allowHttp)
            if (urlError != null) return@launch
            val key = keyInput.ifBlank { llm.apiKey(id).orEmpty() }
            if (key.isBlank() || modelName.isBlank()) {
                report = "先填写模型和 API key"
                return@launch
            }
            testing = true
            val endpoint = LlmEndpoint(protocol, baseUrl.trim(), key, modelName.trim(), stream = stream, jsonOutput = jsonOutput, connectTimeoutSec = 10, firstTokenTimeoutSec = 30, totalTimeoutSec = 60)
            val result = ConnectionTester().test(clients.create(endpoint), endpoint)
            testing = false
            report = if (result.ok) {
                "连接成功 · 首字 ${result.firstTokenMs ?: "—"} ms · 总耗时 ${result.totalMs ?: "—"} ms · ${result.model ?: modelName}"
            } else {
                "连接失败 · ${result.explanation ?: "网络错误"}${result.rawError?.let { "\n$it" }.orEmpty()}"
            }
        }
    }

    fun save(onDone: () -> Unit) {
        viewModelScope.launch {
            urlError = validateBaseUrl(baseUrl, allowHttp)
            if (urlError != null || name.isBlank() || modelName.isBlank()) return@launch
            llm.save(
                LlmProfileEntity(
                    id = id,
                    name = name.trim(),
                    presetId = presetId,
                    basedOnPresetId = presetId.takeIf { it != "custom" },
                    protocol = when (protocol) {
                        LlmProtocol.Anthropic -> "anthropic"
                        LlmProtocol.Gemini -> "gemini"
                        LlmProtocol.OpenAiCompatible -> "openai"
                    },
                    baseUrl = baseUrl.trim(),
                    model = modelName.trim(),
                    contextLength = contextLength,
                    maxOutput = maxOutput,
                    stream = stream,
                    jsonOutput = jsonOutput,
                    builtinSearch = false,
                    connectTimeoutSec = 10,
                    firstTokenTimeoutSec = 30,
                    totalTimeoutSec = 180,
                    allowHttp = allowHttp,
                    extraHeadersJson = headersToText(emptyMap()),
                    keyLast4 = keyInput.takeLast(4).ifBlank { savedLast4 },
                    diverged = false,
                    testOk = report?.startsWith("连接成功"),
                    testSummary = report,
                    testAtEpochMs = if (report == null) null else System.currentTimeMillis(),
                ),
                newKey = keyInput.ifBlank { null },
            )
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmEditScreen(onBack: () -> Unit, model: LlmEditViewModel = hiltViewModel()) {
    var showPresets by remember { mutableStateOf(false) }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onBack) { Text("关闭") }
            Text(if (model.savedLast4 == null) "新建配置档" else "编辑配置档", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(model.name, { model.name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { showPresets = true }, modifier = Modifier.fillMaxWidth()) {
                Text("预设 · ${LlmPresets.byId(model.presetId)?.name ?: "自定义"}")
            }
            val protocols = listOf(LlmProtocol.OpenAiCompatible to "OpenAI 兼容", LlmProtocol.Anthropic to "Anthropic", LlmProtocol.Gemini to "Gemini")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                protocols.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = model.protocol == value,
                        onClick = { model.protocol = value },
                        shape = SegmentedButtonDefaults.itemShape(index, protocols.size),
                    ) { Text(label) }
                }
            }
            OutlinedTextField(
                model.baseUrl,
                { model.baseUrl = it; model.urlError = null },
                label = { Text("接口地址（Base URL）") },
                supportingText = { model.urlError?.let { Text(it) } },
                isError = model.urlError != null,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = if (model.keyInput.isEmpty() && model.savedLast4 != null) "" else model.keyInput,
                onValueChange = { model.keyInput = it },
                label = { Text(if (model.savedLast4 != null) "API key · 末 4 位 ${model.savedLast4}" else "API key") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(model.modelName, { model.modelName = it }, label = { Text("模型") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = model.stream, onClick = { model.stream = !model.stream }, label = { Text("流式输出") })
                FilterChip(selected = model.jsonOutput, onClick = { model.jsonOutput = !model.jsonOutput }, label = { Text("JSON 输出") })
                FilterChip(selected = false, onClick = {}, enabled = false, label = { Text("内置联网检索") })
            }
            Text("该预设暂未适配联网检索。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ToggleRow("允许 http://", model.allowHttp) { model.allowHttp = it }
            if (model.allowHttp) Text("仅用于局域网自建网关，key 会以明文传输", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            model.report?.let { Text(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = model::test, enabled = !model.testing && model.name.isNotBlank() && model.modelName.isNotBlank() && model.baseUrl.isNotBlank()) { Text(if (model.testing) "停止" else "连接测试") }
                Button(onClick = { model.save(onBack) }, enabled = model.name.isNotBlank() && model.modelName.isNotBlank() && model.baseUrl.isNotBlank()) { Text("保存") }
            }
        }
    }
    if (showPresets) {
        AlertDialog(
            onDismissRequest = { showPresets = false },
            title = { Text("预设") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("国内直连", color = MaterialTheme.colorScheme.primary)
                    LlmPresets.domestic.forEach { preset ->
                        TextButton(onClick = { model.applyPreset(preset); showPresets = false }) { Text(preset.name) }
                    }
                    Text("海外", color = MaterialTheme.colorScheme.primary)
                    LlmPresets.overseas.forEach { preset ->
                        TextButton(onClick = { model.applyPreset(preset); showPresets = false }) { Text(preset.name) }
                    }
                    TextButton(onClick = { model.applyPreset(LlmPresets.custom); showPresets = false }) { Text("自定义") }
                }
            },
            confirmButton = { TextButton(onClick = { showPresets = false }) { Text("关闭") } },
        )
    }
}

@HiltViewModel
class GithubViewModel @Inject constructor(
    private val github: GithubSettingsRepository,
) : ViewModel() {
    var owner by mutableStateOf("")
    var repo by mutableStateOf("")
    var branch by mutableStateOf("main")
    var blogOwner by mutableStateOf("")
    var blogRepo by mutableStateOf("")
    var site by mutableStateOf("https://example.com")
    var wxIndex by mutableStateOf("")
    var wechatName by mutableStateOf("")
    var token by mutableStateOf("")
    var last4 by mutableStateOf<String?>(null)
    var message by mutableStateOf("")

    init {
        viewModelScope.launch {
            val state = github.current()
            owner = state.owner
            repo = state.repo
            branch = state.branch
            blogOwner = state.blogOwner
            blogRepo = state.blogRepo
            site = state.siteOrigin
            wxIndex = state.wxIndexUrl
            wechatName = state.wechatName
            last4 = github.tokenLast4()
        }
    }

    fun save() {
        viewModelScope.launch {
            github.save(
                SyncStateEntity(
                    owner = owner.trim(),
                    repo = repo.trim(),
                    branch = branch.trim().ifBlank { "main" },
                    blogOwner = blogOwner.trim(),
                    blogRepo = blogRepo.trim(),
                    baseCommitSha = github.current().baseCommitSha,
                    lastPullEpochMs = github.current().lastPullEpochMs,
                    siteOrigin = site.trim(),
                    wxIndexUrl = wxIndex.trim(),
                    wechatName = wechatName.trim(),
                    wechatMode = github.current().wechatMode,
                ),
                newToken = token.ifBlank { null },
            )
            message = "已保存"
            last4 = github.tokenLast4()
            token = ""
        }
    }
}

@Composable
fun GithubScreen(onBack: () -> Unit, model: GithubViewModel = hiltViewModel()) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("GitHub", style = MaterialTheme.typography.headlineSmall)
            Text("令牌只放在这台手机的 Keystore 里。仓库里不会出现 key。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            GithubField("内容仓库所有者", model.owner) { model.owner = it }
            GithubField("内容仓库名", model.repo) { model.repo = it }
            GithubField("分支", model.branch) { model.branch = it }
            GithubField("博客仓库所有者", model.blogOwner) { model.blogOwner = it }
            GithubField("博客仓库名", model.blogRepo) { model.blogRepo = it }
            GithubField("站点地址", model.site) { model.site = it }
            GithubField("复制页清单 URL", model.wxIndex) { model.wxIndex = it }
            GithubField("公众号名称", model.wechatName) { model.wechatName = it }
            OutlinedTextField(
                value = model.token,
                onValueChange = { model.token = it },
                label = { Text(if (model.last4 != null) "令牌 · 末 4 位 ${model.last4}" else "细粒度 PAT") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (model.message.isNotBlank()) Text(model.message)
            Button(onClick = model::save, modifier = Modifier.fillMaxWidth()) { Text("保存") }
        }
    }
}

@Composable
private fun GithubField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
}

private fun protocolLabel(protocol: String): String = when (protocol) {
    "anthropic" -> "Anthropic"
    "gemini" -> "Gemini"
    else -> "OpenAI 兼容"
}
