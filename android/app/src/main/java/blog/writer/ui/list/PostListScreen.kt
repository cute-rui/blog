package blog.writer.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blog.writer.data.db.PostEntity
import blog.writer.data.repo.PostRepository
import blog.writer.data.repo.SyncCoordinator
import blog.writer.ui.theme.LocalWritingColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

private enum class PostFilter(val label: String) { All("全部"), Drafts("草稿"), Published("已发布"), Unsynced("未同步") }

@HiltViewModel
class PostListViewModel @Inject constructor(
    private val posts: PostRepository,
    private val sync: SyncCoordinator,
) : ViewModel() {
    val items = posts.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val syncState = sync.state

    init {
        viewModelScope.launch { posts.seedIfEmpty() }
    }

    fun create(onOpen: (String) -> Unit) {
        viewModelScope.launch { onOpen(posts.create()) }
    }

    fun refresh() {
        viewModelScope.launch { sync.sync() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostListScreen(
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    model: PostListViewModel = hiltViewModel(),
) {
    val posts by model.items.collectAsState()
    val syncing by model.syncState.collectAsState()
    var filter by remember { mutableStateOf(PostFilter.All) }
    val shown = posts.filter { post ->
        when (filter) {
            PostFilter.All -> true
            PostFilter.Drafts -> post.draft
            PostFilter.Published -> !post.draft
            PostFilter.Unsynced -> post.dirty
        }
    }
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { model.create(onOpen) },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) { Text("新文章", modifier = Modifier.padding(horizontal = 16.dp)) }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = syncing.syncing,
            onRefresh = model::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("文章", style = MaterialTheme.typography.headlineSmall)
                        Text("设置", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(onClick = onSettings).padding(8.dp))
                    }
                    Text(syncing.message, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                        PostFilter.entries.forEach { entry ->
                            FilterChip(selected = filter == entry, onClick = { filter = entry }, label = { Text(entry.label) })
                        }
                    }
                }
                if (shown.isEmpty()) {
                    item { Text("还没有文章。点右下角写一篇。", modifier = Modifier.padding(top = 32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(shown, key = { it.path }) { post ->
                    PostRow(post, onClick = { onOpen(post.path) })
                }
            }
        }
    }
}

@Composable
private fun PostRow(post: PostEntity, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (post.dirty) {
                Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall))
            }
            Text(post.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (post.description.isNotBlank()) {
            Text(post.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${relativeTime(post.updatedAtEpochMs)} · ${formatCount(post.wordCount)} 字", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (post.draft) SuggestionChip(onClick = {}, label = { Text("草稿") })
            if (!post.draft && post.publishBlog) SuggestionChip(onClick = {}, label = { Text("博客") })
            if (!post.draft && post.publishWechat) SuggestionChip(onClick = {}, label = { Text("公众号") })
            if (post.pending) {
                SuggestionChip(onClick = {}, label = { Text("✦ 待审", color = MaterialTheme.colorScheme.tertiary) })
            }
        }
    }
}

private fun formatCount(value: Int): String = "%,d".format(value)

private fun relativeTime(epochMs: Long): String {
    val then = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    val minutes = ChronoUnit.MINUTES.between(then, java.time.ZonedDateTime.now())
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        else -> "${minutes / (60 * 24)} 天前"
    }
}
