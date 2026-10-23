package com.smartglasses.app.feature.glasses

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartglasses.app.R
import com.smartglasses.app.core.ui.theme.AppTheme
import com.smartglasses.app.core.ui.theme.Radius
import com.smartglasses.app.core.ui.theme.Spacing

/** Page frame of the glasses pages: back arrow and centred title, like Settings. */
@Composable
internal fun GlassesPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxWidth().statusBarsPadding().padding(vertical = Spacing.xs), contentAlignment = Alignment.Center) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = Spacing.sm)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = Spacing.lg).padding(top = Spacing.sm, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            content = content,
        )
    }
}

/** White rounded card for [DetailRow]s; put a [RowDivider] between them. */
@Composable
internal fun RowsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(Radius.tile), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(content = content)
    }
}

@Composable
internal fun RowDivider() {
    HorizontalDivider(Modifier.padding(horizontal = Spacing.lg), color = MaterialTheme.colorScheme.surfaceVariant)
}

/** Label on the left; [value] on the right, or a chevron when the row opens something. */
@Composable
internal fun DetailRow(label: String, value: String? = null, onClick: (() -> Unit)? = null, enabled: Boolean = true) {
    val muted = AppTheme.colors.muted
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .let { if (onClick != null && enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else muted,
            modifier = Modifier.weight(1f, fill = value == null),
        )
        if (value != null) {
            Spacer(Modifier.width(Spacing.lg))
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = muted)
    }
}

@Composable
internal fun PageNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = AppTheme.colors.muted,
        modifier = Modifier.padding(horizontal = Spacing.xs),
    )
}
