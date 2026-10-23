package com.smartglasses.app.feature.gallery

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartglasses.app.R
import com.smartglasses.app.core.ui.components.PrimaryButton
import com.smartglasses.app.core.ui.theme.AppTheme
import com.smartglasses.app.core.ui.theme.Radius
import com.smartglasses.app.core.ui.theme.Spacing
import com.smartglasses.app.data.gallery.GalleryImages
import com.smartglasses.app.domain.model.GalleryPhoto
import com.smartglasses.app.domain.model.GalleryProblem
import com.smartglasses.app.domain.model.GalleryUpload
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Dashboard → Gallery: the photos on the glasses, Cast to show one, Import, select and delete. */
@Composable
fun GalleryScreen(onBack: () -> Unit, viewModel: GalleryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val editing by viewModel.editing.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_IMPORT)) { uris ->
        viewModel.import(uris)
    }
    LaunchedEffect(Unit) {
        viewModel.problems.collect { Toast.makeText(context, context.getString(it.messageRes), Toast.LENGTH_LONG).show() }
    }
    BackHandler(enabled = editing) { viewModel.setEditing(false) }
    // Only photos still listed count as selected.
    val selectedNow = selected.filterTo(mutableSetOf()) { name -> state.photos.any { it.name == name } }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        GalleryTopBar(
            editing = editing,
            enabled = connected,
            onBack = { if (editing) viewModel.setEditing(false) else onBack() },
            onToggleEditing = { viewModel.setEditing(!editing) },
            onRefresh = viewModel::refresh,
            onClear = { confirmClear = true },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val empty = state.photos.isEmpty() && state.uploads.isEmpty()
            when {
                !connected -> CenteredNote(stringResource(R.string.gallery_not_connected))
                empty && state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                empty -> CenteredNote(stringResource(R.string.gallery_empty))
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(Spacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                ) {
                    items(state.photos, key = { "photo:" + it.name }) { photo ->
                        PhotoTile(
                            photo = photo,
                            casting = state.casting == photo.name,
                            pending = state.castPending == photo.name,
                            editing = editing,
                            selected = photo.name in selectedNow,
                            onCast = { viewModel.cast(photo.name) },
                            onToggle = { viewModel.toggle(photo.name) },
                        )
                    }
                    items(state.uploads, key = { "upload:" + it.id }) { upload ->
                        UploadTile(upload, onRetry = { viewModel.retry(upload.id) }, onDiscard = { viewModel.discard(upload.id) })
                    }
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.xl).padding(bottom = Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PrimaryButton(
                text = stringResource(if (state.preparing) R.string.gallery_preparing else R.string.gallery_import),
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = connected && !editing && !state.preparing,
                loading = state.preparing,
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                stringResource(R.string.gallery_free_space, state.freeKb?.let(::formatKb) ?: "—"),
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.muted,
            )
            if (editing) {
                EditBar(
                    allSelected = state.photos.isNotEmpty() && selectedNow.size == state.photos.size,
                    canDelete = selectedNow.isNotEmpty(),
                    onSelectAll = viewModel::selectAll,
                    onDelete = { confirmDelete = true },
                )
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.gallery_delete_confirm_title, selectedNow.size),
            body = stringResource(R.string.gallery_delete_confirm_body),
            action = stringResource(R.string.action_delete),
            onConfirm = {
                confirmDelete = false
                viewModel.deleteSelected()
            },
            onDismiss = { confirmDelete = false },
        )
    }
    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.gallery_clear_confirm_title),
            body = stringResource(R.string.gallery_clear_confirm_body),
            action = stringResource(R.string.gallery_clear_action),
            onConfirm = {
                confirmClear = false
                viewModel.clearAll()
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun GalleryTopBar(
    editing: Boolean,
    enabled: Boolean,
    onBack: () -> Unit,
    onToggleEditing: () -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(vertical = Spacing.xs), contentAlignment = Alignment.Center) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = Spacing.sm)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
        }
        Text(stringResource(R.string.gallery_title), style = MaterialTheme.typography.titleLarge)
        Row(Modifier.align(Alignment.CenterEnd).padding(end = Spacing.sm)) {
            IconButton(onClick = onToggleEditing, enabled = enabled) {
                Icon(
                    Icons.Outlined.EditNote,
                    contentDescription = stringResource(R.string.cd_gallery_edit),
                    tint = if (editing) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = enabled) {
                    Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.cd_gallery_menu))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.gallery_refresh)) }, onClick = { menuOpen = false; onRefresh() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.gallery_clear)) }, onClick = { menuOpen = false; onClear() })
                }
            }
        }
    }
}

@Composable
private fun PhotoTile(
    photo: GalleryPhoto,
    casting: Boolean,
    pending: Boolean,
    editing: Boolean,
    selected: Boolean,
    onCast: () -> Unit,
    onToggle: () -> Unit,
) {
    Column {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(Radius.tile))
                .let { if (casting) it.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(Radius.tile)) else it }
                .let { if (editing) it.clickable(onClick = onToggle).semantics { this.selected = selected; role = Role.Checkbox } else it },
        ) {
            Thumbnail(photo.thumbnail, photo.name, Modifier.fillMaxSize())
            if (editing) SelectCircle(selected, Modifier.align(Alignment.TopStart).padding(Spacing.sm))
        }
        Spacer(Modifier.height(Spacing.sm))
        Button(
            onClick = onCast,
            enabled = !editing && !pending,
            shape = RoundedCornerShape(Radius.button),
            colors = if (casting) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (pending) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(if (casting) R.string.gallery_casting else R.string.gallery_cast))
            }
        }
    }
}

@Composable
private fun UploadTile(upload: GalleryUpload, onRetry: () -> Unit, onDiscard: () -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(Radius.tile))) {
            Thumbnail(upload.file, upload.name, Modifier.fillMaxSize())
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                    .padding(Spacing.sm),
            ) {
                Text(
                    if (upload.failed) stringResource(R.string.gallery_upload_failed)
                    else stringResource(R.string.gallery_uploading, (upload.progress * 100).toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (upload.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                if (!upload.failed) {
                    Spacer(Modifier.height(Spacing.xs))
                    LinearProgressIndicator(progress = { upload.progress }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        if (upload.failed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onRetry, shape = RoundedCornerShape(Radius.button), modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_retry))
                }
                IconButton(onClick = onDiscard) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_discard_upload))
                }
            }
        } else {
            Button(onClick = {}, enabled = false, shape = RoundedCornerShape(Radius.button), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.gallery_cast))
            }
        }
    }
}

/** The phone's copy of a photo; a placeholder with its name when there is none. */
@Composable
private fun Thumbnail(file: File?, name: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, file) {
        value = file?.let { withContext(Dispatchers.IO) { GalleryImages.thumbnail(it, THUMBNAIL_SIDE)?.asImageBitmap() } }
    }
    val image = bitmap
    Box(modifier.background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(image, contentDescription = name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(Spacing.md)) {
                Icon(Icons.Outlined.Image, contentDescription = null, tint = AppTheme.colors.muted, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    name,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.muted,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SelectCircle(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.size(24.dp)
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
            .border(1.5.dp, if (selected) MaterialTheme.colorScheme.primary else AppTheme.colors.muted, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun EditBar(allSelected: Boolean, canDelete: Boolean, onSelectAll: (Boolean) -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(Radius.button)).clickable { onSelectAll(!allSelected) }
                .padding(vertical = Spacing.sm, horizontal = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectCircle(allSelected)
            Spacer(Modifier.width(Spacing.md))
            Text(stringResource(R.string.gallery_select_all), style = MaterialTheme.typography.titleSmall)
        }
        Button(
            onClick = onDelete,
            enabled = canDelete,
            shape = RoundedCornerShape(Radius.button),
            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.danger, contentColor = androidx.compose.ui.graphics.Color.White),
        ) {
            Text(stringResource(R.string.action_delete), modifier = Modifier.padding(horizontal = Spacing.xl))
        }
    }
}

@Composable
private fun CenteredNote(text: String) {
    Box(Modifier.fillMaxSize().padding(Spacing.xl), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = AppTheme.colors.muted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action, color = AppTheme.colors.danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Like the reference app: "4.09 MB". */
private fun formatKb(kb: Long): String =
    if (kb >= 1024) String.format(Locale.getDefault(), "%.2f MB", kb / 1024.0) else "$kb KB"

private val GalleryProblem.messageRes: Int
    get() = when (this) {
        GalleryProblem.LIST_FAILED -> R.string.gallery_list_failed
        GalleryProblem.IMPORT_FAILED -> R.string.gallery_import_failed
        GalleryProblem.NO_SPACE -> R.string.gallery_no_space
        GalleryProblem.UPLOAD_FAILED -> R.string.gallery_upload_failed_message
        GalleryProblem.CAST_FAILED -> R.string.gallery_cast_failed
        GalleryProblem.DELETE_FAILED -> R.string.gallery_delete_failed
        GalleryProblem.CLEAR_FAILED -> R.string.gallery_clear_failed
    }

private const val MAX_IMPORT = 10
private const val THUMBNAIL_SIDE = 360
