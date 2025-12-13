package com.autodiag.elm327emu

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket

private const val REQUEST_CODE = 1

class MainActivity : Activity() {
    private val adapter = BluetoothAdapter.getDefaultAdapter()
    private val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var server: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

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

    private fun startBluetoothServer() {
        GlobalScope.launch(Dispatchers.IO) {
            while(true) {
                try {
                    server = adapter.listenUsingRfcommWithServiceRecord("BTSerial", uuid)
                    appendLog("Waiting for connection...")
                    socket = server?.accept()
                    appendLog("Client connected: ${socket?.remoteDevice?.address}")

                    input = socket?.inputStream
                    output = socket?.outputStream
                    val buffer = ByteArray(1024)
                    while (true) {
                        val n = input?.read(buffer) ?: break
                        if (n > 0) {
                            val received = buffer.copyOf(n).toString(Charsets.UTF_8)
                            appendLog("Received: $received")
                            output?.write(buffer, 0, n)
                            output?.flush()
                            appendLog("Sent back: $received")
                        }
                    }
                } catch (e: Exception) {
                    appendLog("Error: ${e.message}")
                } finally {
                    input?.close()
                    output?.close()
                    socket?.close()
                    server?.close()
                    appendLog("Connection closed")
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
