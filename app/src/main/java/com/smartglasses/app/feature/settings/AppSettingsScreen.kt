package com.smartglasses.app.feature.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartglasses.app.R
import com.smartglasses.app.core.locale.AppLanguage
import com.smartglasses.app.core.locale.AppLocales
import com.smartglasses.app.core.ui.components.ListRow
import com.smartglasses.app.core.ui.components.OptionCard
import com.smartglasses.app.core.ui.components.OptionRow
import com.smartglasses.app.core.ui.components.SettingsSectionTitle
import com.smartglasses.app.core.ui.theme.AppTheme
import com.smartglasses.app.core.ui.theme.Radius
import com.smartglasses.app.core.ui.theme.Spacing
import com.smartglasses.app.domain.model.AppTextSize
import com.smartglasses.app.domain.model.ThemeMode

/** ☰ → Settings: options for the app itself. */
@Composable
fun AppSettingsScreen(onBack: () -> Unit, onOpenAboutGlasses: () -> Unit, viewModel: AppSettingsViewModel = hiltViewModel()) {
    val textSize by viewModel.textSize.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val passwordResult by viewModel.passwordResult.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val language = AppLocales.current(context)
    var changingPassword by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxWidth().statusBarsPadding().padding(vertical = Spacing.xs), contentAlignment = Alignment.Center) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = Spacing.sm)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Text(stringResource(R.string.app_settings_title), style = MaterialTheme.typography.titleLarge)
        }

        val selectedSize = textSize ?: return@Column // loading: show only the bar for a moment
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl),
        ) {
            SettingsSectionTitle(stringResource(R.string.app_glasses))
            Surface(shape = RoundedCornerShape(Radius.tile), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                ListRow(
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.about_glasses_title),
                    subtitle = stringResource(R.string.about_glasses_hint),
                    onClick = onOpenAboutGlasses,
                    modifier = Modifier.padding(horizontal = Spacing.md),
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = AppTheme.colors.muted)
                }
            }

            SettingsSectionTitle(stringResource(R.string.app_password))
            Surface(shape = RoundedCornerShape(Radius.tile), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                ListRow(
                    icon = Icons.Outlined.Lock,
                    title = stringResource(R.string.change_password),
                    subtitle = stringResource(R.string.change_password_hint),
                    onClick = {
                        viewModel.clearPasswordResult()
                        changingPassword = true
                    },
                    modifier = Modifier.padding(horizontal = Spacing.md),
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = AppTheme.colors.muted)
                }
            }

            SettingsSectionTitle(stringResource(R.string.app_font_size), stringResource(R.string.app_font_size_hint))
            OptionRow {
                AppTextSize.entries.forEach { size ->
                    OptionCard(
                        selected = selectedSize == size,
                        label = stringResource(size.labelRes),
                        onClick = { viewModel.setTextSize(size) },
                        minHeight = COMPACT_CARD_HEIGHT,
                    ) {
                        // "Aa" in the size the option gives the body text.
                        Text(
                            "Aa",
                            style = MaterialTheme.typography.titleLarge,
                            fontSize = MaterialTheme.typography.titleLarge.fontSize * (size.scale / selectedSize.scale),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Note(stringResource(R.string.app_font_size_note))

            SettingsSectionTitle(stringResource(R.string.app_language))
            OptionRow {
                AppLanguage.entries.forEach { lang ->
                    OptionCard(
                        selected = language == lang,
                        // Each language is named in itself, so it can be found whatever is shown now.
                        label = lang.nativeName,
                        onClick = { if (language != lang) context.findActivity()?.let { AppLocales.apply(it, lang) } },
                        minHeight = COMPACT_CARD_HEIGHT,
                    ) {
                        Text(lang.sample, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            SettingsSectionTitle(stringResource(R.string.app_theme))
            OptionRow {
                ThemeMode.entries.forEach { mode ->
                    OptionCard(
                        selected = theme == mode,
                        label = stringResource(if (mode == ThemeMode.LIGHT) R.string.theme_light else R.string.theme_dark),
                        onClick = { viewModel.setTheme(mode) },
                        minHeight = COMPACT_CARD_HEIGHT,
                    ) {
                        ThemeIcon(if (mode == ThemeMode.LIGHT) Icons.Outlined.LightMode else Icons.Outlined.DarkMode)
                    }
                }
            }
        }
    }

    if (changingPassword) {
        ChangePasswordDialog(
            wrongCurrent = passwordResult == PasswordChangeResult.WRONG_CURRENT,
            onSubmit = viewModel::changePassword,
            onEdit = viewModel::clearPasswordResult,
            onDismiss = { changingPassword = false },
        )
    }
    val changedMessage = stringResource(R.string.password_changed)
    LaunchedEffect(passwordResult) {
        if (passwordResult == PasswordChangeResult.CHANGED) {
            changingPassword = false
            viewModel.clearPasswordResult()
            Toast.makeText(context, changedMessage, Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = AppTheme.colors.muted,
        modifier = Modifier.padding(top = Spacing.md, start = Spacing.xs),
    )
}

@Composable
private fun ThemeIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
}

private val AppTextSize.labelRes: Int
    get() = when (this) {
        AppTextSize.SMALL -> R.string.app_font_size_small
        AppTextSize.MEDIUM -> R.string.app_font_size_medium
        AppTextSize.LARGE -> R.string.app_font_size_large
    }

private val AppLanguage.nativeName: String
    get() = when (this) {
        AppLanguage.ENGLISH -> "English"
        AppLanguage.CHINESE -> "中文"
    }

private val AppLanguage.sample: String
    get() = when (this) {
        AppLanguage.ENGLISH -> "A"
        AppLanguage.CHINESE -> "文"
    }

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private val COMPACT_CARD_HEIGHT = 128.dp
