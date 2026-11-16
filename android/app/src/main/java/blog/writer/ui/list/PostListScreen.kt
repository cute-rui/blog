package blog.writer.ui.list
import blog.writer.copy.Copy

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

private enum class PostFilter { All, Drafts, Published, Unsynced }

private fun PostFilter.label(): String = when (this) {
    PostFilter.All -> blog.writer.copy.Copy["filter_all"]
    PostFilter.Drafts -> blog.writer.copy.Copy["draft"]
    PostFilter.Published -> blog.writer.copy.Copy["filter_published"]
    PostFilter.Unsynced -> blog.writer.copy.Copy["filter_unsynced"]
}

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
            ) { Text(Copy["new_post"], modifier = Modifier.padding(horizontal = 16.dp)) }
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
                        Text(Copy["article"], style = MaterialTheme.typography.headlineSmall)
                        Text(Copy["settings"], color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(onClick = onSettings).padding(8.dp))
                    }
                    Text(syncing.message, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                        PostFilter.entries.forEach { entry ->
                            FilterChip(selected = filter == entry, onClick = { filter = entry }, label = { Text(entry.label()) })
                        }
                    }
                }
                if (shown.isEmpty()) {
                    item { Text(Copy["empty_posts"], modifier = Modifier.padding(top = 32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
            Text(Copy.format("list_meta", "time" to relativeTime(post.updatedAtEpochMs), "count" to formatCount(post.wordCount)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (post.draft) SuggestionChip(onClick = {}, label = { Text(Copy["draft"]) })
            if (!post.draft && post.publishBlog) SuggestionChip(onClick = {}, label = { Text(Copy["blog"]) })
            if (!post.draft && post.publishWechat) SuggestionChip(onClick = {}, label = { Text(Copy["wechat"]) })
            if (post.pending) {
                SuggestionChip(onClick = {}, label = { Text(Copy["chip_pending"], color = MaterialTheme.colorScheme.tertiary) })
            }
        }
    }
}

private fun formatCount(value: Int): String = "%,d".format(value)

private fun relativeTime(epochMs: Long): String {
    val then = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    val minutes = ChronoUnit.MINUTES.between(then, java.time.ZonedDateTime.now())
    return when {
        minutes < 1 -> Copy["just_now"]
        minutes < 60 -> Copy.format("minutes_ago", "count" to minutes)
        minutes < 60 * 24 -> Copy.format("hours_ago", "count" to minutes / 60)
        else -> Copy.format("days_ago", "count" to minutes / (60 * 24))
    }
}
