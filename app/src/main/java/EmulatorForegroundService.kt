package com.github.autodiag2.elm327emu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class EmulatorForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.github.autodiag2.elm327emu.START"
        const val ACTION_STOP = "com.github.autodiag2.elm327emu.STOP"

        private const val CHANNEL_ID = "emulator_service"
        private const val NOTIFICATION_ID = 1001
    }

    inner class LocalBinder : Binder() {
        fun getService() = this@EmulatorForegroundService
    }

    private val binder = LocalBinder()

    var isRunning = false
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEmulator()
                return START_NOT_STICKY
            }
            ACTION_START, null -> startEmulator()
        }

        return START_STICKY
    }

    private fun startEmulator() {
        if (isRunning) return

        startForeground(NOTIFICATION_ID, buildNotification())
        isRunning = true

        // TODO: Start the emulator runtime here.
    }

    private fun stopEmulator() {
        if (!isRunning) {
            stopSelf()
            return
        }

        // TODO: Stop the emulator runtime and close its bridges.

        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, EmulatorForegroundService::class.java)
                .setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_content))
            .setContentIntent(openIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .addAction(
                0,
                getString(R.string.notification_action_stop),
                stopIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        if (isRunning) {
            // TODO: Stop the emulator runtime and close its bridges.
            isRunning = false
        }

        super.onDestroy()
    }
}