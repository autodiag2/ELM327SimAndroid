package com.github.autodiag2.elm327emu

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
import com.github.autodiag2.elm327emu.libautodiag
import android.util.Log
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.FrameLayout
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import android.view.MenuItem
import android.content.Intent
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
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.BluetoothBridge
import android.view.ViewGroup.LayoutParams
import android.view.View
import android.content.SharedPreferences
import android.widget.*
import android.view.MotionEvent
import android.bluetooth.*
import android.bluetooth.le.*
import android.os.ParcelUuid
import java.io.InputStream
import java.io.OutputStream
import android.bluetooth.BluetoothManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.ServerSocket

class NetworkBridge(
    private val activity: MainActivity,
    private val basePort: Int = 35000
) {

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var netInput: InputStream? = null
    private var netOutput: OutputStream? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun appendLog(text: String, level: LogLevel = LogLevel.DEBUG) {
        activity.appendLog(text, level)
    }

    private fun openServer(): ServerSocket {
        var port = basePort
        while (true) {
            try {
                return ServerSocket(port).also {
                    appendLog(getString(R.string.log_network_server_listening, port), LogLevel.INFO)
                }
            } catch (_: IOException) {
                port++
            }
        }
    }

    fun getString(resId: Int, vararg formatArgs: Any?): String {
        return activity.getString(resId, *formatArgs.map { it ?: "" }.toTypedArray())
    }

    fun start() {
        scope.launch {
            activity.clearSocketFiles()

            while (true) {
                try {
                    serverSocket = openServer()
                    clientSocket = serverSocket!!.accept()
                    appendLog(
                        getString(R.string.log_network_client_connected, clientSocket!!.inetAddress.hostAddress, clientSocket!!.port),
                        LogLevel.INFO
                    )

                    netInput = clientSocket!!.getInputStream()
                    netOutput = clientSocket!!.getOutputStream()

                    val filesDirPath = activity.filesDir.absolutePath
                    val location = libautodiag.launchEmu(filesDirPath)
                    appendLog(getString(R.string.log_network_native_sim_location, location), LogLevel.DEBUG)

                    val loopbackSocket = LocalSocket()
                    loopbackSocket.connect(
                        LocalSocketAddress(location, LocalSocketAddress.Namespace.FILESYSTEM)
                    )
                    appendLog(getString(R.string.log_network_loopback_connected), LogLevel.DEBUG)

                    val loopbackInput = loopbackSocket.inputStream
                    val loopbackOutput = loopbackSocket.outputStream

                    val bufferNet = ByteArray(1024)
                    val bufferLoop = ByteArray(1024)

                    val netToLoop = launch {
                        while (isActive) {
                            try {
                                val n = netInput?.read(bufferNet) ?: break
                                if (n <= 0) break
                                loopbackOutput.write(bufferNet, 0, n)
                                loopbackOutput.flush()
                                activity.onDataReceived(bufferNet, n)
                            } catch (e: Exception) {
                                appendLog(getString(R.string.log_network_netToLoop_failed, e.message), LogLevel.DEBUG)
                                break
                            }
                        }
                    }

                    val loopToNet = launch {
                        while (isActive) {
                            try {
                                val n = loopbackInput?.read(bufferLoop) ?: break
                                if (n <= 0) break
                                netOutput?.write(bufferLoop, 0, n)
                                netOutput?.flush()
                                activity.onDataSent(bufferLoop, n)
                            } catch (e: Exception) {
                                appendLog(getString(R.string.log_network_loopToNet_failed, e.message), LogLevel.DEBUG)
                                break
                            }
                        }
                    }

                    netToLoop.join()
                    loopToNet.cancel()

                    loopbackInput.close()
                    loopbackOutput.close()
                    loopbackSocket.close()

                } catch (e: CancellationException) {
                    appendLog(getString(R.string.log_network_cancelled), LogLevel.DEBUG)
                    throw e
                } catch (e: Exception) {
                    appendLog(getString(R.string.log_network_error, e.message), LogLevel.DEBUG)
                } finally {
                    netInput?.close()
                    netOutput?.close()
                    clientSocket?.close()
                    serverSocket?.close()

                    netInput = null
                    netOutput = null
                    clientSocket = null
                    serverSocket = null

                    appendLog(getString(R.string.log_network_connection_closed), LogLevel.INFO)
                }
            }
        }
    }

    fun stop() {
        try {
            netInput?.close()
            netOutput?.close()
            clientSocket?.close()
            serverSocket?.close()
        } catch (_: Exception) {
        }

        netInput = null
        netOutput = null
        clientSocket = null
        serverSocket = null

        scope.coroutineContext.cancelChildren()
    }
}