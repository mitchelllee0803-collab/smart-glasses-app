package com.smartglasses.app.feature.glasses

import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartglasses.app.R
import com.smartglasses.app.core.ui.theme.AppTheme
import com.smartglasses.app.domain.model.ConnectionState
import com.smartglasses.app.domain.model.GlassesReset

/** Settings → About Glasses. [onReleased]: the glasses were unbound or reset (the app disconnected). */
@Composable
fun AboutGlassesScreen(
    onBack: () -> Unit,
    onOpenInfo: () -> Unit,
    onReleased: () -> Unit,
    viewModel: AboutGlassesViewModel = hiltViewModel(),
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val connected = connection as? ConnectionState.Connected
    var confirming by rememberSaveable { mutableStateOf<GlassesReset?>(null) }
    val context = LocalContext.current
    val currentOnReleased by rememberUpdatedState(onReleased)

    LaunchedEffect(Unit) {
        viewModel.finished.collect { (kind, ok) ->
            confirming = null
            val message = when {
                !ok -> R.string.glasses_reset_failed
                kind == GlassesReset.UNBIND -> R.string.glasses_unbound
                else -> R.string.glasses_factory_reset_started
            }
            Toast.makeText(context, context.getString(message), Toast.LENGTH_LONG).show()
            if (ok) currentOnReleased()
        }
    }
    // The question no longer applies once the glasses are gone (unless the answer is awaited).
    LaunchedEffect(connected == null, running) { if (connected == null && running == null) confirming = null }

    GlassesPage(stringResource(R.string.about_glasses_title), onBack) {
        val enabled = connected != null
        RowsCard {
            DetailRow(stringResource(R.string.glasses_name), value = connected?.device?.name ?: stringResource(R.string.status_not_connected))
            RowDivider()
            DetailRow(stringResource(R.string.glasses_info_title), onClick = onOpenInfo, enabled = enabled)
            RowDivider()
            DetailRow(stringResource(R.string.glasses_unbind), onClick = { confirming = GlassesReset.UNBIND }, enabled = enabled)
            RowDivider()
            DetailRow(stringResource(R.string.glasses_factory_reset), onClick = { confirming = GlassesReset.FACTORY_RESET }, enabled = enabled)
        }
        if (!enabled) PageNote(stringResource(R.string.about_glasses_connect_hint))
    }

    confirming?.let { kind ->
        ResetDialog(
            kind = kind,
            working = running == kind,
            onConfirm = { viewModel.reset(kind) },
            onDismiss = { if (running == null) confirming = null },
        )
    }
}

@Composable
private fun ResetDialog(kind: GlassesReset, working: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val unbind = kind == GlassesReset.UNBIND
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (unbind) R.string.glasses_unbind_confirm_title else R.string.glasses_factory_reset_confirm_title)) },
        text = { Text(stringResource(if (unbind) R.string.glasses_unbind_confirm_body else R.string.glasses_factory_reset_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !working) {
                if (working) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        stringResource(if (unbind) R.string.glasses_unbind else R.string.glasses_factory_reset_action),
                        color = AppTheme.colors.danger,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !working) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
