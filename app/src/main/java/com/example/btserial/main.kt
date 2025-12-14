package com.autodiag.elm327emu

import android.Manifest
import android.app.Activity
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

private const val REQUEST_CODE = 1

object libautodiag {
    init {
        System.loadLibrary("autodiag")
    }
    external fun launchEmu(): String
    external fun setTmpDir(path: String)
}

class MainActivity : Activity() {
    private val adapter = BluetoothAdapter.getDefaultAdapter()
    private val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var server: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null
    private var bt_input: InputStream? = null
    private var bt_output: OutputStream? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logView = TextView(this).apply {
            setPadding(16,16,16,16)
        }
        setContentView(logView)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_CODE)
                return
            }
        }
        startBluetoothServer()
    }

    private fun toStr(buffer: ByteArray, size: Int): String {
        val colSize = 20
        val result = StringBuilder()
        var byteI = 0

        while (byteI < size) {
            val hexCollector = StringBuilder()
            val asciiCollector = StringBuilder()
            var col = 0

            while (col < colSize && byteI < size) {
                val b = buffer[byteI].toInt() and 0xFF

                if (col > 0) hexCollector.append(' ')
                hexCollector.append(String.format("%02x", b))

                asciiCollector.append(
                    if (b in 0x20..0x7E) b.toChar() else '.'
                )

                col += 1
                byteI += 1
            }

            result.append(
                String.format(
                    "%59s | %20s\n",
                    hexCollector.toString(),
                    asciiCollector.toString()
                )
            )
        }

        return result.toString()
    }

    private fun startBluetoothServer() {
        scope.launch {
            while (true) {
                try {
                    server = adapter.listenUsingRfcommWithServiceRecord("BTSerial", uuid)
                    appendLog("Waiting for connection...")

                    socket = server?.accept()
                    appendLog("Client connected: ${socket?.remoteDevice?.address}")

                    bt_input = socket?.inputStream
                    bt_output = socket?.outputStream

                    val filesDirPath = filesDir.absolutePath
                    libautodiag.setTmpDir(filesDirPath)

                    val location = libautodiag.launchEmu()
                    appendLog("Native sim location: $location")

                    val loopbackSocket = LocalSocket()
                    loopbackSocket.connect(
                        LocalSocketAddress(location, LocalSocketAddress.Namespace.FILESYSTEM)
                    )

                    appendLog("Loopback socket connected")

                    val loopbackInput = loopbackSocket.inputStream
                    val loopbackOutput = loopbackSocket.outputStream

                    val bufferBT = ByteArray(1024)
                    val bufferLoop = ByteArray(1024)

                    val btToLoop = launch {
                        while (true) {
                            val n = bt_input?.read(bufferBT) ?: break
                            if (n <= 0) break
                            loopbackOutput.write(bufferBT, 0, n)
                            loopbackOutput.flush()
                            appendLog(" * Received from Bluetooth: (passing to loopback)")
                            appendLog(toStr(bufferBT, n))
                        }
                    }

                    val loopToBt = launch {
                        while (true) {
                            val n = loopbackInput.read(bufferLoop)
                            if (n <= 0) break
                            bt_output?.write(bufferLoop, 0, n)
                            bt_output?.flush()
                            appendLog(" * Sending the data received from loopback on bluetooth:")
                            appendLog(toStr(bufferLoop, n))
                        }
                    }

                    btToLoop.join()
                    loopToBt.cancelAndJoin()

                    loopbackInput.close()
                    loopbackOutput.close()
                    loopbackSocket.close()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    appendLog("Error: ${e.message}")
                } finally {
                    bt_input?.close()
                    bt_output?.close()
                    socket?.close()
                    server?.close()
                    appendLog("Bluetooth connection closed")
                }
            }
        }
    }


    private fun appendLog(text: String) {
        runOnUiThread {
            logView.append(text + "\n")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startBluetoothServer()
        }
    }
}