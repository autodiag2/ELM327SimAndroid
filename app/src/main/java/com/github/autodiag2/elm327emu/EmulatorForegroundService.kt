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
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import com.github.autodiag2.elm327emu.com.BridgeOrchestrator
import com.github.autodiag2.elm327emu.sim.Sim
import com.github.autodiag2.elm327emu.StatsController
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.util.Log

class EmulatorForegroundService : Service(), BridgeOrchestrator.ClientConnectionListener {

    companion object {
        const val ACTION_START = "com.github.autodiag2.elm327emu.START"
        const val ACTION_STOP = "com.github.autodiag2.elm327emu.STOP"

        private const val CHANNEL_ID = "emulator_service"
        private const val NOTIFICATION_ID = 1001
    }

    lateinit var log: LogController
    lateinit var sim: Sim
    lateinit var bridgeOrchestrator: BridgeOrchestrator
    lateinit var stats: StatsController
    public var clients: List<BridgeOrchestrator.ConnectedClient>? = null

    inner class LocalBinder : Binder() {
        fun getService() = this@EmulatorForegroundService
    }

    private val binder = LocalBinder()
    protected val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    lateinit var btManager: BluetoothManager
    lateinit var btAdapter: BluetoothAdapter

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        btAdapter = btManager.adapter
        log = LogController(this)
        sim = Sim(this)
        stats = StatsController()
        bridgeOrchestrator = BridgeOrchestrator(service = this, clientConnectionListener = this)
    }

    override fun onClientConnectUpdate(
        clients: List<BridgeOrchestrator.ConnectedClient>
    ) {
        for (client in clients) {
            if (this.clients.orEmpty().none {
                    it.clientIdentifier == client.clientIdentifier
                } == true
            ) {
                sim.customSerialScreen.onClientConnected(
                    client.clientIdentifier
                )
            }
        }

        for (client in this.clients.orEmpty()) {
            if (clients.none {
                    it.clientIdentifier == client.clientIdentifier
                }
            ) {
                sim.customSerialScreen.onClientDisconnected(
                    client.clientIdentifier
                )
            }
        }
        this.clients = clients
        refreshClientsConnected()
    }

    fun refreshClientsConnected() {
        val simView = sim.view
        if ( simView == null ) {
            return
        }
        val clients = this.clients
        if ( clients == null ) {
            return
        }
        simView.updateConnectedClientsButton(clients)
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
            }
            ACTION_START -> {
                startEmulator()
            }
            null -> {

            }
        }

        return START_NOT_STICKY
    }

    public fun startEmulator() {
        startForeground(NOTIFICATION_ID, buildNotification())

        scope.launch {
            bridgeOrchestrator.start()
        }
    }

    public fun stopEmulator() {
        bridgeOrchestrator.stop()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
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
        super.onDestroy()
    }

    fun onDataReceived(data: ByteArray, size_used: Int) {
        log.logData(LogEntryType.RECV, data, size_used)
        stats.onDataReceived(data, size_used)
    }

    fun onDataSent(data: ByteArray, size_used: Int) {
        log.logData(LogEntryType.SENT, data, size_used)
        stats.onDataSent(data, size_used)
    }

    fun clearSocketFiles() {
        filesDir.listFiles()?.forEach {
            if (it.name.startsWith("socket")) it.delete()
        }
    }

}