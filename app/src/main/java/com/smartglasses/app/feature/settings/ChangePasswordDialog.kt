package com.smartglasses.app.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.smartglasses.app.R
import com.smartglasses.app.core.ui.components.PasswordField
import com.smartglasses.app.core.ui.components.PrimaryButton
import com.smartglasses.app.core.ui.theme.Spacing
import com.smartglasses.app.data.security.AppLock

/** Current password, new password, confirmation. [wrongCurrent] comes back from the check. */
@Composable
fun ChangePasswordDialog(
    wrongCurrent: Boolean,
    onSubmit: (current: String, new: String) -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    var current by rememberSaveable { mutableStateOf("") }
    var new by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }

    val tooShort = new.length < AppLock.MIN_LENGTH
    val mismatch = confirm != new
    val submit = {
        submitted = true
        if (current.isNotEmpty() && !tooShort && !mismatch) onSubmit(current, new)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.change_password), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Spacing.xl))
                PasswordField(
                    value = current,
                    onValueChange = { current = it; onEdit() },
                    placeholder = stringResource(R.string.password_current),
                    isError = wrongCurrent,
                    imeAction = ImeAction.Next,
                )
                if (wrongCurrent) ErrorLine(stringResource(R.string.password_current_wrong))
                Spacer(Modifier.height(Spacing.md))
                PasswordField(
                    value = new,
                    onValueChange = { new = it },
                    placeholder = stringResource(R.string.password_new),
                    isError = submitted && tooShort,
                    imeAction = ImeAction.Next,
                )
                if (submitted && tooShort) ErrorLine(stringResource(R.string.password_too_short, AppLock.MIN_LENGTH))
                Spacer(Modifier.height(Spacing.md))
                PasswordField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    placeholder = stringResource(R.string.password_confirm),
                    isError = submitted && mismatch,
                    onImeAction = submit,
                )
                if (submitted && mismatch) ErrorLine(stringResource(R.string.password_mismatch))
                Spacer(Modifier.height(Spacing.xl))
                PrimaryButton(stringResource(R.string.action_save), onClick = submit, enabled = current.isNotEmpty() && new.isNotEmpty())
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
}

@Composable
private fun ErrorLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs, start = Spacing.xs),
    )
}
