package dev.wuxie233.codecarry.ui.screens.roundtable

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dev.wuxie233.codecarry.R
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wuxie233.codecarry.data.api.PiApi
import dev.wuxie233.codecarry.data.api.PiConnection
import dev.wuxie233.codecarry.data.api.RoundEndPayloadDto
import dev.wuxie233.codecarry.data.repository.ServerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File
import java.net.URLDecoder
import javax.inject.Inject

private const val DEFAULT_TRANSCRIPT_RENDER_CHUNK_CHARS = 4_000
private const val DEFAULT_TRANSCRIPT_RENDER_MAX_CHUNKS = 120

data class RoundtableSummaryUiState(
    val serverName: String = "",
    val roundtableId: String = "",
    val isLoading: Boolean = true,
    val isExporting: Boolean = false,
    val error: String? = null,
    val markdown: String = "",
    val finalSummaryMarkdown: String = "",
    val knowledgeNetworkMermaid: String? = null,
    val openQuestions: List<String> = emptyList(),
    val exportReadyUri: Uri? = null,
)

data class TranscriptRenderChunk(
    val index: Int,
    val text: String,
    val omissionNotice: OmissionNotice? = null,
) {
    val isOmissionNotice: Boolean get() = omissionNotice != null

    data class OmissionNotice(
        val renderedChars: Int,
        val omittedChars: Int,
    )
}

@HiltViewModel
class RoundtableSummaryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val api: PiApi,
    private val json: Json,
    private val serverRepository: ServerRepository,
) : ViewModel() {
    private val serverId: String = decodeRouteArg(savedStateHandle.get<String>("serverId"))
    private val roundtableId: String = decodeRouteArg(savedStateHandle.get<String>("roundtableId"))
    private val _uiState = MutableStateFlow(
        RoundtableSummaryUiState(
            serverName = context.getString(R.string.roundtable_default_name),
            roundtableId = roundtableId,
        ),
    )
    val uiState: StateFlow<RoundtableSummaryUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val conn = resolveConnection()
                val markdown = api.getTranscriptMarkdown(conn, roundtableId)
                val transcript = api.getTranscript(conn, roundtableId)
                val finalPayload = transcript.events
                    .asReversed()
                    .firstOrNull { it.type == "round_end" }
                    ?.payload
                    ?.let { payload -> runCatching { json.decodeFromJsonElement(RoundEndPayloadDto.serializer(), payload) }.getOrNull() }
                val finalSummary = finalPayload?.finalSummaryMarkdown.orEmpty()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    markdown = markdown,
                    finalSummaryMarkdown = finalSummary,
                    knowledgeNetworkMermaid = extractLastMermaidBlock(finalSummary) ?: extractLastMermaidBlock(markdown),
                    openQuestions = finalPayload?.openQuestions?.ifEmpty { extractOpenQuestions(markdown) } ?: extractOpenQuestions(markdown),
                    error = null,
                )
            } catch (error: Exception) {
                _uiState.update { it.copy(isLoading = false, error = error.message ?: context.getString(R.string.roundtable_summary_error_load)) }
            }
        }
    }

    private suspend fun resolveConnection(): PiConnection {
        val server = serverRepository.getServer(serverId) ?: error(context.getString(R.string.roundtable_error_saved_server_missing))
        _uiState.update { it.copy(serverName = server.displayName.ifBlank { context.getString(R.string.roundtable_default_name) }) }
        return PiConnection.from(server.url, server.token)
    }

    fun exportToUri(context: Context, uri: Uri, onResult: (Boolean) -> Unit) {
        val content = _uiState.value.markdown
        viewModelScope.launch(Dispatchers.IO) {
            val success = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output -> output.write(content.toByteArray(Charsets.UTF_8)) }
                    ?: error(context.getString(R.string.roundtable_summary_error_output_stream))
            }.isSuccess
            withContext(Dispatchers.Main) { onResult(success) }
        }
    }

    fun prepareShare(context: Context, onResult: (Boolean) -> Unit) {
        val content = _uiState.value.markdown
        viewModelScope.launch(Dispatchers.IO) {
            val uri = runCatching {
                val dir = File(context.cacheDir, "roundtable-summary").apply { mkdirs() }
                val file = File(dir, "roundtable-${safeFilePart(roundtableId)}-summary.md")
                file.writeText(content, Charsets.UTF_8)
                FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            }.getOrNull()
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(exportReadyUri = uri) }
                onResult(uri != null)
            }
        }
    }

    fun clearShareUri() {
        _uiState.update { it.copy(exportReadyUri = null) }
    }
}

fun roundtableSummaryShareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/markdown"
    putExtra(Intent.EXTRA_STREAM, uri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

internal fun extractLastMermaidBlock(markdown: String): String? {
    val pattern = Regex("```mermaid\\s*\\r?\\n([\\s\\S]*?)\\r?\\n?```", RegexOption.IGNORE_CASE)
    return pattern.findAll(markdown).lastOrNull()?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
}

internal fun extractOpenQuestions(markdown: String): List<String> {
    val start = markdown.lines().indexOfLast { it.trim().contains("开放问题") || it.trim().contains("Open Questions", ignoreCase = true) }
    if (start < 0) return emptyList()
    return markdown.lines().drop(start + 1).takeWhile { line -> !line.startsWith("## ") }.mapNotNull { line ->
        line.trim().removePrefix("-").trim().takeIf { it.isNotBlank() }
    }
}

internal fun splitTranscriptForRendering(
    markdown: String,
    maxChunkChars: Int = DEFAULT_TRANSCRIPT_RENDER_CHUNK_CHARS,
    maxChunks: Int = DEFAULT_TRANSCRIPT_RENDER_MAX_CHUNKS,
): List<TranscriptRenderChunk> {
    if (markdown.isBlank()) return emptyList()

    val safeChunkChars = maxChunkChars.coerceAtLeast(256)
    val safeMaxChunks = maxChunks.coerceAtLeast(1)
    val maxRenderedChars = (safeChunkChars.toLong() * safeMaxChunks.toLong())
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
    val rendered = markdown.take(maxRenderedChars)
    val chunks = rendered.chunked(safeChunkChars).mapIndexed { index, chunk ->
        TranscriptRenderChunk(index = index, text = chunk)
    }
    val omittedChars = markdown.length - rendered.length
    if (omittedChars <= 0) return chunks

    val notice = TranscriptRenderChunk.OmissionNotice(rendered.length, omittedChars)
    return chunks + TranscriptRenderChunk(index = chunks.size, text = "", omissionNotice = notice)
}

private fun safeFilePart(value: String): String = value.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifBlank { "roundtable" }

private fun decodeRouteArg(value: String?): String {
    val raw = value.orEmpty()
    return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
}
