package com.waide.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that keeps the CPU and network alive while an answer is being
 * generated, so it keeps going when the phone is locked, asleep, or you switch apps.
 * Reference-counted so local and server generations can overlap safely: the service
 * runs while at least one generation is active and stops when the last one ends.
 */
class GenerationService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("w-Ai-de is working")
            .setContentText("Generating your answer. You can lock the phone or leave the app.")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        ServiceCompat.startForeground(
            this, NOTIF_ID, n,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "waide:generation").apply {
                setReferenceCounted(false)
                acquire(30 * 60 * 1000L) // safety cap; end() releases it normally
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "waide_gen"
        private const val NOTIF_ID = 42
        private val active = AtomicInteger(0)

        /** Call when a generation starts. Starts the service on the first one. */
        fun begin(ctx: Context) {
            if (active.incrementAndGet() == 1) {
                runCatching {
                    ContextCompat.startForegroundService(
                        ctx.applicationContext, Intent(ctx.applicationContext, GenerationService::class.java)
                    )
                }
            }
        }

        /** Call when a generation ends. Stops the service when the last one finishes. */
        fun end(ctx: Context) {
            if (active.decrementAndGet() <= 0) {
                active.set(0)
                runCatching {
                    ctx.applicationContext.stopService(
                        Intent(ctx.applicationContext, GenerationService::class.java)
                    )
                }
            }
        }

        private fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(CHANNEL, "Generation", NotificationManager.IMPORTANCE_LOW)
                ch.description = "Shown while w-Ai-de is generating an answer"
                nm.createNotificationChannel(ch)
            }
        }
    }
}
