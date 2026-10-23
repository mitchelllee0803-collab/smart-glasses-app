package com.smartglasses.app.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.smartglasses.app.core.ui.theme.AppTheme
import com.smartglasses.app.core.ui.theme.Radius
import com.smartglasses.app.core.ui.theme.Spacing

/* Settings pages: a heading per setting and selectable cards side by side (Translation Settings design). */

@Composable
fun SettingsSectionTitle(title: String, hint: String? = null) {
    Text(
        buildAnnotatedString {
            append(title)
            if (hint != null) {
                withStyle(MaterialTheme.typography.bodyMedium.toSpanStyle().copy(color = AppTheme.colors.muted)) { append(hint) }
            }
        },
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = Spacing.xl, bottom = Spacing.md, start = Spacing.xs),
    )
}

/** Options side by side; exactly one is selected. */
@Composable
fun OptionRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Spacing.md), content = content)
}

/** One choice: a picture above its label, outlined in blue when selected. */
@Composable
fun RowScope.OptionCard(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    minHeight: Dp = OPTION_CARD_MIN_HEIGHT,
    visual: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(Radius.tile),
        color = MaterialTheme.colorScheme.surface,
        border = if (selected) BorderStroke(SELECTED_BORDER, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.weight(1f).heightIn(min = minHeight).semantics { role = Role.RadioButton },
    ) {
        Column(
            Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            visual()
            Spacer(Modifier.height(Spacing.lg))
            Text(label, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        }
    }
}

val OPTION_CARD_MIN_HEIGHT = 168.dp
private val SELECTED_BORDER = 2.dp
