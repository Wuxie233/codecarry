package dev.minios.ocremote.ui.screens.sessions.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.data.preferences.SessionFilter
import dev.minios.ocremote.data.preferences.SessionScope
import dev.minios.ocremote.data.preferences.SessionSort

@Composable
fun SessionListTopControls(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    sort: SessionSort,
    onSortChange: (SessionSort) -> Unit,
    filter: SessionFilter,
    onFilterChange: (SessionFilter) -> Unit,
    scope: SessionScope,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val isAmoled = colors.background == Color.Black && colors.surface == Color.Black
    var expanded by rememberSaveable { mutableStateOf(searchQuery.isNotBlank()) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val filterScrollState = rememberScrollState()

    LaunchedEffect(expanded) {
        if (expanded) {
            focusRequester.requestFocus()
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = expanded,
                    transitionSpec = {
                        ContentTransform(
                            targetContentEnter = fadeIn(animationSpec = tween(300)) +
                                expandHorizontally(animationSpec = tween(300)),
                            initialContentExit = fadeOut(animationSpec = tween(220)) +
                                shrinkHorizontally(animationSpec = tween(300)),
                            sizeTransform = SizeTransform(clip = false),
                        )
                    },
                    label = "session_list_search_expand",
                ) { isExpanded ->
                    if (isExpanded) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            singleLine = true,
                            placeholder = {
                                Text(stringResource(R.string.sessions_search_hint))
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                )
                            },
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        onSearchQueryChange("")
                                        expanded = false
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.sessions_search_clear),
                                    )
                                }
                            },
                            shape = RoundedCornerShape(16.dp),
                        )
                    } else {
                        SearchCollapsedHint(
                            isAmoled = isAmoled,
                            onExpand = { expanded = true },
                        )
                    }
                }
            }

            Box {
                IconButton(onClick = { sortMenuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.Sort,
                        contentDescription = stringResource(R.string.sessions_sort),
                    )
                }

                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false },
                ) {
                    SessionSort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(text = sortLabel(option)) },
                            leadingIcon = {
                                if (sort == option) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                } else {
                                    Box(modifier = Modifier.size(24.dp))
                                }
                            },
                            onClick = {
                                onSortChange(option)
                                sortMenuExpanded = false
                            },
                        )
                    }
                }
            }
        }

        if (scope == SessionScope.INBOX) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(filterScrollState)
                    .padding(horizontal = 16.dp)
                    .heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SessionFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { onFilterChange(option) },
                        label = { Text(text = filterLabel(option)) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = if (isAmoled) Color.Black else colors.surface,
                            selectedContainerColor = colors.primaryContainer,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = filter == option,
                            borderColor = if (isAmoled) colors.outlineVariant else colors.outlineVariant.copy(alpha = 0.6f),
                            selectedBorderColor = if (isAmoled) colors.primaryContainer else colors.outlineVariant.copy(alpha = 0.3f),
                            borderWidth = 1.dp,
                            selectedBorderWidth = 1.dp,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchCollapsedHint(
    isAmoled: Boolean,
    onExpand: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onExpand),
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = BorderStroke(
            width = 1.dp,
            color = if (isAmoled) colors.outlineVariant else colors.outlineVariant.copy(alpha = 0.55f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onExpand) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = stringResource(R.string.sessions_search_hint),
                )
            }

            Text(
                text = stringResource(R.string.sessions_search_hint),
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun sortLabel(sort: SessionSort): String = when (sort) {
    SessionSort.RECENT_UPDATED -> stringResource(R.string.sessions_sort_recently_updated)
    SessionSort.CREATED_TIME -> stringResource(R.string.sessions_sort_created_time)
    SessionSort.TITLE_ALPHA -> stringResource(R.string.sessions_sort_title_alpha)
}

@Composable
private fun filterLabel(filter: SessionFilter): String = when (filter) {
    SessionFilter.ALL -> stringResource(R.string.sessions_filter_all)
    SessionFilter.WORKING -> stringResource(R.string.sessions_filter_working)
    SessionFilter.HAS_CHANGES -> stringResource(R.string.sessions_filter_has_changes)
    SessionFilter.HAS_ERRORS -> stringResource(R.string.sessions_filter_has_errors)
}
