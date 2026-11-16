package blog.writer.ui.settings
import blog.writer.copy.Copy

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
    onEgress: () -> Unit,
    onSearch: () -> Unit,
    model: SettingsViewModel = hiltViewModel(),
) {
    val appearance by model.appearanceState.collectAsState(blog.writer.data.repo.Appearance())
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text(Copy["back"]) }
            Text(Copy["settings"], style = MaterialTheme.typography.headlineSmall)
            SettingsRow(Copy["row_llm"], Copy["row_llm_detail"], onLlm)
            SettingsRow(Copy["row_search"], Copy["row_search_detail"], onSearch)
            SettingsRow(Copy["github"], Copy["row_github_detail"], onGithub)
            SettingsRow(Copy["row_egress"], Copy["row_egress_detail"], onEgress)
            Text(Copy["appearance"], color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            ToggleRow(Copy["follow_system_color"], appearance.dynamicColor) { model.update { current -> current.copy(dynamicColor = it) } }
            ToggleRow(Copy["haptics"], appearance.haptics) { model.update { current -> current.copy(haptics = it) } }
            ToggleRow(Copy["serif_body"], appearance.serif) { model.update { current -> current.copy(serif = it) } }
            Text(Copy.format("font_size", "size" to appearance.fontSizeSp.roundToInt()))
            Slider(
                value = appearance.fontSizeSp,
                onValueChange = { model.update { current -> current.copy(fontSizeSp = it) } },
                valueRange = 15f..22f,
            )
            Text(Copy["dynamic_color_note"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    search: blog.writer.data.repo.SearchRepository,
    history: blog.writer.data.repo.AiHistoryRepository,
) : ViewModel() {
    val profiles = llm.observeProfiles()
    val searchProfiles = search.observe()
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
    val searchProfiles by model.searchProfiles.collectAsState(emptyList())
    val assignments by model.assignments.collectAsState(ActionAssignments())
    val history by model.history.collectAsState(emptyList())
    val confirm by model.confirmClear.collectAsState()
    if (confirm) {
        AlertDialog(
            onDismissRequest = model::dismissClear,
            title = { Text(Copy["clear_credentials_title"]) },
            text = { Text(Copy["clear_credentials_body"]) },
            confirmButton = { TextButton(onClick = model::clear) { Text(Copy["clear"], color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = model::dismissClear) { Text(Copy["cancel"]) } },
        )
    }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onBack) { Text(Copy["back"]) }
                TextButton(onClick = model::askClear) { Text(Copy["clear_all_credentials"], color = MaterialTheme.colorScheme.error) }
            }
            Text(Copy["row_llm"], style = MaterialTheme.typography.headlineSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(Copy["profiles"], color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = onCreate) { Text(Copy["new_item"]) }
            }
            profiles.forEach { profile ->
                ProfileRow(profile, assignments, onClick = { onEdit(profile.id) }, onDelete = { model.delete(profile.id) })
            }
            if (profiles.isEmpty()) Text(Copy["no_profiles"], color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Copy["action_assignment"], color = MaterialTheme.colorScheme.primary)
            AssignmentRow(Copy["default_label"], assignments.defaultProfileId, assignments.defaultModel, profiles) { id, name ->
                model.saveAssignments(assignments.copy(defaultProfileId = id, defaultModel = name))
            }
            Text(
                if (assignments.continueWriting.followDefault) Copy.format("continue_follow", "temperature" to assignments.continueWriting.temperature)
                else Copy.format("continue_model", "model" to assignments.continueWriting.model, "temperature" to assignments.continueWriting.temperature),
            )
            TextButton(onClick = {
                model.saveAssignments(
                    assignments.copy(
                        continueWriting = assignments.continueWriting.copy(followDefault = !assignments.continueWriting.followDefault),
                    ),
                )
            }) { Text(if (assignments.continueWriting.followDefault) Copy["use_specific_profile"] else Copy["use_default_profile"]) }
            if (!assignments.continueWriting.followDefault && profiles.isNotEmpty()) {
                AssignmentRow(Copy["assignment_continue"], assignments.continueWriting.profileId, assignments.continueWriting.model, profiles) { id, name ->
                    model.saveAssignments(
                        assignments.copy(continueWriting = ModelChoice(id, name, followDefault = false, temperature = assignments.continueWriting.temperature)),
                    )
                }
            }
            Text(Copy.format("temperature_value", "temperature" to assignments.continueWriting.temperature))
            Slider(
                value = assignments.continueWriting.temperature.toFloat(),
                onValueChange = { value ->
                    val stepped = (value * 10).roundToInt() / 10.0
                    model.saveAssignments(assignments.copy(continueWriting = assignments.continueWriting.copy(temperature = stepped)))
                },
                valueRange = 0f..2f,
            )
            Text(
                Copy.format(
                    "fill_assignment",
                    "model" to if (assignments.fillMiddle.followDefault) Copy["follow_default"] else assignments.fillMiddle.model ?: Copy["not_specified"],
                    "temperature" to assignments.fillMiddle.temperature,
                    "words" to assignments.fillTargetWords,
                ),
            )
            Text(Copy.format("fact_assignment", "model" to if (assignments.factCheck.followDefault) Copy["follow_default"] else assignments.factCheck.model ?: Copy["not_specified"]))
            Text(Copy["retrieval"], style = MaterialTheme.typography.titleSmall)
            Text(Copy["retrieval_mode"], style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("AUTO" to Copy["mode_auto"], "SINGLE" to Copy["mode_single"], "MERGE" to Copy["mode_merge"]).forEach { (id, label) ->
                    FilterChip(
                        selected = assignments.factMode == id,
                        onClick = { model.saveAssignments(assignments.copy(factMode = id)) },
                        label = { Text(label) },
                    )
                }
            }
            if (assignments.factMode == "MERGE") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Copy.format("merge_count", "count" to assignments.factMergeCount), modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        model.saveAssignments(assignments.copy(factMergeCount = (assignments.factMergeCount - 1).coerceAtLeast(2)))
                    }) { Text(Copy["minus"]) }
                    TextButton(onClick = {
                        model.saveAssignments(assignments.copy(factMergeCount = (assignments.factMergeCount + 1).coerceAtMost(5)))
                    }) { Text(Copy["plus"]) }
                }
            }
            if (assignments.factMode == "SINGLE") {
                Text(Copy["mode_single"], style = MaterialTheme.typography.labelMedium)
                if (searchProfiles.isEmpty()) {
                    Text(Copy["no_search_sources"], color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    searchProfiles.forEach { profile ->
                        FilterChip(
                            selected = assignments.factSingleProfileId == profile.id,
                            onClick = { model.saveAssignments(assignments.copy(factSingleProfileId = profile.id)) },
                            label = { Text(profile.name) },
                        )
                    }
                }
            }
            Text(Copy["ai_history"], color = MaterialTheme.colorScheme.primary)
            if (history.isEmpty()) Text(Copy["no_history"], color = MaterialTheme.colorScheme.onSurfaceVariant)
            history.take(8).forEach { entry ->
                Text(Copy.format("history_line", "action" to entry.action, "model" to entry.model, "version" to entry.promptVersion), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = {
                model.saveAssignments(assignments.copy(fillMiddle = assignments.fillMiddle.copy(followDefault = !assignments.fillMiddle.followDefault)))
            }) { Text(if (assignments.fillMiddle.followDefault) Copy["fill_use_specific"] else Copy["fill_use_default"]) }
            TextButton(onClick = {
                model.saveAssignments(assignments.copy(factCheck = assignments.factCheck.copy(followDefault = !assignments.factCheck.followDefault)))
            }) { Text(if (assignments.factCheck.followDefault) Copy["fact_use_specific"] else Copy["fact_use_default"]) }
            if (!assignments.fillMiddle.followDefault) {
                AssignmentRow(Copy["action_fill"], assignments.fillMiddle.profileId, assignments.fillMiddle.model, profiles) { id, name ->
                    model.saveAssignments(assignments.copy(fillMiddle = assignments.fillMiddle.copy(profileId = id, model = name, followDefault = false)))
                }
            }
            if (!assignments.factCheck.followDefault) {
                AssignmentRow(Copy["action_fact"], assignments.factCheck.profileId, assignments.factCheck.model, profiles) { id, name ->
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
        val test = profile.testSummary ?: Copy["untested"]
        Text(test, style = MaterialTheme.typography.labelMedium, color = if (profile.testOk == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (assignments.defaultProfileId == profile.id) Text(Copy["default_label"], color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = onDelete) { Text(Copy["delete"]) }
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
            Text(if (profile.id == selectedId) Copy.format("selected_profile", "name" to profile.name, "model" to (selectedModel ?: profile.model)) else profile.name)
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
    var name by mutableStateOf(Copy["preset_deepseek"])
    var presetId by mutableStateOf(LlmPresets.deepSeek.id)
    var protocol by mutableStateOf(LlmProtocol.OpenAiCompatible)
    var baseUrl by mutableStateOf(LlmPresets.deepSeek.baseUrl)
    var modelName by mutableStateOf(LlmPresets.deepSeek.defaultModel)
    var keyInput by mutableStateOf("")
    var savedLast4 by mutableStateOf<String?>(null)
    var stream by mutableStateOf(true)
    var jsonOutput by mutableStateOf(true)
    var builtinSearch by mutableStateOf(false)
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
            builtinSearch = saved.builtinSearch
            allowHttp = saved.allowHttp
            contextLength = saved.contextLength
            maxOutput = saved.maxOutput
        }
    }

    fun applyPreset(preset: LlmPreset) {
        val previousName = LlmPresets.byId(presetId)?.name
        presetId = preset.id
        if (preset.id != "custom") {
            if (name.isBlank() || name == previousName) name = preset.name
            protocol = preset.protocol
            baseUrl = preset.baseUrl
            modelName = preset.defaultModel
            contextLength = preset.contextLength
            stream = preset.stream
            jsonOutput = preset.jsonOutput
            builtinSearch = preset.builtinSearch
            allowHttp = preset.baseUrl.startsWith("http://")
        }
    }

    fun test() {
        viewModelScope.launch {
            urlError = validateBaseUrl(baseUrl, allowHttp)
            if (urlError != null) return@launch
            val key = keyInput.ifBlank { llm.apiKey(id).orEmpty() }
            if (key.isBlank() || modelName.isBlank()) {
                report = Copy["fill_model_first"]
                return@launch
            }
            testing = true
            val endpoint = LlmEndpoint(protocol, baseUrl.trim(), key, modelName.trim(), stream = stream, jsonOutput = jsonOutput, connectTimeoutSec = 10, firstTokenTimeoutSec = 30, totalTimeoutSec = 60)
            val result = ConnectionTester().test(clients.create(endpoint), endpoint)
            testing = false
            val dash = Copy["dash"]
            report = if (result.ok) {
                Copy.format(
                    "connection_ok",
                    "first" to (result.firstTokenMs ?: dash),
                    "total" to (result.totalMs ?: dash),
                    "model" to (result.model ?: modelName),
                )
            } else {
                Copy.format(
                    "connection_failed",
                    "explanation" to (result.explanation ?: Copy["network_error"]),
                    "raw" to result.rawError?.let { "\n$it" }.orEmpty(),
                )
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
                    builtinSearch = builtinSearch && supportsBuiltinSearch(presetId, protocol),
                    connectTimeoutSec = 10,
                    firstTokenTimeoutSec = 30,
                    totalTimeoutSec = 180,
                    allowHttp = allowHttp,
                    extraHeadersJson = headersToText(emptyMap()),
                    keyLast4 = keyInput.takeLast(4).ifBlank { savedLast4 },
                    diverged = false,
                    testOk = report?.startsWith(Copy["connection_ok"].substringBefore(" ·")),
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
            TextButton(onClick = onBack) { Text(Copy["close"]) }
            Text(if (model.savedLast4 == null) Copy["new_profile"] else Copy["edit_profile"], style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(model.name, { model.name = it }, label = { Text(Copy["field_name"]) }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { showPresets = true }, modifier = Modifier.fillMaxWidth()) {
                Text(Copy.format("preset_line", "name" to (LlmPresets.byId(model.presetId)?.name ?: Copy["custom"])))
            }
            val protocols = listOf(LlmProtocol.OpenAiCompatible to Copy["protocol_openai"], LlmProtocol.Anthropic to Copy["anthropic"], LlmProtocol.Gemini to Copy["protocol_gemini"])
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
                label = { Text(Copy["field_base_url"]) },
                supportingText = { model.urlError?.let { Text(it) } },
                isError = model.urlError != null,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = if (model.keyInput.isEmpty() && model.savedLast4 != null) "" else model.keyInput,
                onValueChange = { model.keyInput = it },
                label = { Text(if (model.savedLast4 != null) Copy.format("api_key_last4", "last4" to model.savedLast4) else Copy["api_key"]) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(model.modelName, { model.modelName = it }, label = { Text(Copy["field_model"]) }, modifier = Modifier.fillMaxWidth())
            val searchSupported = supportsBuiltinSearch(model.presetId, model.protocol)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = model.stream, onClick = { model.stream = !model.stream }, label = { Text(Copy["stream_output"]) })
                FilterChip(selected = model.jsonOutput, onClick = { model.jsonOutput = !model.jsonOutput }, label = { Text(Copy["json_output"]) })
                FilterChip(
                    selected = searchSupported && model.builtinSearch,
                    onClick = { if (searchSupported) model.builtinSearch = !model.builtinSearch },
                    enabled = searchSupported,
                    label = { Text(Copy["builtin_search"]) },
                )
            }
            Text(
                if (searchSupported) Copy["builtin_search_on"]
                else Copy["builtin_search_unsupported"],
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToggleRow(Copy["allow_http"], model.allowHttp) { model.allowHttp = it }
            if (model.allowHttp) Text(Copy["allow_http_warning"], color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            model.report?.let { Text(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = model::test, enabled = !model.testing && model.name.isNotBlank() && model.modelName.isNotBlank() && model.baseUrl.isNotBlank()) { Text(if (model.testing) Copy["stop"] else Copy["connection_test"]) }
                Button(onClick = { model.save(onBack) }, enabled = model.name.isNotBlank() && model.modelName.isNotBlank() && model.baseUrl.isNotBlank()) { Text(Copy["save"]) }
            }
        }
    }
    if (showPresets) {
        AlertDialog(
            onDismissRequest = { showPresets = false },
            title = { Text(Copy["presets_title"]) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(Copy["domestic_direct"], color = MaterialTheme.colorScheme.primary)
                    LlmPresets.domestic.forEach { preset ->
                        TextButton(onClick = { model.applyPreset(preset); showPresets = false }) { Text(preset.name) }
                    }
                    Text(Copy["overseas"], color = MaterialTheme.colorScheme.primary)
                    LlmPresets.overseas.forEach { preset ->
                        TextButton(onClick = { model.applyPreset(preset); showPresets = false }) { Text(preset.name) }
                    }
                    TextButton(onClick = { model.applyPreset(LlmPresets.custom); showPresets = false }) { Text(Copy["custom"]) }
                }
            },
            confirmButton = { TextButton(onClick = { showPresets = false }) { Text(Copy["close"]) } },
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
            message = Copy["saved"]
            last4 = github.tokenLast4()
            token = ""
        }
    }
}

@Composable
fun GithubScreen(onBack: () -> Unit, model: GithubViewModel = hiltViewModel()) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onBack) { Text(Copy["back"]) }
            Text(Copy["github"], style = MaterialTheme.typography.headlineSmall)
            Text(Copy["github_token_note"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            GithubField(Copy["github_owner"], model.owner) { model.owner = it }
            GithubField(Copy["github_repo"], model.repo) { model.repo = it }
            GithubField(Copy["github_branch"], model.branch) { model.branch = it }
            GithubField(Copy["github_blog_owner"], model.blogOwner) { model.blogOwner = it }
            GithubField(Copy["github_blog_repo"], model.blogRepo) { model.blogRepo = it }
            GithubField(Copy["github_site"], model.site) { model.site = it }
            GithubField(Copy["github_wx_index"], model.wxIndex) { model.wxIndex = it }
            GithubField(Copy["github_wechat_name"], model.wechatName) { model.wechatName = it }
            OutlinedTextField(
                value = model.token,
                onValueChange = { model.token = it },
                label = { Text(if (model.last4 != null) Copy.format("github_token_last4", "last4" to model.last4) else Copy["github_pat"]) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (model.message.isNotBlank()) Text(model.message)
            Button(onClick = model::save, modifier = Modifier.fillMaxWidth()) { Text(Copy["save"]) }
        }
    }
}

@Composable
private fun GithubField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
}

private fun supportsBuiltinSearch(presetId: String, protocol: LlmProtocol): Boolean {
    if (LlmPresets.byId(presetId)?.builtinSearch == true) return true
    return protocol == LlmProtocol.Anthropic || protocol == LlmProtocol.Gemini
}

private fun protocolLabel(protocol: String): String = when (protocol) {
    "anthropic" -> Copy["anthropic"]
    "gemini" -> Copy["protocol_gemini"]
    else -> Copy["protocol_openai"]
}
