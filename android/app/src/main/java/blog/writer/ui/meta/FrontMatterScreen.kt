package blog.writer.ui.meta

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blog.writer.content.PostFrontMatter
import blog.writer.content.PublishTargets
import blog.writer.data.repo.PostRepository
import blog.writer.ui.nav.decodePath
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FrontMatterViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val posts: PostRepository,
) : ViewModel() {
    private val original = decodePath(savedStateHandle.get<String>("path").orEmpty())
    val saved = MutableStateFlow("文章信息")
    var matter by mutableStateOf(PostFrontMatter())
        private set
    var slug by mutableStateOf("")
        private set

    init {
        viewModelScope.launch {
            val document = posts.read(original)
            matter = document.frontMatter
            slug = document.slug
        }
    }

    fun update(next: PostFrontMatter) {
        matter = next
    }

    fun updateSlug(value: String) {
        slug = value
    }

    fun save(onDone: () -> Unit) {
        viewModelScope.launch {
            posts.writeMatter(original, matter, slug)
            saved.value = "已保存"
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrontMatterScreen(
    onBack: () -> Unit,
    model: FrontMatterViewModel = hiltViewModel(),
) {
    val matter = model.matter
    val note by model.saved.collectAsState()
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(note)
            Field("标题", matter.title) { model.update(matter.copy(title = it)) }
            Field("描述", matter.description) { model.update(matter.copy(description = it)) }
            Field("发布日期", matter.pubDate) { model.update(matter.copy(pubDate = it)) }
            Field("更新日期", matter.updatedDate.orEmpty()) { model.update(matter.copy(updatedDate = it.ifBlank { null })) }
            Field("标签", matter.tags.joinToString(", ")) {
                model.update(matter.copy(tags = it.split(',').map { tag -> tag.trim() }.filter { tag -> tag.isNotEmpty() }))
            }
            Field("路径名", model.slug) { model.updateSlug(it) }
            Field("封面", matter.heroImage.orEmpty()) { model.update(matter.copy(heroImage = it.ifBlank { null })) }
            Field("封面说明", matter.heroAlt.orEmpty()) { model.update(matter.copy(heroAlt = it.ifBlank { null })) }
            Toggle("草稿", matter.draft) { model.update(matter.copy(draft = it)) }
            Toggle("发布到博客", matter.publish.blog) { model.update(matter.copy(publish = matter.publish.copy(blog = it))) }
            Toggle("生成公众号版本", matter.publish.wechat) { model.update(matter.copy(publish = PublishTargets(matter.publish.blog, it))) }
            Text("公众号")
            Field("公众号标题", matter.wechat.title.orEmpty()) { model.update(matter.copy(wechat = matter.wechat.copy(title = it.ifBlank { null }))) }
            Field("作者", matter.wechat.author.orEmpty()) { model.update(matter.copy(wechat = matter.wechat.copy(author = it.ifBlank { null }))) }
            Field("摘要", matter.wechat.digest.orEmpty()) { model.update(matter.copy(wechat = matter.wechat.copy(digest = it.ifBlank { null }))) }
            Field("公众号封面", matter.wechat.cover.orEmpty()) { model.update(matter.copy(wechat = matter.wechat.copy(cover = it.ifBlank { null }))) }
            Field("阅读原文", matter.wechat.sourceUrl.orEmpty()) { model.update(matter.copy(wechat = matter.wechat.copy(sourceUrl = it.ifBlank { null }))) }
            Toggle("开启评论", matter.wechat.openComment == true) {
                model.update(matter.copy(wechat = matter.wechat.copy(openComment = it)))
            }
            Button(onClick = { model.save(onBack) }, modifier = Modifier.fillMaxWidth()) { Text("保存") }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
