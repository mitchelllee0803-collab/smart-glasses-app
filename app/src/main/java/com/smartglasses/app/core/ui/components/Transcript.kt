package com.smartglasses.app.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.smartglasses.app.R
import com.smartglasses.app.core.ui.theme.AppTheme
import com.smartglasses.app.core.ui.theme.Spacing
import com.smartglasses.app.domain.model.TranscriptLine

/**
 * Conversation on a grey background, one bubble per phrase. Bubbles size to their text like
 * chat messages: short phrases stay narrow, long ones wrap up to [BUBBLE_MAX_WIDTH_FRACTION].
 */
@Composable
fun TranscriptList(lines: List<TranscriptLine>, modifier: Modifier = Modifier, followNewest: Boolean = true) {
    val listState = rememberLazyListState()
    if (followNewest) {
        val last = lines.lastOrNull()
        LaunchedEffect(lines.size, last?.original, last?.translation) {
            if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().background(AppTheme.colors.chatBackground),
        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(lines, key = { it.id }) { line ->
            Box(Modifier.fillMaxWidth(BUBBLE_MAX_WIDTH_FRACTION)) { TranscriptBubble(line) }
        }
    }
}

@Composable
fun TranscriptBubble(line: TranscriptLine, modifier: Modifier = Modifier) {
    val live = !line.isFinal
    Surface(
        // Telegram-style: the corner at the speaker's side is less rounded.
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 6.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 0.5.dp,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
            Text(
                line.original.ifBlank { stringResource(R.string.line_recognizing) },
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = if (live) FontStyle.Italic else FontStyle.Normal,
                color = if (live) AppTheme.colors.muted else MaterialTheme.colorScheme.onSurface,
            )
            when {
                line.translation.isNotBlank() -> Text(
                    line.translation,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = if (live) LIVE_ALPHA else 1f),
                    modifier = Modifier.padding(top = Spacing.xs),
                )
                line.translating && line.original.isNotBlank() -> Text(
                    stringResource(R.string.line_translating),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.muted,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
        }
    }
}

private const val BUBBLE_MAX_WIDTH_FRACTION = 0.85f
private const val LIVE_ALPHA = 0.7f
