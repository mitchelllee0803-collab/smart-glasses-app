package com.smartglasses.app.feature.models

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.smartglasses.app.R
import com.smartglasses.app.core.ui.components.AppCard
import com.smartglasses.app.core.ui.components.PrimaryButton
import com.smartglasses.app.core.ui.theme.AppTheme
import com.smartglasses.app.core.ui.theme.Spacing
import com.smartglasses.app.data.translation.AiModelState
import com.smartglasses.app.data.translation.HyMtTranslator

/** The optional AI translation model (Hy-MT2): install from a file, switch on/off, remove. */
@Composable
fun AiModelCard(
    state: AiModelState,
    enabled: Boolean,
    onImport: (android.net.Uri) -> Unit,
    onCancelImport: () -> Unit,
    onRemove: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    // Any file type: Android often does not know the .gguf extension.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri)
    }

    AppCard {
        Text(stringResource(R.string.ai_model_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.sm))
        when (state) {
            is AiModelState.Unsupported -> Text(
                stringResource(
                    if (state.reason == AiModelState.Unsupported.Reason.MEMORY) R.string.ai_model_unsupported_memory
                    else R.string.ai_model_unsupported_cpu,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.muted,
            )
            is AiModelState.NotInstalled -> {
                Text(
                    stringResource(R.string.ai_model_steps, HyMtTranslator.RECOMMENDED_FILE),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.muted,
                )
                state.importError?.let {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(stringResource(R.string.models_failed, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(Spacing.xl))
                PrimaryButton(stringResource(R.string.action_choose_file), onClick = { picker.launch(arrayOf("*/*")) })
            }
            is AiModelState.Importing -> {
                val fraction = if (state.totalBytes > 0) state.copiedBytes.toFloat() / state.totalBytes else 0f
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    stringResource(
                        R.string.models_progress,
                        Formatter.formatShortFileSize(context, state.copiedBytes),
                        Formatter.formatShortFileSize(context, state.totalBytes.coerceAtLeast(0)),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.muted,
                )
                Spacer(Modifier.height(Spacing.lg))
                OutlinedButton(onClick = onCancelImport, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
            }
            is AiModelState.Installed -> {
                Text(
                    stringResource(R.string.ai_model_installed, Formatter.formatShortFileSize(context, state.sizeBytes)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.muted,
                )
                Spacer(Modifier.height(Spacing.lg))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ai_model_use), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(Spacing.md))
                    Switch(checked = enabled, onCheckedChange = onEnabledChange)
                }
                Spacer(Modifier.height(Spacing.sm))
                TextButton(onClick = { confirmRemove = true }) {
                    Text(stringResource(R.string.ai_model_remove), color = AppTheme.colors.danger)
                }
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            text = { Text(stringResource(R.string.ai_model_remove_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onRemove()
                }) { Text(stringResource(R.string.ai_model_remove), color = AppTheme.colors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
