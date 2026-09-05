package dev.wuxie233.codecarry.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.data.dsh.DshAgentPresetEntry
import dev.wuxie233.codecarry.data.dsh.filterDshPresets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DshPresetPickerSheet(
    presets: List<DshAgentPresetEntry>,
    selectedId: String?,
    loading: Boolean,
    error: String?,
    selecting: Boolean = false,
    allowDefault: Boolean = false,
    enabled: Boolean = true,
    disabledReason: String? = null,
    currentStatus: String? = null,
    onSelect: (String?) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = filterDshPresets(presets, query)
    ModalBottomSheet(onDismissRequest = { if (!selecting) onDismiss() }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.dsh_preset_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.dsh_preset_scope), style = MaterialTheme.typography.bodySmall)
            currentStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.dsh_preset_search)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            if (loading || selecting) LinearProgressIndicator(Modifier.fillMaxWidth())
            disabledReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                if (allowDefault && query.isBlank()) item(key = "host-default") {
                    PresetRow(
                        name = stringResource(R.string.dsh_preset_host_default),
                        description = null,
                        selected = selectedId == null,
                        enabled = enabled && !selecting && !loading,
                        onClick = { onSelect(null) },
                    )
                }
                items(filtered, key = { it.id }) { preset ->
                    PresetRow(
                        name = preset.name?.takeIf { it.isNotBlank() } ?: preset.id,
                        description = preset.description,
                        selected = selectedId == preset.id,
                        enabled = enabled && !loading && !selecting && preset.broken == null,
                        isDefault = preset.isDefault,
                        broken = preset.broken,
                        onClick = { onSelect(preset.id) },
                    )
                }
                if (!loading && filtered.isEmpty()) item {
                    Text(stringResource(if (presets.isEmpty()) R.string.dsh_preset_empty else R.string.dsh_preset_no_match), Modifier.padding(vertical = 16.dp))
                }
            }
            TextButton(onClick = onRefresh, enabled = !loading && !selecting) {
                Text(stringResource(R.string.dsh_preset_refresh))
            }
        }
    }
}

@Composable
private fun PresetRow(
    name: String,
    description: String?,
    selected: Boolean,
    enabled: Boolean,
    isDefault: Boolean = false,
    broken: String? = null,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp)) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (selected) Text(stringResource(R.string.dsh_preset_current), color = MaterialTheme.colorScheme.primary)
            if (isDefault) Text(stringResource(R.string.dsh_preset_default_badge), style = MaterialTheme.typography.labelSmall)
            broken?.let { Text(stringResource(R.string.dsh_preset_broken, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
