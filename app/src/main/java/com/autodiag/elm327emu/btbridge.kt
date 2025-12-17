package com.autodiag.elm327emu

import android.Manifest
import androidx.appcompat.app.AppCompatActivity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import kotlinx.coroutines.*
import java.io.*
import java.net.Socket
import java.util.UUID
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.autodiag.elm327emu.libautodiag
import android.util.Log
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.FrameLayout
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import android.view.MenuItem
import android.content.Intent
import com.autodiag.elm327emu.SimGeneratorGui
import androidx.appcompat.widget.Toolbar
import android.content.Context
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ListView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Button
import android.widget.CheckBox
import android.widget.ScrollView
import androidx.core.widget.addTextChangedListener
import com.autodiag.elm327emu.R
import android.view.ViewGroup.LayoutParams
import android.view.View
import android.content.SharedPreferences
import android.widget.*
import android.view.MotionEvent

class BluetoothBridge(
    private val activity: MainActivity,
    private val adapter: BluetoothAdapter
) {

    private val classicalBtUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var server: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null
    private var bt_input: InputStream? = null
    private var bt_output: OutputStream? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun appendLog(level: LogLevel, text: String) {
        activity.appendLog(level, text)
    }

    public fun start() {
        if (!adapter.isEnabled) {
            activity.showBluetoothEnablePopup()
            return
        }
        scope.launch {
            activity.clearSocketFiles()
            val isMultipleAdvertisementSupported = adapter.isMultipleAdvertisementSupported

            while (true) {
                try {

                    server = adapter.listenUsingRfcommWithServiceRecord("BTSerial", classicalBtUUID)
                    appendLog(LogLevel.INFO, "Waiting for connection...")

                    socket = server?.accept()
                    appendLog(LogLevel.INFO, "Client connected: ${socket?.remoteDevice?.address}")

                    bt_input = socket?.inputStream
                    bt_output = socket?.outputStream

                    val filesDirPath = activity.filesDir.absolutePath
                    val location = libautodiag.launchEmu(filesDirPath)
                    appendLog(LogLevel.DEBUG, "Native sim location: $location")

                    val loopbackSocket = LocalSocket()
                    loopbackSocket.connect(
                        LocalSocketAddress(location, LocalSocketAddress.Namespace.FILESYSTEM)
                    )

                    appendLog(LogLevel.DEBUG, "Loopback socket connected")

                    val loopbackInput = loopbackSocket.inputStream
                    val loopbackOutput = loopbackSocket.outputStream

                    val bufferBT = ByteArray(1024)
                    val bufferLoop = ByteArray(1024)

                    val btToLoop = launch {
                        while (isActive) {
                            try {
                                val n = bt_input?.read(bufferBT) ?: break
                                if (n <= 0) break
                                loopbackOutput.write(bufferBT, 0, n)
                                loopbackOutput.flush()
                                appendLog(LogLevel.DEBUG, " * Received from Bluetooth: (passing to loopback)")
                                appendLog(LogLevel.DEBUG, hexDump(bufferBT, n))
                            } catch(e: Exception) {
                                appendLog(LogLevel.DEBUG, "exiting btToLoop: ${e.message}")
                                break
                            }
                        }
                    }

                    val loopToBt = launch {
                        while (isActive) {
                            try {
                                val n = loopbackInput?.read(bufferLoop) ?: break
                                if (n <= 0) break
                                bt_output?.write(bufferLoop, 0, n)
                                bt_output?.flush()
                                appendLog(LogLevel.DEBUG, " * Sending the data received from loopback on bluetooth:")
                                appendLog(LogLevel.DEBUG, hexDump(bufferLoop, n))
                            } catch(e: Exception) {
                                appendLog(LogLevel.DEBUG, "exiting loopToBt: ${e.message}")
                                break
                            }
                        }
                    }

                    btToLoop.join()
                    loopToBt.cancel()

                    loopbackInput.close()
                    loopbackOutput.close()
                    loopbackSocket.close()
                } catch (e: CancellationException) {
                    appendLog(LogLevel.DEBUG, "Cancelled")
                    throw e
                } catch (e: Exception) {
                    appendLog(LogLevel.DEBUG, "Error: ${e.message}")
                } finally {
                    bt_input?.close()
                    bt_output?.close()
                    socket?.close()
                    server?.close()
                    appendLog(LogLevel.INFO, "Bluetooth connection closed")
                }
            }
        }
    }

    public fun stop() {
        try {
            bt_input?.close()
            bt_input = null

            bt_output?.close()
            bt_output = null

            socket?.close()
            socket = null

            server?.close()
            server = null
        } catch (_: Exception) {
            
        }

        scope.coroutineContext.cancelChildren()
    }
}