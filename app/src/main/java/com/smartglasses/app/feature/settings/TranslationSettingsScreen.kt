package com.smartglasses.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartglasses.app.R
import com.smartglasses.app.core.ui.components.OptionCard
import com.smartglasses.app.core.ui.components.OptionRow
import com.smartglasses.app.core.ui.components.SettingsSectionTitle
import com.smartglasses.app.core.ui.theme.AppTheme
import com.smartglasses.app.core.ui.theme.Radius
import com.smartglasses.app.core.ui.theme.Spacing
import com.smartglasses.app.domain.model.DisplaySettings
import com.smartglasses.app.domain.model.GlassesTextMode
import com.smartglasses.app.domain.model.MicrophoneSource
import kotlin.math.roundToInt

@Composable
fun TranslationSettingsScreen(onBack: () -> Unit, viewModel: TranslationSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxWidth().statusBarsPadding().padding(vertical = Spacing.xs), contentAlignment = Alignment.Center) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = Spacing.sm)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)
        }

        val s = settings ?: return@Column // loading: show only the bar for a moment
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl),
        ) {
            SettingsSectionTitle(stringResource(R.string.settings_microphone), stringResource(R.string.settings_microphone_hint))
            OptionRow {
                OptionCard(
                    selected = s.microphone == MicrophoneSource.GLASSES,
                    label = stringResource(R.string.settings_mic_glasses),
                    onClick = { viewModel.setMicrophone(MicrophoneSource.GLASSES) },
                ) { IconTile(painterResource(R.drawable.ic_stat_glasses)) }
                OptionCard(
                    selected = s.microphone == MicrophoneSource.PHONE,
                    label = stringResource(R.string.settings_mic_phone),
                    onClick = { viewModel.setMicrophone(MicrophoneSource.PHONE) },
                ) { IconTile(rememberVectorPainter(Icons.Filled.Smartphone)) }
            }

            SettingsSectionTitle(stringResource(R.string.settings_subtitle_content))
            OptionRow {
                OptionCard(
                    selected = s.display.textMode == GlassesTextMode.ORIGINAL_AND_TRANSLATION,
                    label = stringResource(R.string.settings_show_both),
                    onClick = { viewModel.setTextMode(GlassesTextMode.ORIGINAL_AND_TRANSLATION) },
                ) {
                    SampleText(stringResource(R.string.settings_sample_original))
                    Spacer(Modifier.height(Spacing.sm))
                    SampleText(stringResource(R.string.settings_sample_translation))
                }
                OptionCard(
                    selected = s.display.textMode == GlassesTextMode.TRANSLATION_ONLY,
                    label = stringResource(R.string.settings_show_translation),
                    onClick = { viewModel.setTextMode(GlassesTextMode.TRANSLATION_ONLY) },
                ) { SampleText(stringResource(R.string.settings_sample_translation)) }
            }

            SettingsSectionTitle(stringResource(R.string.settings_text_display))
            OptionRow {
                OptionCard(
                    selected = s.display.showHistory,
                    label = stringResource(R.string.settings_history),
                    onClick = { viewModel.setShowHistory(true) },
                ) { IconTile(rememberVectorPainter(Icons.Outlined.Schedule)) }
                OptionCard(
                    selected = !s.display.showHistory,
                    label = stringResource(R.string.settings_newest),
                    onClick = { viewModel.setShowHistory(false) },
                ) { NewestTile() }
            }

            SettingsSectionTitle(stringResource(R.string.settings_font_size))
            FontSizeCard(fontSize = s.display.fontSize, onChange = viewModel::setFontSize)
        }
    }
}

/** Text size on the glasses (VenusSDK FontConfig); saved when the slider is released. */
@Composable
private fun FontSizeCard(fontSize: Int, onChange: (Int) -> Unit) {
    var value by remember(fontSize) { mutableFloatStateOf(fontSize.toFloat()) }
    val current = value.roundToInt()
    Surface(shape = RoundedCornerShape(Radius.tile), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.lg)) {
            Text(
                if (current == DisplaySettings.DEFAULT_FONT_SIZE) stringResource(R.string.settings_font_size_value_default, current)
                else stringResource(R.string.settings_font_size_value, current),
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A", style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.muted)
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    onValueChangeFinished = { if (current != fontSize) onChange(current) },
                    valueRange = DisplaySettings.MIN_FONT_SIZE.toFloat()..DisplaySettings.MAX_FONT_SIZE.toFloat(),
                    steps = (DisplaySettings.MAX_FONT_SIZE - DisplaySettings.MIN_FONT_SIZE) / FONT_STEP - 1,
                    modifier = Modifier.weight(1f).padding(horizontal = Spacing.md),
                )
                Text("A", style = MaterialTheme.typography.titleLarge, color = AppTheme.colors.muted)
            }
            Text(stringResource(R.string.settings_font_size_hint), style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.muted)
        }
    }
}

@Composable
private fun IconTile(painter: Painter) {
    Box(
        Modifier.size(TILE_SIZE).background(AppTheme.colors.iconCircle, RoundedCornerShape(Radius.tile)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(ICON_SIZE))
    }
}

/** "N" in a rounded frame, as in the design. */
@Composable
private fun NewestTile() {
    Box(
        Modifier.size(TILE_SIZE).background(AppTheme.colors.iconCircle, RoundedCornerShape(Radius.tile)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(ICON_SIZE).border(2.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("N", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SampleText(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

private val TILE_SIZE = 64.dp
private val ICON_SIZE = 38.dp
private const val FONT_STEP = 2
