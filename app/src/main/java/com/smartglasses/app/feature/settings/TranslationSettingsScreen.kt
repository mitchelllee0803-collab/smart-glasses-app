package com.smartglasses.app.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartglasses.app.R
import com.smartglasses.app.core.ui.theme.AppTheme
import com.smartglasses.app.core.ui.theme.Radius
import com.smartglasses.app.core.ui.theme.Spacing
import com.smartglasses.app.domain.model.GlassesTextMode
import com.smartglasses.app.domain.model.MicrophoneSource

@Composable
fun TranslationSettingsScreen(onBack: () -> Unit, viewModel: TranslationSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxWidth().statusBarsPadding().padding(vertical = Spacing.xs), contentAlignment = Alignment.Center) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = Spacing.sm)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal))
        }

        val s = settings ?: return@Column // loading: show only the bar for a moment
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl),
        ) {
            SectionTitle(stringResource(R.string.settings_microphone), stringResource(R.string.settings_microphone_hint))
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

            SectionTitle(stringResource(R.string.settings_subtitle_content))
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

            SectionTitle(stringResource(R.string.settings_text_display))
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
        }
    }
}

@Composable
private fun SectionTitle(title: String, hint: String? = null) {
    Text(
        buildAnnotatedString {
            append(title)
            if (hint != null) {
                withStyle(SpanStyle(fontSize = 15.sp, color = AppTheme.colors.muted)) { append(hint) }
            }
        },
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = Spacing.xl, bottom = Spacing.md, start = Spacing.xs),
    )
}

/** Two options side by side; exactly one is selected. */
@Composable
private fun OptionRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Spacing.lg), content = content)
}

@Composable
private fun RowScope.OptionCard(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    visual: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Radius.tile)
    Surface(
        selected = selected,
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = if (selected) BorderStroke(SELECTED_BORDER, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.weight(1f).heightIn(min = CARD_MIN_HEIGHT).semantics { role = Role.RadioButton },
    ) {
        Column(
            Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            visual()
            Spacer(Modifier.height(Spacing.lg))
            Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp), textAlign = TextAlign.Center)
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
            Text("N", color = MaterialTheme.colorScheme.primary, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun SampleText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.primary, fontSize = 21.sp, fontWeight = FontWeight.Bold)
}

private val TILE_SIZE = 64.dp
private val ICON_SIZE = 38.dp
private val CARD_MIN_HEIGHT = 168.dp
private val SELECTED_BORDER = 2.dp
