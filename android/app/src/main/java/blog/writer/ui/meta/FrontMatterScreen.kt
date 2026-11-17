package blog.writer.ui.meta
import blog.writer.copy.Copy

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
    val saved = MutableStateFlow(Copy["article_info"])
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
            saved.value = Copy["saved"]
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
            TextButton(onClick = onBack) { Text(Copy["back"]) }
            Text(note)
            Field(Copy["meta_title"], matter.title) { model.update(matter.copy(title = it)) }
            Field(Copy["meta_description"], matter.description) { model.update(matter.copy(description = it)) }
            Field(Copy["meta_pub_date"], matter.pubDate) { model.update(matter.copy(pubDate = it)) }
            Field(Copy["meta_updated"], matter.updatedDate.orEmpty()) { model.update(matter.copy(updatedDate = it.ifBlank { null })) }
            Field(Copy["meta_tags"], matter.tags.joinToString(", ")) {
                model.update(matter.copy(tags = it.split(',').map { tag -> tag.trim() }.filter { tag -> tag.isNotEmpty() }))
            }
            Field(Copy["meta_slug"], model.slug) { model.updateSlug(it) }
            Field(Copy["meta_cover"], matter.heroImage.orEmpty()) { model.update(matter.copy(heroImage = it.ifBlank { null })) }
            Field(Copy["meta_cover_alt"], matter.heroAlt.orEmpty()) { model.update(matter.copy(heroAlt = it.ifBlank { null })) }
            Toggle(Copy["draft"], matter.draft) { model.update(matter.copy(draft = it)) }
            Toggle(Copy["publish_blog"], matter.publish.blog) { model.update(matter.copy(publish = matter.publish.copy(blog = it))) }
            Toggle(Copy["publish_wechat"], matter.publish.wechat) { model.update(matter.copy(publish = PublishTargets(matter.publish.blog, it))) }
            Text(Copy["wechat"])
            Field(Copy["meta_wechat_title"], matter.wechat.title.orEmpty()) { model.update(matter.copy(wechat = matter.wechat.copy(title = it.ifBlank { null }))) }
            Field(Copy["meta_author"], matter.wechat.author.orEmpty()) { model.update(matter.copy(wechat = matter.wechat.copy(author = it.ifBlank { null }))) }
            Field(Copy["meta_digest"], matter.wechat.digest.orEmpty()) { model.update(matter.copy(wechat = matter.wechat.copy(digest = it.ifBlank { null }))) }
            Field(Copy["meta_wechat_cover"], matter.wechat.cover.orEmpty()) { model.update(matter.copy(wechat = matter.wechat.copy(cover = it.ifBlank { null }))) }
            Field(Copy["meta_source_url"], matter.wechat.sourceUrl.orEmpty()) { model.update(matter.copy(wechat = matter.wechat.copy(sourceUrl = it.ifBlank { null }))) }
            Toggle(Copy["meta_comments"], matter.wechat.openComment == true) {
                model.update(matter.copy(wechat = matter.wechat.copy(openComment = it)))
            }
            Button(onClick = { model.save(onBack) }, modifier = Modifier.fillMaxWidth()) { Text(Copy["save"]) }
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
