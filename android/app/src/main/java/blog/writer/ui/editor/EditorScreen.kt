package blog.writer.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import blog.writer.ai.CheckedClaim
import blog.writer.ai.ContinueIdea
import blog.writer.ai.FactCheckRules
import blog.writer.ai.Verdict
import blog.writer.content.ContentIssue
import blog.writer.content.IssueSeverity
import blog.writer.data.repo.Appearance
import blog.writer.editor.MarkdownEdit
import blog.writer.editor.MarkdownKind
import blog.writer.editor.MarkdownSyntax
import blog.writer.ui.theme.EditorText
import blog.writer.ui.theme.LocalWritingColors
import blog.writer.ui.theme.Mono
import blog.writer.ui.theme.SerifSc
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

private data object AssistKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    appearance: Appearance,
    onBack: () -> Unit,
    onInfo: (String) -> Unit,
    onPreview: (String) -> Unit,
    onSettings: () -> Unit,
    model: EditorViewModel = hiltViewModel(),
) {
    val ui by model.ui.collectAsState()
    val sync by model.syncState.collectAsState()
    val state = rememberTextFieldState("")
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    var ideas by remember { mutableStateOf(false) }
    var fact by remember { mutableStateOf(false) }
    var publish by remember { mutableStateOf(false) }
    var extra by remember { mutableStateOf("") }
    val toolbar = remember {
        object : TextToolbar {
            private var current by mutableStateOf(TextToolbarStatus.Hidden)
            override val status: TextToolbarStatus
                get() = current
            override fun hide() {
                current = TextToolbarStatus.Hidden
            }
            override fun showMenu(
                rect: androidx.compose.ui.geometry.Rect,
                onCopyRequested: (() -> Unit)?,
                onPasteRequested: (() -> Unit)?,
                onCutRequested: (() -> Unit)?,
                onSelectAllRequested: (() -> Unit)?,
            ) {
                current = TextToolbarStatus.Shown
                menu = true
                if (appearance.haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                hide()
            }
        }
    }
    LaunchedEffect(model) {
        model.commands.collect { command ->
            state.edit {
                replace(0, length, command.text)
                selection = TextRange(command.cursor.coerceIn(0, command.text.length))
            }
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.drop(1).collect { model.onDisplayText(it) }
    }
    LaunchedEffect(ui.message) {
        val message = ui.message ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(message, actionLabel = if (message == "已接受" || message == "已丢弃") "撤销" else null)
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) model.undoMessage()
        model.clearMessage()
    }
    if (ui.askDraft) {
        AlertDialog(
            onDismissRequest = model::dismissDraftPrompt,
            title = { Text("先处理当前草稿？") },
            text = { Text("还有一段 AI 草稿待审。") },
            confirmButton = { TextButton(onClick = { model.accept(); model.dismissDraftPrompt() }) { Text("接受") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { model.reject(); model.dismissDraftPrompt() }) { Text("丢弃") }
                    TextButton(onClick = model::dismissDraftPrompt) { Text("取消") }
                }
            },
        )
    }
    CompositionLocalProvider(LocalTextToolbar provides toolbar) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                EditorTopBar(
                    path = ui.path.ifBlank { "草稿" },
                    syncLabel = if (sync.syncing) "同步中…" else if (ui.pending != null) "1 处待审" else sync.message,
                    pending = ui.pending != null,
                    onBack = onBack,
                    onPreview = { onPreview(ui.path) },
                    onPublish = { publish = true },
                )
            },
            bottomBar = {
                Column(Modifier.imePadding()) {
                    if (ui.pending != null) {
                        ReviewBar(
                            words = ui.pending?.length ?: 0,
                            generating = ui.fillRunning,
                            onStop = model::stopFill,
                            onReject = {
                                if (appearance.haptics) haptic.performHapticFeedback(HapticFeedbackType.Reject)
                                model.reject()
                            },
                            onAccept = {
                                if (appearance.haptics) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                model.accept()
                            },
                        )
                    }
                    MarkdownBar(
                        count = ui.wordCount,
                        onMark = { marker ->
                            val edit = MarkdownEdit.wrapSelection(state.text.toString(), state.selection.start, state.selection.end, marker)
                            state.edit {
                                replace(0, length, edit.text)
                                selection = TextRange(edit.cursor)
                            }
                        },
                        onPrefix = { prefix ->
                            val edit = MarkdownEdit.prefixLine(state.text.toString(), state.selection.start, prefix)
                            state.edit {
                                replace(0, length, edit.text)
                                selection = TextRange(edit.cursor)
                            }
                        },
                        onLink = {
                            val edit = MarkdownEdit.insertLink(state.text.toString(), state.selection.start, state.selection.end)
                            state.edit {
                                replace(0, length, edit.text)
                                selection = TextRange(edit.cursor)
                            }
                        },
                        onImage = {
                            val edit = MarkdownEdit.insertImage(state.text.toString(), state.selection.start, "./")
                            state.edit {
                                replace(0, length, edit.text)
                                selection = TextRange(edit.cursor)
                            }
                        },
                        onAssist = { menu = true },
                    )
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier
                        .widthIn(max = 640.dp)
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                ) {
                    if (ui.opening != null || ui.ending != null) {
                        Text(
                            "开头 ${if (ui.opening != null) "已标" else "未标"} · 结尾 ${if (ui.ending != null) "已标" else "未标"}",
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text(
                        "${if (ui.matter.draft) "草稿" else "文章"} · ${ui.wordCount} 字 · ${ui.savedLabel}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    val colors = MaterialTheme.colorScheme
                    val textStyle = EditorText.body(appearance.fontSizeSp, appearance.serif).copy(color = colors.onSurface)
                    BasicTextField(
                        state = state,
                        modifier = Modifier
                            .fillMaxSize()
                            .appendAssistMenu { menu = true },
                        textStyle = textStyle,
                        cursorBrush = SolidColor(colors.primary),
                        lineLimits = TextFieldLineLimits.MultiLine(),
                        outputTransformation = OutputTransformation {
                            val value = asCharSequence().toString()
                            MarkdownSyntax.spans(value).forEach { span ->
                                val style = when (span.kind) {
                                    MarkdownKind.HeadingMark, MarkdownKind.Code, MarkdownKind.LinkMark ->
                                        SpanStyle(color = colors.outline, fontFamily = Mono, fontSize = 13.sp)
                                    MarkdownKind.Heading -> SpanStyle(fontWeight = FontWeight.SemiBold, fontFamily = SerifSc)
                                    MarkdownKind.Bold -> SpanStyle(fontWeight = FontWeight.SemiBold)
                                    MarkdownKind.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
                                    MarkdownKind.LinkText -> SpanStyle(color = colors.primary)
                                }
                                addStyle(style, span.start, span.end)
                            }
                            ui.pending?.let { span ->
                                if (span.endExclusive <= value.length) {
                                    addStyle(
                                        SpanStyle(background = colors.tertiaryContainer, color = colors.onTertiaryContainer),
                                        span.start,
                                        span.endExclusive,
                                    )
                                }
                            }
                        },
                    )
                }
                if (menu) {
                    Box(Modifier.fillMaxSize().background(colorsOverlay()).clickable { menu = false })
                    AiMenu(
                        extra = extra,
                        onExtra = { extra = it },
                        recommendFact = state.selection.start != state.selection.end,
                        fillReady = ui.opening != null && ui.ending != null || state.text.contains("<!-- fill -->"),
                        onContinue = {
                            menu = false
                            ideas = true
                            model.startIdeas(state.selection.start, extra)
                        },
                        onFact = {
                            menu = false
                            fact = true
                            model.startFactCheck(state.selection.start, state.selection.end)
                        },
                        onFill = {
                            menu = false
                            model.startFill(extra)
                        },
                        onOpening = { model.markOpening(state.selection.start); menu = false },
                        onEnding = { model.markEnding(state.selection.start); menu = false },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    )
                }
            }
        }
    }
    if (fact) {
        ModalBottomSheet(onDismissRequest = { fact = false }) {
            FactSheet(
                claims = ui.factClaims,
                unmatched = ui.factUnmatched,
                running = ui.factRunning,
                needProfile = ui.needProfile,
                onInsert = { fix ->
                    model.insertFix(fix, state.selection.start)
                    fact = false
                },
                onCite = { title, url -> model.appendCitation(url, title) },
                onSettings = { fact = false; onSettings() },
            )
        }
    }
    if (ideas) {
        ModalBottomSheet(onDismissRequest = { ideas = false; model.stopIdeas() }) {
            ContinueSheet(
                ideas = ui.ideas,
                loading = ui.ideasLoading,
                error = ui.ideasError,
                needProfile = ui.needProfile,
                onInsert = { sample ->
                    model.insertSample(sample, state.selection.start)
                    ideas = false
                },
                onCopy = {
                    scope.launch { snackbar.showSnackbar("已复制方向") }
                },
                onRetry = { model.startIdeas(state.selection.start, extra) },
                onSettings = { ideas = false; onSettings() },
            )
        }
    }
    if (publish) {
        PublishSheet(
            title = ui.title,
            defaultsBlog = ui.matter.publish.blog,
            defaultsWechat = ui.matter.publish.wechat,
            pending = ui.pending != null,
            checks = { blog, wechat -> model.checksFor(blog, wechat) },
            label = { blog, wechat -> model.buttonLabel(blog, wechat) },
            onDismiss = { publish = false },
            onInfo = { publish = false; onInfo(ui.path) },
            onPublish = { blog, wechat ->
                model.publish(blog, wechat) { note ->
                    scope.launch {
                        if (appearance.haptics) {
                            val type = if (note.contains("失败") || note.contains("冲突")) HapticFeedbackType.Reject else HapticFeedbackType.Confirm
                            haptic.performHapticFeedback(type)
                        }
                        snackbar.showSnackbar(note)
                    }
                    publish = false
                }
            },
        )
    }
}

@Composable
private fun colorsOverlay() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

@Composable
private fun EditorTopBar(
    path: String,
    syncLabel: String,
    pending: Boolean,
    onBack: () -> Unit,
    onPreview: () -> Unit,
    onPublish: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) { Text("返回") }
        Text(
            path.substringAfterLast('/').ifBlank { path },
            modifier = Modifier.weight(1f),
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            syncLabel,
            color = if (pending) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        TextButton(onClick = onPreview) { Text("预览") }
        FilledTonalButton(onClick = onPublish, modifier = Modifier.padding(start = 4.dp)) { Text("发布") }
    }
}

@Composable
private fun MarkdownBar(
    count: Int,
    onMark: (String) -> Unit,
    onPrefix: (String) -> Unit,
    onLink: () -> Unit,
    onImage: () -> Unit,
    onAssist: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf("H" to "# ", "B" to "**", "I" to "*").forEach { (label, marker) ->
            TextButton(onClick = { if (label == "H") onPrefix("# ") else onMark(marker) }) { Text(label) }
        }
        TextButton(onClick = onLink) { Text("链接") }
        TextButton(onClick = { onPrefix("> ") }) { Text("引用") }
        TextButton(onClick = onImage) { Text("图片") }
        Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Surface(
            onClick = onAssist,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.height(44.dp),
        ) {
            Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Text("✦", color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun AiMenu(
    extra: String,
    onExtra: (String) -> Unit,
    recommendFact: Boolean,
    fillReady: Boolean,
    onContinue: () -> Unit,
    onFact: () -> Unit,
    onFill: () -> Unit,
    onOpening: () -> Unit,
    onEnding: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 296.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("✦ 写作助手", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelSmall)
                Text("光标处", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ActionRow("给我思路", "3 个方向，各附一段示范，由你挑", recommended = !recommendFact && !fillReady, enabled = true, onClick = onContinue)
            ActionRow(
                "事实核查",
                if (recommendFact) "逐条核对陈述并附来源 · 选区" else "逐条核对陈述并附来源 · 本段",
                recommended = recommendFact,
                enabled = true,
                onClick = onFact,
            )
            ActionRow(
                "补全中间",
                if (fillReady) "用你的开头和结尾补出中间" else "需先标出开头和结尾，或在空行写 <!-- fill -->",
                recommended = fillReady,
                enabled = fillReady,
                onClick = { if (fillReady) onFill() else onFill() },
            )
            OutlinedTextField(
                value = extra,
                onValueChange = onExtra,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("补充要求（可选）") },
                singleLine = true,
            )
            Row {
                TextButton(onClick = onOpening) { Text("设为开头") }
                TextButton(onClick = onEnding) { Text("设为结尾") }
            }
        }
    }
}

@Composable
private fun ActionRow(title: String, detail: String, recommended: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .background(if (recommended) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("✦", color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 2.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ContinueSheet(
    ideas: List<ContinueIdea>,
    loading: Boolean,
    error: String?,
    needProfile: Boolean,
    onInsert: (String) -> Unit,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxWidth().heightIn(min = 280.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("继续写的 3 个方向", style = MaterialTheme.typography.titleMedium)
        Text("基于前后文与大纲 · 插入前不会改动正文", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (needProfile) {
            Text("还没有设置模型")
            Button(onClick = onSettings) { Text("去设置") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        ideas.forEachIndexed { index, idea ->
            Surface(shape = MaterialTheme.shapes.large, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("方向 ${index + 1}", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelSmall)
                    Text(idea.direction, style = MaterialTheme.typography.titleSmall)
                    Text(
                        idea.sample,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SerifSc),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(idea.direction))
                            onCopy()
                        }) { Text("复制方向") }
                        FilledTonalButton(onClick = { onInsert(idea.sample) }) { Text("插入示范段") }
                    }
                }
            }
        }
        if (loading && ideas.size < 3) {
            repeat(3 - ideas.size) {
                Box(Modifier.fillMaxWidth().height(88.dp).background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.large))
            }
        }
        if (!needProfile) TextButton(onClick = onRetry) { Text("换一批") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ReviewBar(words: Int, generating: Boolean, onStop: () -> Unit, onReject: () -> Unit, onAccept: () -> Unit) {
    Surface(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().height(64.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 8.dp,
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (generating) "生成中…" else "$words 字", style = MaterialTheme.typography.labelMedium)
            Row {
                if (generating) TextButton(onClick = onStop) { Text("停止") }
                TextButton(onClick = onReject) { Text("丢弃") }
                Button(
                    onClick = onAccept,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary),
                ) { Text("接受") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PublishSheet(
    title: String,
    defaultsBlog: Boolean,
    defaultsWechat: Boolean,
    pending: Boolean,
    checks: (Boolean, Boolean) -> List<ContentIssue>,
    label: (Boolean, Boolean) -> String,
    onDismiss: () -> Unit,
    onInfo: () -> Unit,
    onPublish: (Boolean, Boolean) -> Unit,
) {
    var blog by remember { mutableStateOf(defaultsBlog) }
    var wechat by remember { mutableStateOf(defaultsWechat) }
    val issues = checks(blog, wechat)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("发布这篇文章", style = MaterialTheme.typography.titleMedium)
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TargetCard("博客", "生成网站上的文章", blog, defaultsBlog, enabled = defaultsBlog) { blog = it }
            TargetCard("微信公众号", "生成公众号版本，之后由你复制到公众号后台群发", wechat, defaultsWechat, enabled = defaultsWechat) { wechat = it }
            issues.forEach { issue ->
                val color = when (issue.severity) {
                    IssueSeverity.Fail -> MaterialTheme.colorScheme.error
                    IssueSeverity.Warn -> MaterialTheme.colorScheme.error
                    IssueSeverity.Note -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(issue.message, color = color, style = MaterialTheme.typography.labelMedium)
            }
            if (pending) {
                Text("有 1 处 AI 草稿待审，发布内容不包含它", color = MaterialTheme.colorScheme.tertiary)
            }
            TextButton(onClick = onInfo) { Text("文章信息") }
            Button(onClick = { onPublish(blog, wechat) }, modifier = Modifier.fillMaxWidth()) {
                Text(label(blog, wechat))
            }
        }
    }
}

@Composable
private fun TargetCard(title: String, detail: String, selected: Boolean, isDefault: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        onClick = { if (enabled) onChange(!selected) },
        shape = MaterialTheme.shapes.large,
        border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (isDefault) Text("默认", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FactSheet(
    claims: List<CheckedClaim>,
    unmatched: List<CheckedClaim>,
    running: Boolean,
    needProfile: Boolean,
    onInsert: (String) -> Unit,
    onCite: (String, String) -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 240.dp).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("事实核查", style = MaterialTheme.typography.titleMedium)
        if (running) Text("正在找出可核查的陈述…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (needProfile) {
            Text("还没有设置模型")
            Button(onClick = onSettings) { Text("去设置") }
        }
        if (!running && claims.isEmpty() && unmatched.isEmpty() && !needProfile) {
            Text("这一段没有可以核实的事实性陈述")
        }
        claims.forEach { claim -> ClaimCard(claim, onInsert, onCite) }
        unmatched.forEach { claim ->
            Text("未对上原文：${claim.claim}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ClaimCard(
    claim: CheckedClaim,
    onInsert: (String) -> Unit,
    onCite: (String, String) -> Unit,
) {
    val color = when (claim.verdict) {
        Verdict.Supported -> LocalWritingColors.current.supported
        Verdict.Wrong -> MaterialTheme.colorScheme.error
        Verdict.Uncertain, Verdict.Unverifiable -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(claim.label, color = color, style = MaterialTheme.typography.labelLarge)
            if (claim.claim.isNotBlank()) {
                Text("「${claim.claim}」", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SerifSc))
            }
            if (claim.reason.isNotBlank()) Text(claim.reason, style = MaterialTheme.typography.bodySmall)
            if (claim.label == FactCheckRules.RETRIEVAL_FAILED) {
                Text("没有用来源记忆做判定。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            claim.results.forEach { result ->
                Text("${result.rank}. ${result.title}", style = MaterialTheme.typography.labelMedium)
                Text(result.url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { onCite(result.title, result.url) })
            }
            claim.fix?.let { fix ->
                TextButton(onClick = { onInsert(fix) }) { Text("插入修正") }
            }
        }
    }
}

private fun Modifier.appendAssistMenu(onAssist: () -> Unit): Modifier =
    this.then(
        appendTextContextMenuComponents {
            item(AssistKey, "✦ 写作助手") {
                close()
                onAssist()
            }
        },
    )
