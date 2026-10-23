package com.smartglasses.app.feature.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartglasses.app.R
import com.smartglasses.app.core.ui.components.GlassesImage
import com.smartglasses.app.core.ui.components.PasswordField
import com.smartglasses.app.core.ui.components.PrimaryButton
import com.smartglasses.app.core.ui.theme.AppTheme
import com.smartglasses.app.core.ui.theme.Sizes
import com.smartglasses.app.core.ui.theme.Spacing
import com.smartglasses.app.data.security.UnlockResult
import kotlinx.coroutines.delay

/** Shown when the app starts, before any other screen. */
@Composable
fun LockScreen(viewModel: LockViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var password by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val unlock = { viewModel.unlock(password) }

    // Countdown while blocked after too many wrong passwords.
    val blocked = state.result as? UnlockResult.Blocked
    var secondsLeft by remember(blocked) { mutableIntStateOf(blocked?.secondsLeft ?: 0) }
    LaunchedEffect(blocked) {
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft--
        }
        if (blocked != null) viewModel.clearResult()
    }
    LaunchedEffect(state.result) {
        if (state.result is UnlockResult.Wrong) password = ""
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(TOP_SPACE))
        GlassesImage(Sizes.heroImageWidth)
        Spacer(Modifier.height(Spacing.xl))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(Spacing.sm))
        Text(
            stringResource(R.string.lock_prompt),
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xl))
        Column(Modifier.widthIn(max = FIELD_MAX_WIDTH)) {
            PasswordField(
                value = password,
                onValueChange = {
                    password = it
                    if (state.result is UnlockResult.Wrong) viewModel.clearResult()
                },
                placeholder = stringResource(R.string.lock_password_hint),
                isError = state.result is UnlockResult.Wrong,
                onImeAction = unlock,
                modifier = Modifier.focusRequester(focus),
            )
            val message = when (val r = state.result) {
                is UnlockResult.Wrong -> pluralStringResource(R.plurals.lock_wrong_password, r.attemptsLeft, r.attemptsLeft)
                is UnlockResult.Blocked -> pluralStringResource(R.plurals.lock_blocked, secondsLeft, secondsLeft)
                else -> null
            }
            if (message != null) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Spacing.sm, start = Spacing.xs),
                )
            }
            Spacer(Modifier.height(Spacing.xl))
            PrimaryButton(
                stringResource(R.string.action_unlock),
                onClick = unlock,
                enabled = password.isNotEmpty() && blocked == null,
                loading = state.checking,
            )
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

private val TOP_SPACE = 72.dp
private val FIELD_MAX_WIDTH = 420.dp
