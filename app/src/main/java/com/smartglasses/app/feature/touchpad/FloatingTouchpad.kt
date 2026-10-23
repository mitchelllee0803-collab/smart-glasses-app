package com.smartglasses.app.feature.touchpad

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.smartglasses.app.R
import com.smartglasses.app.core.ui.theme.AppTheme
import com.smartglasses.app.core.ui.theme.Radius
import com.smartglasses.app.core.ui.theme.Spacing
import com.smartglasses.app.data.venus.TouchGesture
import com.smartglasses.app.data.venus.TouchpadGestures
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * The glasses' temple touchpad on the phone, floating above every page. The grey bar moves it;
 * taps, double taps, long presses and swipes on the dotted area go to the glasses. Without
 * pointer input of its own, the full-size layer lets touches beside the panel reach the page.
 */
@Composable
fun FloatingTouchpad(onGesture: (TouchGesture) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(Spacing.lg)) {
        val density = LocalDensity.current
        val maxX = with(density) { (maxWidth - PANEL_WIDTH).toPx() }.coerceAtLeast(0f)
        val maxY = with(density) { (maxHeight - PANEL_HEIGHT).toPx() }.coerceAtLeast(0f)
        // Distance from the bottom-right corner, so the default spot works on any screen size.
        var fromRight by rememberSaveable { mutableFloatStateOf(0f) }
        var fromBottom by rememberSaveable { mutableFloatStateOf(with(density) { DEFAULT_BOTTOM.toPx() }) }

        Surface(
            shape = RoundedCornerShape(Radius.tile),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 6.dp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset { IntOffset(-fromRight.coerceIn(0f, maxX).roundToInt(), -fromBottom.coerceIn(0f, maxY).roundToInt()) }
                .width(PANEL_WIDTH),
        ) {
            Column {
                Box(
                    Modifier.fillMaxWidth().height(HANDLE_HEIGHT)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = HANDLE_ALPHA))
                        .pointerInput(maxX, maxY) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                fromRight = (fromRight.coerceIn(0f, maxX) - drag.x).coerceIn(0f, maxX)
                                fromBottom = (fromBottom.coerceIn(0f, maxY) - drag.y).coerceIn(0f, maxY)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(width = 28.dp, height = 4.dp)
                            .background(AppTheme.colors.muted, RoundedCornerShape(2.dp)),
                    )
                }
                TouchSurface(onGesture, Modifier.fillMaxWidth().height(PANEL_HEIGHT - HANDLE_HEIGHT))
            }
        }
    }
}

/** The dotted area. Each gesture briefly lights it up and names it: the glasses never confirm. */
@Composable
private fun TouchSurface(onGesture: (TouchGesture) -> Unit, modifier: Modifier = Modifier) {
    val currentOnGesture by rememberUpdatedState(onGesture)
    var shown by remember { mutableStateOf<TouchGesture?>(null) }
    var count by remember { mutableIntStateOf(0) }
    val flash = remember { Animatable(0f) }
    val swipeDistance = with(LocalDensity.current) { SWIPE_DISTANCE.toPx() }

    fun recognised(gesture: TouchGesture) {
        currentOnGesture(gesture)
        shown = gesture
        count++
    }

    LaunchedEffect(count) {
        if (count == 0) return@LaunchedEffect
        flash.snapTo(1f)
        flash.animateTo(0f, tween(FEEDBACK_MS))
        delay(FEEDBACK_MS / 2L)
        shown = null
    }

    val labels = TouchGesture.entries.associateWith { stringResource(it.labelRes) }
    val description = stringResource(R.string.cd_touchpad)
    val dot = AppTheme.colors.muted.copy(alpha = DOT_ALPHA)
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .background(primary.copy(alpha = FLASH_ALPHA * flash.value))
            .pointerInput(swipeDistance) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val press = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { followPress(down.id, swipeDistance) }
                    when (press) {
                        null -> {
                            recognised(TouchGesture.LONG_PRESS)
                            consumeUntilUp()
                        }
                        is Press.Swiped -> {
                            recognised(press.gesture)
                            consumeUntilUp()
                        }
                        Press.Released -> {
                            val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown() }
                            if (second == null) {
                                recognised(TouchGesture.TAP)
                            } else {
                                second.consume()
                                consumeUntilUp()
                                recognised(TouchGesture.DOUBLE_TAP)
                            }
                        }
                    }
                }
            }
            .semantics {
                contentDescription = description
                // TalkBack users pick the gesture from the actions menu.
                customActions = TouchGesture.entries.map { gesture ->
                    CustomAccessibilityAction(labels.getValue(gesture)) { recognised(gesture); true }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val step = DOT_SPACING.toPx()
            val radius = DOT_RADIUS.toPx()
            var y = step / 2
            while (y < size.height) {
                var x = step / 2
                while (x < size.width) {
                    drawCircle(dot, radius, Offset(x, y))
                    x += step
                }
                y += step
            }
        }
        shown?.let {
            Text(
                labels.getValue(it),
                style = MaterialTheme.typography.labelLarge,
                color = primary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(Radius.button))
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            )
        }
    }
}

private sealed interface Press {
    data object Released : Press
    data class Swiped(val gesture: TouchGesture) : Press
}

/** Follows the pressed finger until it lifts or has moved far enough to be a swipe. */
private suspend fun AwaitPointerEventScope.followPress(id: PointerId, swipeDistance: Float): Press {
    var moved = Offset.Zero
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: return Press.Released
        moved += change.positionChange()
        change.consume()
        if (!change.pressed) return Press.Released
        TouchpadGestures.swipe(moved.x, moved.y, swipeDistance)?.let { return Press.Swiped(it) }
    }
}

/** The rest of a gesture already sent: nothing more happens until every finger is up. */
private suspend fun AwaitPointerEventScope.consumeUntilUp() {
    while (currentEvent.changes.any { it.pressed }) {
        awaitPointerEvent().changes.forEach { it.consume() }
    }
}

private val TouchGesture.labelRes: Int
    get() = when (this) {
        TouchGesture.TAP -> R.string.touchpad_tap
        TouchGesture.DOUBLE_TAP -> R.string.touchpad_double_tap
        TouchGesture.LONG_PRESS -> R.string.touchpad_long_press
        TouchGesture.FORWARD -> R.string.touchpad_forward
        TouchGesture.BACK -> R.string.touchpad_back
    }

private val PANEL_WIDTH = 104.dp
private val PANEL_HEIGHT = 176.dp
private val HANDLE_HEIGHT = 24.dp
/** Above the bottom buttons of the pages (Start Translation, the microphone). */
private val DEFAULT_BOTTOM = 96.dp
private val SWIPE_DISTANCE = 32.dp
private val DOT_SPACING = 8.dp
private val DOT_RADIUS = 1.dp
private const val DOT_ALPHA = 0.7f
private const val HANDLE_ALPHA = 0.08f
private const val FLASH_ALPHA = 0.18f
private const val FEEDBACK_MS = 700
