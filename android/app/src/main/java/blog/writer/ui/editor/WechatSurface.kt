package blog.writer.ui.editor
import blog.writer.copy.Copy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import blog.writer.content.PostFrontMatter

enum class ArticleSurface { Blog, Wechat }

@Composable
fun ArticleSurfaceBar(
    surface: ArticleSurface,
    onBlog: () -> Unit,
    onWechat: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onBlog) {
            Text(if (surface == ArticleSurface.Blog) Copy["mode_blog_current"] else Copy["blog"])
        }
        TextButton(onClick = onWechat) {
            Text(if (surface == ArticleSurface.Wechat) Copy["mode_wechat_current"] else Copy["wechat"])
        }
    }
}

@Composable
fun WechatDifferenceFields(
    matter: PostFrontMatter,
    onIncluded: (Boolean) -> Unit,
    onTitle: (String) -> Unit,
    onDigest: (String) -> Unit,
    onCover: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Copy["publish_wechat"], modifier = Modifier.weight(1f))
            Switch(checked = matter.publish.wechat, onCheckedChange = onIncluded)
        }
        OverrideField(
            label = Copy["meta_wechat_title"],
            value = matter.wechat.title ?: matter.title,
            followsBlog = matter.wechat.title.isNullOrBlank(),
            onChange = onTitle,
        )
        OverrideField(
            label = Copy["meta_digest"],
            value = matter.wechat.digest ?: matter.description,
            followsBlog = matter.wechat.digest.isNullOrBlank(),
            onChange = onDigest,
        )
        OverrideField(
            label = Copy["meta_wechat_cover"],
            value = matter.wechat.cover ?: matter.heroImage.orEmpty(),
            followsBlog = matter.wechat.cover.isNullOrBlank(),
            onChange = onCover,
        )
        Text(
            Copy["wechat_body_hint"],
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OverrideField(
    label: String,
    value: String,
    followsBlog: Boolean,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = {
            if (followsBlog) {
                Text(Copy["wechat_same_as_blog"])
            } else {
                TextButton(onClick = { onChange("") }) { Text(Copy["follow_default"]) }
            }
        },
    )
}
