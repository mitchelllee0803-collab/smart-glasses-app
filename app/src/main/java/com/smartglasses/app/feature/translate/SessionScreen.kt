package com.smartglasses.app.feature.translate

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartglasses.app.R
import com.smartglasses.app.core.permissions.AppPermissions
import com.smartglasses.app.core.ui.components.TranscriptList
import com.smartglasses.app.core.ui.theme.AppTheme
import com.smartglasses.app.core.ui.theme.Radius
import com.smartglasses.app.core.ui.theme.Sizes
import com.smartglasses.app.core.ui.theme.Spacing
import com.smartglasses.app.data.translation.SessionError
import com.smartglasses.app.domain.EngineStatus
import com.smartglasses.app.domain.SubtitleSinkStatus
import com.smartglasses.app.domain.model.ConnectionState
import com.smartglasses.app.service.TranslationSessionService

/**
 * A live translation session: a fresh page that starts listening right away. Every finished
 * phrase is saved, so the session appears in "All Records" when the page is left.
 */
@Composable
fun SessionScreen(
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: SessionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    var autoStarted by rememberSaveable { mutableStateOf(false) }

    fun begin() {
        TranslationSessionService.start(context)
        viewModel.startListening()
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // The notification permission is optional: the session works without it.
        if (result[Manifest.permission.RECORD_AUDIO] == false) permissionDenied = true else begin()
    }

    fun toggleOrRequest(listening: Boolean) {
        permissionDenied = false
        when {
            listening -> viewModel.stopListening()
            AppPermissions.granted(context, Manifest.permission.RECORD_AUDIO) -> begin()
            else -> permissionLauncher.launch(AppPermissions.session.toTypedArray())
        }
    }

    val listening = state.session.listening
    val ready = (state.engine as? EngineStatus.Ready)?.usable == true

    // "Start Translation" means start: listen as soon as the engine is ready (once per page).
    LaunchedEffect(ready) {
        if (ready && !autoStarted) {
            autoStarted = true
            toggleOrRequest(listening = false)
        }
    }

    // The wearer opened Translate on the glasses again while this page was open.
    val glassesListenRequest by viewModel.glassesListenRequest.collectAsStateWithLifecycle()
    LaunchedEffect(glassesListenRequest, ready) {
        if (glassesListenRequest && ready) {
            viewModel.consumeGlassesListenRequest()
            if (!state.session.listening && !state.session.finishing) toggleOrRequest(listening = false)
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar(
            languages = "${state.settings.source.label}  →  ${state.settings.target.label}",
            onBack = onBack,
            onSettings = onOpenSettings,
        )
        GlassesStatusChip(state.glasses, state.connection, onReopen = viewModel::reopenOnGlasses)

        Box(Modifier.weight(1f).fillMaxWidth().background(AppTheme.colors.chatBackground)) {
            when {
                state.engine is EngineStatus.Starting -> CenterMessage(stringResource(R.string.engine_starting), showProgress = true)
                state.engine is EngineStatus.Failed ->
                    CenterMessage(stringResource(R.string.engine_failed, (state.engine as EngineStatus.Failed).message))
                !ready -> ModelsNeeded(onOpenModels)
                state.session.lines.isEmpty() -> EmptyTranscript()
                else -> TranscriptList(state.session.lines)
            }
        }

        state.session.error?.let { error ->
            ErrorBanner(
                error,
                onOpenModels = onOpenModels,
                onUsePhoneMic = {
                    TranslationSessionService.start(context)
                    viewModel.usePhoneMicrophone()
                },
                onDismiss = viewModel::dismissError,
            )
        }
        if (permissionDenied) ErrorText(stringResource(R.string.mic_permission_denied))
        MicBar(
            listening = listening,
            finishing = state.session.finishing,
            level = state.session.level,
            enabled = ready,
            onToggle = { toggleOrRequest(listening) },
        )
    }
}

@Composable
private fun TopBar(languages: String, onBack: () -> Unit, onSettings: () -> Unit) {
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(vertical = Spacing.xs), contentAlignment = Alignment.Center) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = Spacing.sm)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.translate_title), style = MaterialTheme.typography.titleLarge)
            Text(languages, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.muted)
        }
        IconButton(onClick = onSettings, modifier = Modifier.align(Alignment.CenterEnd).padding(end = Spacing.sm)) {
            Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.cd_display_settings))
        }
    }
}

@Composable
private fun GlassesStatusChip(status: SubtitleSinkStatus, connection: ConnectionState, onReopen: () -> Unit) {
    val connected = connection is ConnectionState.Connected
    val text = when {
        !connected -> R.string.glasses_status_none
        status == SubtitleSinkStatus.Opening -> R.string.glasses_status_opening
        status == SubtitleSinkStatus.Active -> R.string.glasses_status_active
        status == SubtitleSinkStatus.ViewLeft -> R.string.glasses_status_left
        status is SubtitleSinkStatus.Failed -> R.string.glasses_status_failed
        else -> R.string.glasses_status_none
    }
    val good = connected && status == SubtitleSinkStatus.Active
    val retry = connected && (status == SubtitleSinkStatus.ViewLeft || status is SubtitleSinkStatus.Failed)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm).clip(RoundedCornerShape(Radius.button))
            .background(if (good) AppTheme.colors.success.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface)
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodyMedium,
            color = if (good) AppTheme.colors.success else AppTheme.colors.muted,
            modifier = Modifier.weight(1f).padding(vertical = Spacing.sm),
        )
        if (retry) TextButton(onClick = onReopen) { Text(stringResource(R.string.action_reopen)) }
    }
}

@Composable
private fun EmptyTranscript() {
    Column(Modifier.fillMaxSize().padding(Spacing.xxl), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.transcript_empty_title), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.size(Spacing.sm))
        Text(
            stringResource(R.string.transcript_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.muted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CenterMessage(text: String, showProgress: Boolean = false) {
    Column(Modifier.fillMaxSize().padding(Spacing.xxl), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        if (showProgress) {
            CircularProgressIndicator(Modifier.size(32.dp))
            Spacer(Modifier.size(Spacing.lg))
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, color = AppTheme.colors.muted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ModelsNeeded(onOpenModels: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(Spacing.xxl), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.models_needed_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.size(Spacing.sm))
        Text(
            stringResource(R.string.models_needed_body),
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(Spacing.xl))
        Button(onClick = onOpenModels, shape = RoundedCornerShape(Radius.button)) { Text(stringResource(R.string.action_set_up_models)) }
    }
}

@Composable
private fun ErrorBanner(error: SessionError, onOpenModels: () -> Unit, onUsePhoneMic: () -> Unit, onDismiss: () -> Unit) {
    val message = when (error) {
        SessionError.ModelsMissing -> stringResource(R.string.models_needed_title)
        is SessionError.Microphone -> stringResource(R.string.error_microphone, error.message)
        is SessionError.GlassesMicrophone -> error.message
        is SessionError.Engine -> error.message
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs).clip(RoundedCornerShape(Radius.button))
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f)).padding(start = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f).padding(vertical = Spacing.md))
        if (error == SessionError.ModelsMissing) TextButton(onClick = onOpenModels) { Text(stringResource(R.string.action_set_up_models)) }
        if (error is SessionError.GlassesMicrophone) TextButton(onClick = onUsePhoneMic) { Text(stringResource(R.string.action_use_phone_mic)) }
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.xs))
}

@Composable
private fun MicBar(listening: Boolean, finishing: Boolean, level: Float, enabled: Boolean, onToggle: () -> Unit) {
    Surface(shadowElevation = 8.dp) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val pulse = if (listening) 1f + level * 0.25f else 1f
            Box(
                Modifier.size(Sizes.micButton * pulse).clip(CircleShape)
                    .background(
                        when {
                            !enabled || finishing -> MaterialTheme.colorScheme.surfaceVariant
                            listening -> AppTheme.colors.listening
                            else -> MaterialTheme.colorScheme.primary
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                val description = stringResource(if (listening) R.string.cd_stop_listening else R.string.cd_start_listening)
                IconButton(
                    onClick = onToggle,
                    enabled = enabled && !finishing,
                    modifier = Modifier.size(Sizes.micButton).semantics { contentDescription = description },
                ) {
                    if (finishing) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    } else {
                        Icon(if (listening) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                }
            }
            Spacer(Modifier.size(Spacing.sm))
            Text(
                stringResource(
                    when {
                        finishing -> R.string.mic_finishing
                        listening -> R.string.mic_listening
                        else -> R.string.mic_start
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.muted,
            )
        }
    }
}
