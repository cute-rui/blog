package blog.writer.ui.editor
import blog.writer.copy.Copy

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PickedImage(val name: String?, val bytes: ByteArray)

fun readPickedImage(resolver: ContentResolver, uri: Uri): PickedImage? {
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    return PickedImage(name, bytes)
}

@Composable
fun ArticleImageStrip(references: List<String>, load: (String) -> ByteArray?) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).height(88.dp).padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        references.forEach { reference ->
            val bitmap by produceState<ImageBitmap?>(initialValue = null, reference) {
                value = withContext(Dispatchers.IO) {
                    load(reference)?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                }
            }
            val image = bitmap
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = Copy["image"],
                    modifier = Modifier.height(80.dp).widthIn(max = 140.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(reference, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
