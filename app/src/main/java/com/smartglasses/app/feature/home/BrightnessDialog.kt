package com.smartglasses.app.feature.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartglasses.app.R
import com.smartglasses.app.core.ui.theme.Radius
import com.smartglasses.app.core.ui.theme.Sizes
import com.smartglasses.app.core.ui.theme.Spacing
import com.smartglasses.app.data.venus.BrightnessProblem
import com.smartglasses.app.data.venus.BrightnessScale
import kotlin.math.roundToInt

/** ☀ on the dashboard: a tall slider at the side of the dimmed page, with the Auto switch below. */
@Composable
fun BrightnessDialog(onDismiss: () -> Unit, viewModel: BrightnessViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(Unit) {
        viewModel.problems.collect { Toast.makeText(context, context.getString(it.messageRes), Toast.LENGTH_SHORT).show() }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) {
            // Tapping anywhere beside the panel closes it.
            Box(
                Modifier.matchParentSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
            Surface(
                shape = RoundedCornerShape(Radius.card),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = Sizes.cardElevation,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = Spacing.xl),
            ) {
                Column(
                    Modifier.width(PANEL_WIDTH).padding(Spacing.md),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BrightnessSlider(
                        percent = state.percent,
                        loading = state.loading,
                        onChange = viewModel::setPercent,
                        modifier = Modifier.fillMaxWidth().height(SLIDER_HEIGHT),
                    )
                    if (state.autoSupported) {
                        Spacer(Modifier.height(Spacing.md))
                        Switch(checked = state.auto, onCheckedChange = viewModel::setAuto, enabled = !state.loading)
                        Text(stringResource(R.string.brightness_auto), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** Fills from the bottom like the phone's own brightness control; tap or drag anywhere on it. */
@Composable
private fun BrightnessSlider(percent: Int, loading: Boolean, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val currentOnChange by rememberUpdatedState(onChange)
    val description = stringResource(R.string.cd_brightness)
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .clip(RoundedCornerShape(Radius.tile))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = TRACK_ALPHA))
            .pointerInput(loading) {
                if (loading) return@pointerInput
                fun percentAt(y: Float) = ((1f - y / size.height) * 100).roundToInt()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    currentOnChange(percentAt(down.position.y))
                    drag(down.id) { change ->
                        currentOnChange(percentAt(change.position.y))
                        change.consume()
                    }
                }
            }
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(percent.toFloat(), BrightnessScale.MIN_PERCENT.toFloat()..100f)
                if (!loading) setProgress { currentOnChange(it.roundToInt()); true }
            },
    ) {
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(percent / 100f)
                .background(primary.copy(alpha = FILL_ALPHA)),
        )
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.LightMode, contentDescription = null, tint = primary, modifier = Modifier.size(28.dp))
                Text(stringResource(R.string.brightness_percent, percent), style = MaterialTheme.typography.titleSmall, color = primary)
            }
        }
    }
}

private val BrightnessProblem.messageRes: Int
    get() = when (this) {
        BrightnessProblem.READ_FAILED -> R.string.brightness_read_failed
        BrightnessProblem.SET_FAILED -> R.string.brightness_set_failed
        BrightnessProblem.AUTO_UNSUPPORTED -> R.string.brightness_auto_unsupported
    }

private val PANEL_WIDTH = 96.dp
private val SLIDER_HEIGHT = 260.dp
private const val TRACK_ALPHA = 0.08f
private const val FILL_ALPHA = 0.22f
