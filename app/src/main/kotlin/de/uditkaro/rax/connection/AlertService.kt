package de.uditkaro.rax.connection

import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import de.uditkaro.rax.MainActivity
import de.uditkaro.rax.R

/**
 * Keeps Rax running while background alerts are on. Without a foreground service, Android cuts off
 * Rax's network access soon after it goes to the background.
 */
class AlertService : LifecycleService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, Alerts.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.service_title))
            .setContentText(getString(R.string.service_text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
        try {
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: IllegalStateException) {
            if (!startNotAllowed(e)) throw e
            // Started while Rax was in the background; it starts again the next time Rax opens.
            Log.w(TAG, "foreground start not allowed", e)
            stopSelf()
        }
        return START_STICKY
    }

    companion object {
        private const val TAG = "AlertService"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, AlertService::class.java))
            } catch (e: IllegalStateException) {
                if (!startNotAllowed(e)) throw e
                Log.w(TAG, "foreground start not allowed", e)
            }
        }

        /** Android 12 and later refuse to start a foreground service while the app is in the background. */
        private fun startNotAllowed(e: IllegalStateException) =
            Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException

        fun stop(context: Context) {
            context.stopService(Intent(context, AlertService::class.java))
        }
    }
}
