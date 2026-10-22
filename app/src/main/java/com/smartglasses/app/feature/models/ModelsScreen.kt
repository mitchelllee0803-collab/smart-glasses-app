package com.smartglasses.app.feature.models

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartglasses.app.R
import com.smartglasses.app.core.ui.components.AppCard
import com.smartglasses.app.core.ui.components.PrimaryButton
import com.smartglasses.app.core.ui.theme.AppTheme
import com.smartglasses.app.core.ui.theme.Sizes
import com.smartglasses.app.core.ui.theme.Spacing
import com.smartglasses.app.domain.EngineStatus
import com.smartglasses.app.domain.ModelImportState

@Composable
fun ModelsScreen(onBack: () -> Unit, viewModel: ModelsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.import(uri)
    }
    val running = state.import as? ModelImportState.Running
    val ready = (state.engine as? EngineStatus.Ready)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(shadowElevation = Sizes.cardElevation) {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(vertical = Spacing.xs)) {
                IconButton(onClick = onBack, modifier = Modifier.padding(start = Spacing.sm)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
                Text(stringResource(R.string.models_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm))
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Spacing.lg), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.lg)) {
            AppCard {
                val title = when {
                    ready == null -> R.string.engine_starting
                    ready.usable -> R.string.models_ready_title
                    ready.translationReady || ready.speechReady -> R.string.models_partial_title
                    else -> R.string.models_missing_title
                }
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Spacing.sm))
                if (ready?.usable == true) {
                    Text(stringResource(R.string.models_ready_body), style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.muted)
                } else {
                    Text(stringResource(R.string.models_steps), style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.muted)
                    ready?.problems?.take(MAX_PROBLEMS_SHOWN)?.forEach {
                        Text("• $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Spacing.xs))
                    }
                }
                Spacer(Modifier.height(Spacing.xl))
                if (running == null) {
                    PrimaryButton(stringResource(R.string.action_choose_folder), onClick = { picker.launch(null) }, enabled = ready != null)
                } else {
                    val fraction = if (running.totalBytes > 0) running.copiedBytes.toFloat() / running.totalBytes else 0f
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        stringResource(
                            R.string.models_progress,
                            Formatter.formatShortFileSize(context, running.copiedBytes),
                            Formatter.formatShortFileSize(context, running.totalBytes),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.colors.muted,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Spacer(Modifier.height(Spacing.lg))
                    OutlinedButton(onClick = viewModel::cancelImport, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
                }
            }
            when (val result = state.import) {
                is ModelImportState.Done -> AppCard {
                    Text(stringResource(R.string.models_done, result.importedFiles, result.alreadyInstalled), style = MaterialTheme.typography.bodyLarge)
                    if (result.missing.isNotEmpty()) {
                        Text(
                            stringResource(R.string.models_done_missing, result.missing.take(MAX_PROBLEMS_SHOWN).joinToString()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                is ModelImportState.Failed -> AppCard {
                    Text(
                        if (result.cancelled) stringResource(R.string.models_cancelled) else stringResource(R.string.models_failed, result.message),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (result.cancelled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                }
                else -> Unit
            }
        }
    }
}

private const val MAX_PROBLEMS_SHOWN = 4
