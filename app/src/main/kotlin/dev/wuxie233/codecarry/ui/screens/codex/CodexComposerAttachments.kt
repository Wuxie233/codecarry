@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.wuxie233.codecarry.ui.screens.codex

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.data.codex.CodexFileMatch
import dev.wuxie233.codecarry.data.codex.CodexSkill
import dev.wuxie233.codecarry.data.codex.CodexUserInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

/** Wire inputs contain the image bytes, never an Android-only file path. */
data class CodexComposerAttachment(
    val id: String,
    val label: String,
    val input: CodexUserInput,
    val previewBytes: ByteArray? = null,
)

internal suspend fun loadCodexImage(context: Context, uri: Uri, label: String): CodexComposerAttachment =
    withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                require(total <= 20 * 1024 * 1024) { "Image exceeds 20 MB" }
                result.write(buffer, 0, count)
            }
            result.toByteArray()
        } ?: error("Image is unavailable")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Unsupported image")
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        val oriented = if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val jpeg = try {
            ByteArrayOutputStream().use { output ->
                check(oriented.compress(Bitmap.CompressFormat.JPEG, 90, output))
                output.toByteArray()
            }
        } finally { if (oriented !== bitmap) oriented.recycle(); bitmap.recycle() }
        CodexComposerAttachment(
            id = UUID.randomUUID().toString(), label = label,
            input = CodexUserInput.Image("data:image/jpeg;base64,${Base64.encodeToString(jpeg, Base64.NO_WRAP)}"),
            previewBytes = jpeg,
        )
    }

@Composable
fun CodexAttachmentPicker(
    enabled: Boolean,
    skills: List<CodexSkill>,
    files: List<CodexFileMatch>,
    loading: Boolean,
    error: String?,
    onLoadSkills: () -> Unit,
    onSearchFiles: (String) -> Unit,
    onAdd: (CodexComposerAttachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var imageError by remember { mutableStateOf<String?>(null) }
    var imageLoading by remember { mutableStateOf(false) }
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val imageLabel = stringResource(R.string.codex_attachment_image)
    val imageFailure = stringResource(R.string.codex_attachment_image_failed)
    val cameraFailure = stringResource(R.string.codex_attachment_camera_failed)
    fun importImage(uri: Uri, cleanup: File? = null) {
        imageLoading = true
        scope.launch {
            try { onAdd(loadCodexImage(context, uri, imageLabel)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { imageError = imageFailure }
            finally { imageLoading = false; cleanup?.delete() }
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importImage(it) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        cameraPath?.let { path ->
            val file = File(path)
            if (captured) importImage(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file), file)
            else file.delete()
        }
        cameraPath = null
    }
    Box(modifier) {
        IconButton(onClick = { menu = true }, enabled = enabled && !imageLoading) {
            if (imageLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.AttachFile, stringResource(R.string.codex_attachment_add))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.codex_attachment_gallery)) }, onClick = {
                menu = false
                try { gallery.launch("image/*") } catch (_: Exception) { imageError = imageFailure }
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.codex_attachment_camera)) }, onClick = {
                menu = false
                try {
                    val directory = File(context.cacheDir, "codex-attachments").apply { mkdirs() }
                    val file = File.createTempFile("capture-", ".jpg", directory)
                    cameraPath = file.absolutePath
                    camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
                } catch (_: Exception) {
                    cameraPath?.let { File(it).delete() }; cameraPath = null; imageError = cameraFailure
                }
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.codex_attachment_skills)) }, onClick = {
                menu = false; query = ""; sheet = "skills"; onLoadSkills()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.codex_attachment_files)) }, onClick = {
                menu = false; query = ""; sheet = "files"; onSearchFiles("")
            })
        }
    }
    if (imageError != null) AlertDialog(
        onDismissRequest = { imageError = null }, text = { Text(imageError.orEmpty()) },
        confirmButton = { TextButton(onClick = { imageError = null }) { Text(stringResource(android.R.string.ok)) } },
    )
    if (sheet != null) ModalBottomSheet(onDismissRequest = { sheet = null }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text(stringResource(if (sheet == "skills") R.string.codex_attachment_skills else R.string.codex_attachment_files),
                style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(value = query, onValueChange = {
                query = it
                if (sheet == "files") onSearchFiles(it)
            }, label = { Text(stringResource(R.string.codex_attachment_search)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val filteredSkills = skills.filter { it.enabled && (it.name.contains(query, true) || it.description.contains(query, true)) }
            if (!loading && error == null && (if (sheet == "skills") filteredSkills.isEmpty() else files.isEmpty())) {
                Text(stringResource(R.string.codex_attachment_empty), modifier = Modifier.padding(vertical = 16.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                if (sheet == "skills") items(filteredSkills, key = { it.path }) { skill ->
                    TextButton(onClick = {
                        onAdd(CodexComposerAttachment("skill:${skill.path}", skill.name, CodexUserInput.Skill(skill.name, skill.path)))
                        sheet = null
                    }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(skill.name)
                            Text(skill.shortDescription ?: skill.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else items(files, key = { it.absolutePath }) { file ->
                    TextButton(onClick = {
                        onAdd(CodexComposerAttachment("file:${file.absolutePath}", file.fileName,
                            CodexUserInput.Mention(file.fileName, file.absolutePath)))
                        sheet = null
                    }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(file.fileName)
                            Text(file.path, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun CodexAttachmentChips(
    attachments: List<CodexComposerAttachment>,
    enabled: Boolean,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        attachments.forEach { attachment ->
            InputChip(selected = false, enabled = enabled, onClick = { onRemove(attachment.id) },
                label = { Text(attachment.label) },
                avatar = { attachment.previewBytes?.let { bytes ->
                    AsyncImage(bytes, contentDescription = attachment.label, modifier = Modifier.size(32.dp))
                } },
                trailingIcon = { Icon(Icons.Default.Close, stringResource(R.string.codex_attachment_remove)) })
        }
    }
}
