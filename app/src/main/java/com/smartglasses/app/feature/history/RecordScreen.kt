package com.smartglasses.app.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartglasses.app.R
import com.smartglasses.app.core.ui.components.EditTitleDialog
import com.smartglasses.app.core.ui.components.TranscriptList
import com.smartglasses.app.core.ui.theme.AppTheme
import com.smartglasses.app.core.ui.theme.Spacing

/** A saved session, read-only, in the same message layout as the live page. */
@Composable
fun RecordScreen(onBack: () -> Unit, viewModel: RecordViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var renaming by rememberSaveable { mutableStateOf(false) }
    val loaded = state as? RecordUiState.Loaded

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxWidth().statusBarsPadding().padding(vertical = Spacing.xs), contentAlignment = Alignment.Center) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = Spacing.sm)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            if (loaded != null) {
                val summary = loaded.record.summary
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = TITLE_SIDE_PADDING),
                ) {
                    Text(summary.displayTitle(), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${formatRecordTime(summary.createdAt)}  ·  ${summary.source.label} → ${summary.target.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.colors.muted,
                        maxLines = 1,
                    )
                }
                IconButton(onClick = { renaming = true }, modifier = Modifier.align(Alignment.CenterEnd).padding(end = Spacing.sm)) {
                    Icon(painterResource(R.drawable.ic_edit_square), contentDescription = stringResource(R.string.cd_rename_record))
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(AppTheme.colors.chatBackground).navigationBarsPadding()) {
            when (val s = state) {
                RecordUiState.Loading -> Unit
                RecordUiState.Missing -> Text(
                    stringResource(R.string.record_missing),
                    color = AppTheme.colors.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(Spacing.xxl),
                )
                is RecordUiState.Loaded -> TranscriptList(s.record.lines, followNewest = false)
            }
        }
    }

    if (renaming) {
        EditTitleDialog(
            onDone = {
                viewModel.rename(it)
                renaming = false
            },
            onDismiss = { renaming = false },
        )
    }
}

/** Keeps the centred title clear of the back and rename buttons. */
private val TITLE_SIDE_PADDING = 56.dp
