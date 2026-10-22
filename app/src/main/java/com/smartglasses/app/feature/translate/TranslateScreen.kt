package com.smartglasses.app.feature.translate

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartglasses.app.R
import com.smartglasses.app.core.ui.components.EditTitleDialog
import com.smartglasses.app.core.ui.components.PrimaryButton
import com.smartglasses.app.core.ui.theme.AppTheme
import com.smartglasses.app.core.ui.theme.Radius
import com.smartglasses.app.core.ui.theme.Sizes
import com.smartglasses.app.core.ui.theme.Spacing
import com.smartglasses.app.domain.model.Language
import com.smartglasses.app.domain.model.RecordSummary
import com.smartglasses.app.feature.history.displayTitle
import com.smartglasses.app.feature.history.formatRecordTime

/** Translate home (design image 1): languages, "Start Translation" and "All Records". */
@Composable
fun TranslateScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onStartSession: () -> Unit,
    onOpenRecord: (Long) -> Unit,
    viewModel: TranslateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var renaming by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = state.selecting) { viewModel.toggleSelecting() }
    BackHandler(enabled = state.searching && !state.selecting) { viewModel.closeSearch() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxWidth().statusBarsPadding().padding(vertical = Spacing.xs), contentAlignment = Alignment.Center) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = Spacing.sm)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Text(stringResource(R.string.translate_title), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal))
            IconButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.CenterEnd).padding(end = Spacing.sm)) {
                Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.cd_display_settings))
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item(key = "languages") {
                LanguageCard(
                    source = state.settings.source,
                    target = state.settings.target,
                    onSource = viewModel::setSource,
                    onTarget = viewModel::setTarget,
                    onSwap = viewModel::swapLanguages,
                    onStart = onStartSession,
                )
            }
            item(key = "header") {
                RecordsHeader(
                    searching = state.searching,
                    query = state.query,
                    selecting = state.selecting,
                    onQuery = viewModel::setQuery,
                    onOpenSearch = viewModel::openSearch,
                    onCloseSearch = viewModel::closeSearch,
                    onToggleSelecting = viewModel::toggleSelecting,
                )
            }
            val records = state.records
            when {
                records == null -> Unit
                records.isEmpty() -> item(key = "empty") {
                    Text(
                        stringResource(if (state.query.isBlank()) R.string.records_empty else R.string.records_no_match),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.colors.muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xxl),
                    )
                }
                else -> items(records, key = { it.id }) { record ->
                    RecordRow(
                        record = record,
                        selecting = state.selecting,
                        selected = record.id in state.selected,
                        onClick = { if (state.selecting) viewModel.toggleSelected(record.id) else onOpenRecord(record.id) },
                        onRename = { renaming = record.id },
                    )
                }
            }
        }

        if (state.selecting) {
            val records = state.records.orEmpty()
            SelectionBar(
                allSelected = records.isNotEmpty() && state.selected.size == records.size,
                canDelete = state.selected.isNotEmpty(),
                onSelectAll = viewModel::toggleSelectAll,
                onDelete = { confirmDelete = true },
            )
        }
    }

    renaming?.let { id ->
        EditTitleDialog(
            onDone = { title ->
                viewModel.rename(id, title)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
    if (confirmDelete) {
        val count = state.selected.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(pluralStringResource(R.plurals.delete_records_confirm, count, count)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteSelected()
                }) { Text(stringResource(R.string.action_delete), color = AppTheme.colors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun LanguageCard(
    source: Language,
    target: Language,
    onSource: (Language) -> Unit,
    onTarget: (Language) -> Unit,
    onSwap: () -> Unit,
    onStart: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(Radius.card), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.xl)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LanguagePicker(stringResource(R.string.source_language), source, onSource, Modifier.weight(1f))
                IconButton(onClick = onSwap) {
                    Icon(Icons.Filled.SwapHoriz, contentDescription = stringResource(R.string.cd_swap_languages), modifier = Modifier.size(30.dp))
                }
                LanguagePicker(stringResource(R.string.target_language), target, onTarget, Modifier.weight(1f))
            }
            Spacer(Modifier.height(Spacing.xl))
            PrimaryButton(stringResource(R.string.action_start_translation), onClick = onStart)
        }
    }
}

@Composable
private fun LanguagePicker(label: String, selected: Language, onSelect: (Language) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp))
        Box {
            TextButton(onClick = { open = true }) {
                Text(selected.label, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal), color = MaterialTheme.colorScheme.onSurface)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(32.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Language.entries.forEach { language ->
                    DropdownMenuItem(text = { Text(language.label) }, onClick = { open = false; onSelect(language) })
                }
            }
        }
    }
}

@Composable
private fun RecordsHeader(
    searching: Boolean,
    query: String,
    selecting: Boolean,
    onQuery: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onToggleSelecting: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
        if (searching) {
            val focus = remember { FocusRequester() }
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text(stringResource(R.string.search_records_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = onCloseSearch) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close)) }
                },
                singleLine = true,
                shape = RoundedCornerShape(Radius.button),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
        } else {
            Text(
                stringResource(R.string.all_records),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal),
                modifier = Modifier.weight(1f).padding(start = Spacing.xs),
            )
            IconButton(onClick = onOpenSearch) {
                Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.cd_search_records), modifier = Modifier.size(28.dp))
            }
        }
        IconButton(onClick = onToggleSelecting) {
            Icon(
                painterResource(R.drawable.ic_edit_square),
                contentDescription = stringResource(R.string.cd_select_records),
                tint = if (selecting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun RecordRow(record: RecordSummary, selecting: Boolean, selected: Boolean, onClick: () -> Unit, onRename: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (selecting) {
            SelectionCircle(selected)
            Spacer(Modifier.width(Spacing.lg))
        }
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(Radius.card),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.weight(1f).let { if (selecting) it.semantics { role = Role.Checkbox } else it },
        ) {
            Column(Modifier.padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.lg)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Title + rename icon take the free space; a long title is shortened, the date stays.
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            record.displayTitle(),
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        IconButton(onClick = onRename) {
                            Icon(painterResource(R.drawable.ic_edit_square), contentDescription = stringResource(R.string.cd_rename_record))
                        }
                    }
                    Text(formatRecordTime(record.createdAt), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                }
                PreviewLine(record.previewOriginal)
                PreviewLine(record.previewTranslation)
            }
        }
    }
}

@Composable
private fun PreviewLine(text: String) {
    if (text.isBlank()) return
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = AppTheme.colors.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = Spacing.sm),
    )
}

@Composable
private fun SelectionCircle(selected: Boolean) {
    if (selected) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(SELECTION_SIZE))
    } else {
        Box(Modifier.size(SELECTION_SIZE).padding(2.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape).border(1.dp, AppTheme.colors.muted.copy(alpha = 0.3f), CircleShape))
    }
}

/** "Select All" and the red Delete button (design image 2). */
@Composable
private fun SelectionBar(allSelected: Boolean, canDelete: Boolean, onSelectAll: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).semantics { role = Role.Checkbox },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onSelectAll) { SelectionCircle(allSelected) }
            TextButton(onClick = onSelectAll) {
                Text(stringResource(R.string.select_all), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp), color = MaterialTheme.colorScheme.onBackground)
            }
        }
        Button(
            onClick = onDelete,
            enabled = canDelete,
            shape = RoundedCornerShape(Radius.button),
            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.danger, contentColor = Color.White),
            modifier = Modifier.width(DELETE_WIDTH).height(Sizes.buttonHeight),
        ) {
            Text(stringResource(R.string.action_delete), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp))
        }
    }
}

private val SELECTION_SIZE = 28.dp
private val DELETE_WIDTH = 160.dp
