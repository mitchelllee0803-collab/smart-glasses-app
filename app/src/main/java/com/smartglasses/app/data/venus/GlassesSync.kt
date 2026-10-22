package com.smartglasses.app.data.venus

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.smartglasses.app.MainActivity
import com.smartglasses.app.R
import com.smartglasses.app.data.translation.TranslationSessionManager
import com.smartglasses.app.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A page change the glasses asked for, waiting until the app is on screen to apply it. */
data class GlassesNavRequest(val id: Long, val action: GlassesAction, val atElapsedMs: Long)

/**
 * Keeps the phone in step with the glasses when the wearer uses the touchpad (guide §5.3, §5.7):
 * the glasses opening Translate opens the live page and listens; leaving it ends the session.
 * The phone -> glasses direction is in [VenusSubtitleSink] (it switches the glasses' view).
 */
@Singleton
class GlassesSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val venus: VenusClient,
    private val session: TranslationSessionManager,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _navigation = MutableStateFlow<GlassesNavRequest?>(null)
    /** Consumed by the navigation host while the app is visible ([consume]). */
    val navigation: StateFlow<GlassesNavRequest?> = _navigation.asStateFlow()

    private val _listenRequested = MutableStateFlow(false)
    /** The live page that is already open should start listening ([consumeListenRequest]). */
    val listenRequested: StateFlow<Boolean> = _listenRequested.asStateFlow()

    private val ids = AtomicLong()
    private val openSessionPages = AtomicInteger()
    @Volatile private var screenOn = true
    @Volatile private var uiVisible = false
    private var started = false

    /** Called once after VNSDK.init (constructing VenusClient earlier would crash the SDK). */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            venus.events.collect { event ->
                if (event is VenusEvent.Screen) screenOn = event.on
                val echo = event is VenusEvent.ViewChanged &&
                    GlassesViewSync.isEcho(event.view, venus.lastRequestedView, SystemClock.elapsedRealtime())
                when (GlassesViewSync.decide(event, openSessionPages.get() > 0, screenOn, echo)) {
                    GlassesAction.SHOW_TRANSLATION -> showTranslation()
                    GlassesAction.LEAVE_TRANSLATION -> leaveTranslation()
                    GlassesAction.NONE -> Unit
                }
            }
        }
    }

    private fun showTranslation() {
        Log.i(TAG, "glasses opened translation")
        val pageOpen = openSessionPages.get() > 0
        if (pageOpen && !session.state.value.listening) _listenRequested.value = true
        post(GlassesAction.SHOW_TRANSLATION)
        // Android does not let a background app open itself or start the microphone: ask the user.
        val alreadyTranslating = pageOpen && session.state.value.listening
        if (!uiVisible && !alreadyTranslating) notifyTapToStart()
    }

    private fun leaveTranslation() {
        Log.i(TAG, "glasses left translation")
        // Stops at once, also in the background; spoken phrases are still finished and saved.
        session.stopListening()
        _listenRequested.value = false
        cancelNotification()
        post(GlassesAction.LEAVE_TRANSLATION)
    }

    /** The latest request wins: leaving cancels a pending "show" and vice versa. */
    private fun post(action: GlassesAction) {
        _navigation.value = GlassesNavRequest(ids.incrementAndGet(), action, SystemClock.elapsedRealtime())
    }

    /** Applied or dropped by the navigation host. */
    fun consume(request: GlassesNavRequest) {
        _navigation.update { if (it?.id == request.id) null else it }
        if (request.action == GlassesAction.SHOW_TRANSLATION) cancelNotification()
    }

    /** A "show" request older than this is not applied when the app is opened much later. */
    fun isFresh(request: GlassesNavRequest): Boolean =
        request.action == GlassesAction.LEAVE_TRANSLATION || SystemClock.elapsedRealtime() - request.atElapsedMs <= SHOW_MAX_AGE_MS

    fun consumeListenRequest() {
        _listenRequested.value = false
    }

    fun onSessionPageOpened() {
        openSessionPages.incrementAndGet()
        _listenRequested.value = false // a new page starts listening by itself
    }

    fun onSessionPageClosed() {
        openSessionPages.decrementAndGet()
    }

    /** MainActivity is started (visible) or stopped. */
    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
    }

    private fun notifyTapToStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.notification_channel_glasses))
                .build(),
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_glasses)
            .setContentTitle(context.getString(R.string.notification_glasses_title))
            .setContentText(context.getString(R.string.notification_glasses_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(SHOW_MAX_AGE_MS)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun cancelNotification() = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    private companion object {
        const val TAG = "GlassesSync"
        const val CHANNEL_ID = "glasses_requests"
        const val NOTIFICATION_ID = 2
        const val SHOW_MAX_AGE_MS = 2 * 60 * 1000L
    }
}
