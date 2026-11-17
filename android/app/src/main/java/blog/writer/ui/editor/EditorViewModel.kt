package blog.writer.ui.editor
import blog.writer.copy.Copy

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
import blog.writer.content.applyWechatBody
import blog.writer.content.diffWechatBody
import blog.writer.content.countWritingCharacters
import blog.writer.content.narrowSelection
import blog.writer.content.publishButtonLabel
import blog.writer.ai.CheckedClaim
import blog.writer.ai.FactChecker
import blog.writer.ai.FillMiddle
import blog.writer.ai.LlmEndpoint
import blog.writer.ai.formatSearchResults
import blog.writer.data.files.LocalContentStore
import blog.writer.data.repo.AiHistoryRepository
import blog.writer.data.repo.PostRepository
import blog.writer.data.repo.LlmRepository
import blog.writer.data.repo.SearchRepository
import blog.writer.data.repo.SyncCoordinator
import blog.writer.data.repo.factSettings
import blog.writer.search.SearchRouter
import blog.writer.editor.MarkdownEdit
import blog.writer.editor.PendingDrafts
import blog.writer.editor.PendingSpan
import blog.writer.sync.ImageHost
import blog.writer.sync.articleCommitPaths
import blog.writer.sync.imageFileName
import blog.writer.sync.resolveArticleImage
import blog.writer.ui.nav.decodePath
import blog.writer.ui.nav.encodePath
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    val opening: String? = null,
    val ending: String? = null,
    val factClaims: List<CheckedClaim> = emptyList(),
    val factUnmatched: List<CheckedClaim> = emptyList(),
    val factRunning: Boolean = false,
    val fillRunning: Boolean = false,
    val checks: List<ContentIssue> = emptyList(),
    val publishLabel: String = Copy["publish_save_only"],
    val surface: ArticleSurface = ArticleSurface.Blog,
    val wechatStale: Int = 0,
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val posts: PostRepository,
    private val llm: LlmRepository,
    private val clients: LlmClients,
    private val sync: SyncCoordinator,
    private val search: SearchRepository,
    private val history: AiHistoryRepository,
    private val router: SearchRouter,
    private val images: ImageHost,
    private val files: LocalContentStore,
) : ViewModel() {
    private val handle = savedStateHandle
    private var path: String = decodePath(savedStateHandle.get<String>("path").orEmpty())
    private var display = ""
    private var pending: PendingSpan? = null
    private var matter = PostFrontMatter()
    private var sharedBody = ""
    private var surface = ArticleSurface.Blog
    private var saveJob: Job? = null
    private var ideaJob: Job? = null
    private var fillJob: Job? = null
    private var openingMark: String? = null
    private var endingMark: String? = null
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
        if (surface == ArticleSurface.Blog) sharedBody = PendingDrafts.acceptedText(display, pending)
        publishUi(savedLabel = Copy["saving"])
        scheduleSave()
    }

    fun showBlog() {
        if (surface == ArticleSurface.Blog) return
        if (pending != null) {
            publishUi(askDraft = true)
            return
        }
        captureWechatEdits()
        surface = ArticleSurface.Blog
        display = sharedBody
        publishUi(savedLabel = Copy["saving"])
        _commands.tryEmit(TextCommand(display, 0))
        scheduleSave()
    }

    fun showWechat() {
        if (surface == ArticleSurface.Wechat) return
        if (pending != null) {
            publishUi(askDraft = true)
            return
        }
        sharedBody = display
        surface = ArticleSurface.Wechat
        display = applyWechatBody(sharedBody, matter.wechat.body).text
        publishUi(savedLabel = Copy["saving"])
        _commands.tryEmit(TextCommand(display, 0))
        scheduleSave()
    }

    fun setPublishWechat(included: Boolean) {
        if (matter.publish.wechat == included) return
        matter = matter.copy(publish = matter.publish.copy(wechat = included))
        publishUi(savedLabel = Copy["saving"])
        scheduleSave()
    }

    fun setWechatTitle(value: String) {
        val next = value.takeIf { it.isNotBlank() && it != matter.title }
        if (next == matter.wechat.title) return
        matter = matter.copy(wechat = matter.wechat.copy(title = next))
        publishUi(savedLabel = Copy["saving"])
        scheduleSave()
    }

    fun setWechatDigest(value: String) {
        val next = value.takeIf { it.isNotBlank() && it != matter.description }
        if (next == matter.wechat.digest) return
        matter = matter.copy(wechat = matter.wechat.copy(digest = next))
        publishUi(savedLabel = Copy["saving"])
        scheduleSave()
    }

    fun insertImage(bytes: ByteArray, suggestedName: String?, cursor: Int, onDone: (String) -> Unit) {
        viewModelScope.launch {
            if (bytes.isEmpty()) {
                onDone(Copy["image_empty"])
                return@launch
            }
            if (imageFileName(suggestedName, bytes) == null) {
                onDone(Copy["image_type_unsupported"])
                return@launch
            }
            val stored = runCatching {
                withContext(Dispatchers.IO) { images.upload(path, suggestedName.orEmpty(), bytes) }
            }
            val markdownPath = stored.getOrElse {
                onDone(Copy["image_read_failed"])
                return@launch
            }
            val edit = MarkdownEdit.insertImage(display, cursor, markdownPath)
            pending = pending?.let { PendingDrafts.adjust(display, edit.text, it) }
            display = edit.text
            if (surface == ArticleSurface.Blog) sharedBody = PendingDrafts.acceptedText(display, pending)
            _commands.tryEmit(TextCommand(edit.text, edit.cursor))
            publishUi(savedLabel = Copy["saving"])
            scheduleSave()
            onDone(Copy["image_inserted"])
        }
    }

    fun readImage(reference: String): ByteArray? {
        val full = resolveArticleImage(path, reference) ?: return null
        return files.readBytes(full)
    }

    fun setWechatCover(value: String) {
        val next = value.takeIf { it.isNotBlank() && it != matter.heroImage }
        if (next == matter.wechat.cover) return
        matter = matter.copy(wechat = matter.wechat.copy(cover = next))
        publishUi(savedLabel = Copy["saving"])
        scheduleSave()
    }

    fun accept() {
        val span = pending ?: return
        undo = display to span
        pending = null
        viewModelScope.launch {
            posts.clearPending(path)
            persist()
            publishUi(message = Copy["accepted"])
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
            publishUi(message = Copy["discarded"])
        }
    }

    fun undoMessage() {
        val snap = undo ?: return
        undo = null
        display = snap.first
        pending = snap.second
        _commands.tryEmit(TextCommand(snap.first, snap.second.endExclusive))
        viewModelScope.launch {
            if (surface == ArticleSurface.Blog) {
                posts.savePending(path, snap.second.start, snap.first.substring(snap.second.start, snap.second.endExclusive))
            } else {
                posts.clearPending(path)
            }
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
            record("continue", resolved.profileId, resolved.model)
        }
    }

    fun markOpening(cursor: Int) = mark(cursor, opening = true)

    fun markEnding(cursor: Int) = mark(cursor, opening = false)

    fun clearMarks() {
        openingMark = null
        endingMark = null
        viewModelScope.launch { history.saveMarks(path, null, null) }
        publishUi()
    }

    fun startFill(extra: String) {
        if (pending != null) {
            publishUi(askDraft = true)
            return
        }
        val target = FillMiddle.target(display, openingMark, endingMark)
        if (target == null) {
            publishUi(message = Copy["action_fill_need"])
            return
        }
        fillJob?.cancel()
        fillJob = viewModelScope.launch {
            val resolved = llm.observeAssignments().first().resolveFill()
            val endpoint = resolved?.let { llm.endpoint(it.profileId, it.model) }
            if (resolved == null || endpoint == null) {
                _ui.update { it.copy(needProfile = true, fillRunning = false) }
                return@launch
            }
            val words = llm.observeAssignments().first().fillTargetWords
            val opening = display.substring(target.opening.start, target.opening.endExclusive)
            val ending = display.substring(target.ending.start, target.ending.endExclusive)
            val context = assembleContext(matter.title, matter.description, matter.tags, display, target.insertAt)
            val (seed, span) = PendingDrafts.insert(display, target.insertAt, "\n\n")
            display = seed
            pending = span
            _commands.tryEmit(TextCommand(seed, span.endExclusive))
            _ui.update { it.copy(fillRunning = true, ideasError = null) }
            val request = LlmRequest(
                model = endpoint.model,
                system = Prompts.system,
                user = Prompts.fillUser(context.meta, context.outline, opening, ending, words, extra),
                temperature = resolved.temperature,
                maxOutputTokens = llm.profile(resolved.profileId)?.maxOutput ?: 2048,
                stream = endpoint.stream,
                jsonObject = false,
            )
            streamIntoPending(endpoint, request)
            _ui.update { it.copy(fillRunning = false) }
            record("fill", resolved.profileId, resolved.model)
        }
    }

    fun stopFill() {
        fillJob?.cancel()
        _ui.update { it.copy(fillRunning = false) }
    }

    fun startFactCheck(selectionStart: Int, selectionEnd: Int) {
        if (pending != null) {
            publishUi(askDraft = true)
            return
        }
        viewModelScope.launch {
            val resolved = llm.observeAssignments().first().resolveFactCheck()
            val endpoint = resolved?.let { llm.endpoint(it.profileId, it.model) }
            if (resolved == null || endpoint == null) {
                _ui.update { it.copy(needProfile = true, factRunning = false) }
                return@launch
            }
            val source = if (selectionStart == selectionEnd) {
                FillMiddle.paragraphAt(display, selectionStart)
            } else {
                display.substring(selectionStart.coerceAtMost(selectionEnd), selectionEnd.coerceAtLeast(selectionStart))
            }
            _ui.update { it.copy(factRunning = true, factClaims = emptyList(), factUnmatched = emptyList()) }
            val assignments = llm.observeAssignments().first()
            val checker = FactChecker(
                router = router,
                extract = { title, text ->
                    complete(endpoint, LlmRequest(endpoint.model, Prompts.system, Prompts.extractClaims(title, text, endpoint.jsonOutput), 0.1, 1024, endpoint.stream, false))
                },
                judge = { claim, results ->
                    complete(
                        endpoint,
                        LlmRequest(
                            endpoint.model,
                            Prompts.system,
                            Prompts.judgeClaim(claim, formatSearchResults(results), endpoint.jsonOutput),
                            0.1,
                            512,
                            endpoint.stream,
                            false,
                        ),
                    )
                },
            )
            val report = checker.check(matter.title, source, search.loadForFactCheck(assignments), factSettings(assignments))
            _ui.update {
                it.copy(
                    factRunning = false,
                    factClaims = report.checked,
                    factUnmatched = report.unmatched,
                )
            }
            record("fact-check", resolved.profileId, resolved.model)
        }
    }

    fun insertFix(fix: String, cursor: Int) = insertSample(fix, cursor)

    fun appendCitation(url: String, title: String) {
        val footnote = "\n\n[${title}]($url)\n"
        val text = display + footnote
        display = text
        _commands.tryEmit(TextCommand(text, text.length))
        scheduleSave()
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
            if (surface == ArticleSurface.Blog) {
                posts.savePending(path, span.start, text.substring(span.start, span.endExclusive))
            }
            publishUi()
        }
    }

    fun dismissDraftPrompt() = publishUi(askDraft = false)

    fun checksFor(blog: Boolean, wechat: Boolean): List<ContentIssue> {
        val selection = narrowSelection(matter.publish, PublishSelection(blog, wechat))
        val document = PostDocument(path, matter, sharedBody)
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
            val note = sync.sync(selection, articleCommitPaths(path, posts.localFiles().map { it.path }))
            reload()
            onDone(note)
        }
    }

    fun clearMessage() = publishUi(message = null)

    private suspend fun reload() {
        val document = posts.read(path)
        matter = document.frontMatter
        sharedBody = document.body
        val saved = posts.pending(path)
        if (saved != null && saved.offset in 0..document.body.length) {
            surface = ArticleSurface.Blog
            val inserted = PendingDrafts.insert(document.body, saved.offset, saved.text)
            display = inserted.first
            pending = inserted.second
        } else if (surface == ArticleSurface.Wechat) {
            display = applyWechatBody(sharedBody, matter.wechat.body).text
            pending = null
        } else {
            display = sharedBody
            pending = null
        }
        val marks = history.marks(path)
        openingMark = marks?.opening
        endingMark = marks?.ending
        publishUi(loaded = true, savedLabel = Copy["opened"])
        _commands.tryEmit(TextCommand(display, display.length))
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(1_000)
            persist()
            publishUi(savedLabel = Copy["just_saved"])
        }
    }

    private suspend fun persist() {
        if (surface == ArticleSurface.Wechat) {
            captureWechatEdits()
        } else {
            sharedBody = PendingDrafts.acceptedText(display, pending)
        }
        posts.writeArticle(path, matter, sharedBody)
        val span = pending
        if (surface == ArticleSurface.Blog && span != null && span.endExclusive <= display.length) {
            posts.savePending(path, span.start, display.substring(span.start, span.endExclusive))
        }
    }

    private fun captureWechatEdits() {
        val accepted = PendingDrafts.acceptedText(display, pending)
        val applied = applyWechatBody(sharedBody, matter.wechat.body)
        if (accepted != applied.text) {
            matter = matter.copy(wechat = matter.wechat.copy(body = diffWechatBody(sharedBody, accepted)))
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
                title = matter.title.ifBlank { Copy["untitled"] },
                body = display,
                matter = matter,
                pending = pending,
                wordCount = countWritingCharacters(PendingDrafts.acceptedText(display, pending)),
                savedLabel = savedLabel,
                message = message,
                askDraft = askDraft,
                opening = openingMark,
                ending = endingMark,
                surface = surface,
                wechatStale = applyWechatBody(sharedBody, matter.wechat.body).stale.size,
            )
        }
    }

    private fun mark(cursor: Int, opening: Boolean) {
        val paragraph = FillMiddle.paragraphAt(display, cursor)
        if (paragraph.isBlank()) return
        if (opening) openingMark = paragraph else endingMark = paragraph
        viewModelScope.launch { history.saveMarks(path, openingMark, endingMark) }
        publishUi()
    }

    private suspend fun streamIntoPending(endpoint: LlmEndpoint, request: LlmRequest) {
        clients.create(endpoint).stream(request).collect { event ->
            when (event) {
                is LlmEvent.TextDelta -> appendPending(event.text)
                is LlmEvent.Failed -> _ui.update { it.copy(ideasError = event.error.message) }
                is LlmEvent.Done, is LlmEvent.Citation -> Unit
            }
        }
    }

    private fun appendPending(delta: String) {
        val span = pending ?: return
        if (delta.isEmpty() || span.endExclusive > display.length) return
        display = display.substring(0, span.endExclusive) + delta + display.substring(span.endExclusive)
        pending = span.copy(endExclusive = span.endExclusive + delta.length)
        _commands.tryEmit(TextCommand(display, pending?.endExclusive ?: display.length))
        publishUi()
    }

    private suspend fun complete(endpoint: LlmEndpoint, request: LlmRequest): String {
        val text = StringBuilder()
        clients.create(endpoint).stream(request).collect { event ->
            when (event) {
                is LlmEvent.TextDelta -> text.append(event.text)
                is LlmEvent.Failed -> error(event.error.message)
                is LlmEvent.Done, is LlmEvent.Citation -> Unit
            }
        }
        return text.toString()
    }

    private suspend fun record(action: String, profileId: String, model: String) {
        history.record(action, profileId, model)
    }
}
