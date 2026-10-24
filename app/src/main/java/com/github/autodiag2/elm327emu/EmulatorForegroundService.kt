package com.github.autodiag2.elm327emu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.github.autodiag2.elm327emu.com.BridgeOrchestrator
import com.github.autodiag2.elm327emu.sim.Sim
import com.github.autodiag2.elm327emu.StatsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.github.autodiag2.elm327emu.log.*

class EmulatorForegroundService : Service(),
    BridgeOrchestrator.ClientConnectionListener {

    companion object {
        const val ACTION_START = "com.github.autodiag2.elm327emu.START"
        const val ACTION_STOP = "com.github.autodiag2.elm327emu.STOP"

        private const val CHANNEL_ID = "emulator_service"
        private const val NOTIFICATION_ID = 1001

        var activity: MainActivity? = null
    }

    lateinit var log: LogController
    lateinit var sim: Sim
    lateinit var bridgeOrchestrator: BridgeOrchestrator
    lateinit var stats: StatsController

    var clients: List<BridgeOrchestrator.ConnectedClient>? = null

    inner class LocalBinder : Binder() {
        fun getService() = this@EmulatorForegroundService
    }

    private val binder = LocalBinder()
    protected val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    lateinit var btManager: BluetoothManager
    lateinit var btAdapter: BluetoothAdapter

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return

            when (
                intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.ERROR
                )
            ) {
                BluetoothAdapter.STATE_ON -> {
                    log.append(getString(R.string.log_main_bluetooth_enabled), LogLevel.INFO)
                    bridgeOrchestrator.setupBluetoothBridge()
                    bridgeOrchestrator.setupBleBridge()
                }

                BluetoothAdapter.STATE_OFF -> {
                    // Bluetooth disabled
                }
            }
        }
    }

    private val pendingIntentFlags: Int
        get() = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }

    private var bluetoothReceiverRegistered = false

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        btAdapter = btManager.adapter

        log = LogController(this)
        sim = Sim(this)
        stats = StatsController()

        bridgeOrchestrator = BridgeOrchestrator(
            service = this,
            clientConnectionListener = this
        )

        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                bluetoothStateReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(bluetoothStateReceiver, filter)
        }

        bluetoothReceiverRegistered = true
    }

    override fun onClientConnectUpdate(
        clients: List<BridgeOrchestrator.ConnectedClient>
    ) {
        for (client in clients) {
            if (
                this.clients.orEmpty().none {
                    it.clientIdentifier == client.clientIdentifier
                }
            ) {
                sim.customSerialScreen.onClientConnected(
                    client.clientIdentifier
                )
            }
        }

        for (client in this.clients.orEmpty()) {
            if (
                clients.none {
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
        val simView = sim.view ?: return
        val clients = this.clients ?: return

        simView.updateConnectedClientsButton(clients)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> stopEmulator()
            ACTION_START -> startEmulator()
            null -> {}
        }

        return START_NOT_STICKY
    }

    fun startEmulator() {
        startForeground(NOTIFICATION_ID, buildNotification())

        scope.launch {
            bridgeOrchestrator.start()
        }
    }

    fun stopEmulator() {
        bridgeOrchestrator.stop()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        sim.ensureStopped()
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            pendingIntentFlags
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, EmulatorForegroundService::class.java)
                .setAction(ACTION_STOP),
            pendingIntentFlags
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
        if (bluetoothReceiverRegistered) {
            unregisterReceiver(bluetoothStateReceiver)
            bluetoothReceiverRegistered = false
        }

        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
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