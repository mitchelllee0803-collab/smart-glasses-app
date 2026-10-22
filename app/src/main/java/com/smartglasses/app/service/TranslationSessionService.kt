package com.smartglasses.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.smartglasses.app.MainActivity
import com.smartglasses.app.R
import com.smartglasses.app.data.translation.TranslationSessionManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps a translation session running while the phone screen is off or the app is in the
 * background (glasses users keep the phone in a pocket). Microphone-type foreground service;
 * stops itself when the session ends.
 */
@AndroidEntryPoint
class TranslationSessionService : LifecycleService() {
    @Inject lateinit var session: TranslationSessionManager
    private var watching = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            session.stopListening()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        } catch (e: RuntimeException) {
            // Not allowed now (app in background, or microphone permission missing): the session
            // still runs while the app is visible.
            Log.w(TAG, "could not start in foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!watching) {
            watching = true
            lifecycleScope.launch {
                val active = session.state.map { it.listening || it.finishing }.distinctUntilChanged()
                // The session starts asynchronously; give it a moment to report that it runs.
                if (withTimeoutOrNull(START_GRACE_MS) { active.first { it } } != null) {
                    // A microphone switch restarts the session: stay up across the short gap.
                    do {
                        active.first { !it }
                    } while (withTimeoutOrNull(RESTART_GRACE_MS) { active.first { it } } != null)
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.notification_channel_session))
                .build(),
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TranslationSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_glasses)
            .setContentTitle(getString(R.string.notification_session_title))
            .setContentText(getString(R.string.notification_session_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "TranslationService"
        private const val CHANNEL_ID = "translation_session"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.smartglasses.app.action.STOP_LISTENING"
        private const val START_GRACE_MS = 5_000L
        private const val RESTART_GRACE_MS = 2_000L

        /** Call while the app is visible (Android only allows starting it then). */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, TranslationSessionService::class.java)) }
                .onFailure { Log.w(TAG, "service not started", it) }
        }
    }
}
