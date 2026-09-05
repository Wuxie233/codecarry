package dev.wuxie233.codecarry.ui.screens.codex

import android.util.Base64
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.data.codex.CodexThreadItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class CodexTimelineImage(val type: String, val source: String?)

internal fun CodexThreadItem.timelineImages(): List<CodexTimelineImage> =
    (raw["content"] as? JsonArray).orEmpty().mapNotNull { block ->
        val value = block as? JsonObject ?: return@mapNotNull null
        val type = (value["type"] as? JsonPrimitive)?.contentOrNull
        when (type) {
            "image" -> CodexTimelineImage(type, (value["url"] as? JsonPrimitive)?.contentOrNull)
            "localImage" -> CodexTimelineImage(type, (value["path"] as? JsonPrimitive)?.contentOrNull)
            else -> null
        }
    }

/** Paths belong to the selected daemon; they are never interpreted as Android files. */
@Composable
internal fun CodexTimelineImages(item: CodexThreadItem, loadRemoteImage: suspend (String) -> ByteArray) {
    val currentImageLoader by rememberUpdatedState(loadRemoteImage)
    val images = remember(item.raw) { item.timelineImages() }
    images.forEachIndexed { index, image ->
        var attempt by remember(image) { mutableStateOf(0) }
        val loaded by produceState<Result<Any>?>(null, image, attempt) {
            value = null
            value = try {
                Result.success(withContext(Dispatchers.IO) {
                    val source = requireNotNull(image.source?.takeIf { it.isNotBlank() })
                    when {
                        image.type == "localImage" -> currentImageLoader(source)
                        source.startsWith("data:image/", ignoreCase = true) -> {
                            val delimiter = source.indexOf(',')
                            require(delimiter > 0 && source.substring(0, delimiter).endsWith(";base64", true))
                            require(source.length - delimiter <= 16 * 1024 * 1024)
                            Base64.decode(source.substring(delimiter + 1), Base64.DEFAULT)
                        }
                        source.startsWith("https://") || source.startsWith("http://") -> source
                        else -> error("Unsupported image source")
                    }
                })
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
        }
        // Reserve preview height while loading so a completed image does not move the followed tail.
        Column(Modifier.fillMaxWidth().height(240.dp)) {
            when {
                loaded == null -> CircularProgressIndicator()
                loaded?.isFailure == true -> CodexImageRetry { attempt++ }
                else -> key(attempt) { SubcomposeAsyncImage(
                    model = loaded?.getOrNull(),
                    contentDescription = stringResource(R.string.codex_image_description, index + 1),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .testTag("codex_timeline_image:$index"),
                    loading = { CircularProgressIndicator() },
                    error = { CodexImageRetry { attempt++ } },
                ) }
            }
        }
    }
}

@Composable
private fun CodexImageRetry(onRetry: () -> Unit) {
    Column {
        Text(stringResource(R.string.codex_image_unavailable))
        TextButton(onClick = onRetry) { Text(stringResource(R.string.codex_image_retry)) }
    }
}
