package blog.writer.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blog.writer.ai.ContinueIdea
import blog.writer.ai.LlmClients
import blog.writer.ai.LlmEvent
import blog.writer.ai.LlmRequest
import blog.writer.ai.Prompts
import blog.writer.ai.assembleContext
import blog.writer.ai.IdeaStreamParser
import blog.writer.content.ContentIssue
import blog.writer.content.ContentRules
import blog.writer.content.PostDocument
import blog.writer.content.PostFrontMatter
import blog.writer.content.PublishSelection
import blog.writer.content.PublishTargets
import blog.writer.content.WechatHtmlLength
import blog.writer.content.countWritingCharacters
import blog.writer.content.narrowSelection
import blog.writer.content.publishButtonLabel
import blog.writer.data.repo.PostRepository
import blog.writer.data.repo.LlmRepository
import blog.writer.data.repo.SyncCoordinator
import blog.writer.editor.PendingDrafts
import blog.writer.editor.PendingSpan
import blog.writer.ui.nav.decodePath
import blog.writer.ui.nav.encodePath
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TextCommand(val text: String, val cursor: Int)

data class EditorUi(
    val loaded: Boolean = false,
    val path: String = "",
    val title: String = "",
    val body: String = "",
    val matter: PostFrontMatter = PostFrontMatter(),
    val pending: PendingSpan? = null,
    val wordCount: Int = 0,
    val savedLabel: String = "",
    val ideas: List<ContinueIdea> = emptyList(),
    val ideasLoading: Boolean = false,
    val ideasError: String? = null,
    val needProfile: Boolean = false,
    val message: String? = null,
    val askDraft: Boolean = false,
    val checks: List<ContentIssue> = emptyList(),
    val publishLabel: String = "只保存，不发布",
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val posts: PostRepository,
    private val llm: LlmRepository,
    private val clients: LlmClients,
    private val sync: SyncCoordinator,
) : ViewModel() {
    private val handle = savedStateHandle
    private var path: String = decodePath(savedStateHandle.get<String>("path").orEmpty())
    private var display = ""
    private var pending: PendingSpan? = null
    private var matter = PostFrontMatter()
    private var saveJob: Job? = null
    private var ideaJob: Job? = null
    private var undo: Pair<String, PendingSpan>? = null

    private val _ui = MutableStateFlow(EditorUi(path = path))
    val ui = _ui.asStateFlow()
    private val _commands = MutableSharedFlow<TextCommand>(extraBufferCapacity = 1)
    val commands = _commands.asSharedFlow()
    val syncState = sync.state

    init {
        viewModelScope.launch { reload() }
    }

    fun onDisplayText(text: String) {
        if (!ui.value.loaded || text == display) return
        pending = pending?.let { PendingDrafts.adjust(display, text, it) }
        display = text
        publishUi(savedLabel = "正在保存…")
        scheduleSave()
    }

    fun accept() {
        val span = pending ?: return
        undo = display to span
        pending = null
        viewModelScope.launch {
            posts.clearPending(path)
            persist()
            publishUi(message = "已接受")
        }
    }

    fun reject() {
        val span = pending ?: return
        undo = display to span
        val text = PendingDrafts.reject(display, span)
        display = text
        pending = null
        _commands.tryEmit(TextCommand(text, span.start))
        viewModelScope.launch {
            posts.clearPending(path)
            persist()
            publishUi(message = "已丢弃")
        }
    }

    fun undoMessage() {
        val snap = undo ?: return
        undo = null
        display = snap.first
        pending = snap.second
        _commands.tryEmit(TextCommand(snap.first, snap.second.endExclusive))
        viewModelScope.launch {
            posts.savePending(path, snap.second.start, snap.first.substring(snap.second.start, snap.second.endExclusive))
            persist()
            publishUi(message = null)
        }
    }

    fun startIdeas(cursor: Int, extra: String) {
        if (pending != null) {
            publishUi(askDraft = true)
            return
        }
        ideaJob?.cancel()
        ideaJob = viewModelScope.launch {
            _ui.update { it.copy(ideas = emptyList(), ideasLoading = true, ideasError = null, needProfile = false) }
            val resolved = llm.observeAssignments().first().resolveContinue()
            val endpoint = resolved?.let { llm.endpoint(it.profileId, it.model) }
            if (resolved == null || endpoint == null) {
                _ui.update { it.copy(ideasLoading = false, needProfile = true) }
                return@launch
            }
            val acceptedCursor = PendingDrafts.toAcceptedOffset(cursor, pending)
            val context = assembleContext(
                title = matter.title,
                description = matter.description,
                tags = matter.tags,
                body = display,
                cursor = acceptedCursor,
                contextCharLimit = endpoint.let { llm.profile(resolved.profileId)?.contextLength ?: 8_192 },
            )
            val parser = IdeaStreamParser()
            val request = LlmRequest(
                model = endpoint.model,
                system = Prompts.system,
                user = Prompts.continueUser(context.meta, context.outline, context.before, context.after, extra, endpoint.jsonOutput),
                temperature = resolved.temperature,
                maxOutputTokens = llm.profile(resolved.profileId)?.maxOutput ?: 2048,
                stream = endpoint.stream,
                jsonObject = false,
            )
            clients.create(endpoint).stream(request).collect { event ->
                when (event) {
                    is LlmEvent.TextDelta -> {
                        val fresh = parser.push(event.text)
                        if (fresh.isNotEmpty()) _ui.update { it.copy(ideas = it.ideas + fresh) }
                    }
                    is LlmEvent.Failed -> _ui.update { it.copy(ideasError = event.error.message, ideasLoading = false) }
                    is LlmEvent.Done, is LlmEvent.Citation -> Unit
                }
            }
            _ui.update { it.copy(ideasLoading = false) }
        }
    }

    fun stopIdeas() {
        ideaJob?.cancel()
        _ui.update { it.copy(ideasLoading = false) }
    }

    fun insertSample(sample: String, cursor: Int) {
        if (pending != null) {
            publishUi(askDraft = true)
            return
        }
        val piece = PendingDrafts.prepareInsertion(display, cursor, sample)
        val (text, span) = PendingDrafts.insert(display, cursor, piece)
        display = text
        pending = span
        _commands.tryEmit(TextCommand(text, span.endExclusive))
        viewModelScope.launch {
            posts.savePending(path, span.start, text.substring(span.start, span.endExclusive))
            publishUi()
        }
    }

    fun dismissDraftPrompt() = publishUi(askDraft = false)

    fun checksFor(blog: Boolean, wechat: Boolean): List<ContentIssue> {
        val selection = narrowSelection(matter.publish, PublishSelection(blog, wechat))
        val document = PostDocument(path, matter, PendingDrafts.acceptedText(display, pending))
        return ContentRules.inspect(
            document,
            PublishTargets(selection.blog, selection.wechat),
            WechatHtmlLength(0, 0),
            rendererBound = false,
        )
    }

    fun buttonLabel(blog: Boolean, wechat: Boolean): String {
        val selection = narrowSelection(matter.publish, PublishSelection(blog, wechat))
        return publishButtonLabel(selection.blog, selection.wechat)
    }

    fun publish(blog: Boolean, wechat: Boolean, onDone: (String) -> Unit) {
        viewModelScope.launch {
            persist()
            val selection = narrowSelection(matter.publish, PublishSelection(blog, wechat))
            if (selection.blog || selection.wechat) {
                val moved = posts.moveToPosts(path)
                if (moved != path) {
                    path = moved
                    handle["path"] = encodePath(moved)
                }
            }
            val note = sync.sync(selection, setOf(path))
            reload()
            onDone(note)
        }
    }

    fun clearMessage() = publishUi(message = null)

    private suspend fun reload() {
        val document = posts.read(path)
        matter = document.frontMatter
        val saved = posts.pending(path)
        if (saved != null && saved.offset in 0..document.body.length) {
            val inserted = PendingDrafts.insert(document.body, saved.offset, saved.text)
            display = inserted.first
            pending = inserted.second
        } else {
            display = document.body
            pending = null
        }
        publishUi(loaded = true, savedLabel = "已打开")
        _commands.tryEmit(TextCommand(display, display.length))
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(1_000)
            persist()
            publishUi(savedLabel = "刚刚保存")
        }
    }

    private suspend fun persist() {
        posts.writeBody(path, PendingDrafts.acceptedText(display, pending))
        val span = pending
        if (span != null && span.endExclusive <= display.length) {
            posts.savePending(path, span.start, display.substring(span.start, span.endExclusive))
        }
    }

    private fun publishUi(
        loaded: Boolean = _ui.value.loaded,
        savedLabel: String = _ui.value.savedLabel,
        message: String? = _ui.value.message,
        askDraft: Boolean = _ui.value.askDraft,
    ) {
        _ui.update {
            it.copy(
                loaded = loaded,
                path = path,
                title = matter.title.ifBlank { "未命名" },
                body = display,
                matter = matter,
                pending = pending,
                wordCount = countWritingCharacters(PendingDrafts.acceptedText(display, pending)),
                savedLabel = savedLabel,
                message = message,
                askDraft = askDraft,
            )
        }
    }
}
