package dev.wuxie233.codecarry.ui.screens.codex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CodexArchiveOperation { ARCHIVE, RESTORE }

data class CodexArchiveFeedback(
    val id: Long,
    val operation: CodexArchiveOperation,
    val archivedCount: Int = 0,
    val restoredCount: Int = 0,
    val failedCount: Int = 0,
    val unknownCount: Int = 0,
    val canUndo: Boolean = false,
)

internal enum class CodexArchiveResult { CONFIRMED, FAILED, UNKNOWN }

/** Main-confined and owned by one server's list ViewModel. Only receipts create undo rights.
 * The first Snackbar presentation starts an accessibility-adjusted expiry, including off-screen time.
 */
internal class CodexArchiveController(
    private val scope: CoroutineScope,
    private val onUnknown: (String) -> Unit = {},
    private val mutate: suspend (String, CodexArchiveOperation) -> CodexArchiveResult,
) {
    private val _feedback = MutableStateFlow<List<CodexArchiveFeedback>>(emptyList())
    val feedback = _feedback.asStateFlow()
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy = _busy.asStateFlow()
    private val undoTargets = mutableMapOf<Long, List<String>>()
    private val unresolved = mutableSetOf<String>()
    private val inFlight = mutableSetOf<String>()
    private var nextId = 0L
    private val expiryJobs = mutableMapOf<Long, Job>()

    fun present(id: Long, timeoutMillis: Long) {
        if (_feedback.value.firstOrNull()?.id != id || id in expiryJobs) return
        expiryJobs[id] = scope.launch {
            delay(timeoutMillis.coerceAtLeast(1))
            dismiss(id)
        }
    }

    fun archive(ids: List<String>) = submit(ids, CodexArchiveOperation.ARCHIVE)
    fun restore(ids: List<String>) = submit(ids, CodexArchiveOperation.RESTORE)

    fun dismiss(id: Long) {
        expiryJobs.remove(id)?.cancel()
        undoTargets.remove(id)
        _feedback.update { entries -> entries.filterNot { it.id == id } }
    }

    fun undo(id: Long) {
        val targets = undoTargets.remove(id) ?: return
        dismiss(id)
        submit(targets, CodexArchiveOperation.RESTORE)
    }

    /** Call only after a complete, generation-fenced read establishes current archive state. */
    fun reconciled(ids: Set<String>) {
        unresolved.removeAll(ids)
        _busy.value = inFlight + unresolved
    }

    fun unresolvedIds(): Set<String> = unresolved.toSet()

    private fun submit(ids: List<String>, operation: CodexArchiveOperation) {
        val targets = ids.distinct().filter { id ->
            id !in _busy.value && id !in unresolved &&
                (operation != CodexArchiveOperation.ARCHIVE || undoTargets.values.none { id in it })
        }
        if (targets.isEmpty()) return
        // Reserve synchronously, before launch, so same-frame taps cannot enqueue duplicates.
        inFlight += targets
        _busy.value = inFlight + unresolved
        // A manual restore consumes any older undo claim for that thread.
        if (operation == CodexArchiveOperation.RESTORE) {
            undoTargets.replaceAll { _, value -> value - targets.toSet() }
            _feedback.update { entries -> entries.map { it.copy(canUndo = undoTargets[it.id]?.isNotEmpty() == true) } }
        }
        scope.launch {
            val confirmed = mutableListOf<String>()
            var failed = 0
            var unknown = 0
            try {
                for (id in targets) {
                    val result = try {
                        mutate(id, operation)
                    } catch (_: TimeoutCancellationException) {
                        CodexArchiveResult.UNKNOWN
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        CodexArchiveResult.UNKNOWN
                    }
                    when (result) {
                        CodexArchiveResult.CONFIRMED -> confirmed += id
                        CodexArchiveResult.FAILED -> failed++
                        CodexArchiveResult.UNKNOWN -> {
                            unknown++
                            unresolved += id
                            onUnknown(id)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                inFlight.removeAll(targets.toSet())
                _busy.value = inFlight + unresolved
                if (confirmed.isNotEmpty() || failed > 0 || unknown > 0) {
                    val id = ++nextId
                    if (operation == CodexArchiveOperation.ARCHIVE && confirmed.isNotEmpty()) undoTargets[id] = confirmed.toList()
                    _feedback.update { entries -> entries + CodexArchiveFeedback(
                        id = id, operation = operation,
                        archivedCount = if (operation == CodexArchiveOperation.ARCHIVE) confirmed.size else 0,
                        restoredCount = if (operation == CodexArchiveOperation.RESTORE) confirmed.size else 0,
                        failedCount = failed, unknownCount = unknown,
                        canUndo = operation == CodexArchiveOperation.ARCHIVE && confirmed.isNotEmpty(),
                    ) }
                }
            }
        }
    }
}
