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
import com.autodiag.elm327emu.libautodiag

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
        startSimulation()
    }

    private fun startSimulation() {
        scope.launch {
            try {
                
                val filesDirPath = filesDir.absolutePath
                appendLog(filesDirPath)
                libautodiag.setTmpDir(filesDirPath)

                val location = libautodiag.launchEmu()
                appendLog("Native sim location: $location")

                val port = location.substringAfter("127.0.0.1:").toInt()

                appendLog("Connecting to loopback port $port")

                val loopbackSocket = Socket("127.0.0.1", port)
                val loopbackInput = loopbackSocket.getInputStream()
                val loopbackOutput = loopbackSocket.getOutputStream()

                val buffer = ByteArray(1024)

                // Launch coroutine to forward BT -> Loopback
                val jobBTtoLoopback = launch {
                    while (true) {
                        val n = bt_input?.read(buffer) ?: break
                        if (n <= 0) break
                        loopbackOutput.write(buffer, 0, n)
                        loopbackOutput.flush()
                        appendLog("BT->Loopback sent ${n} bytes")
                    }
                }

                // Launch coroutine to forward Loopback -> BT
                val jobLoopbackToBT = launch {
                    while (true) {
                        val n = loopbackInput.read(buffer)
                        if (n <= 0) break
                        bt_output?.write(buffer, 0, n)
                        bt_output?.flush()
                        appendLog("Loopback->BT sent ${n} bytes")
                    }
                }

                jobBTtoLoopback.join()
                jobLoopbackToBT.cancelAndJoin()

                loopbackInput.close()
                loopbackOutput.close()
                loopbackSocket.close()
                appendLog("Loopback connection closed")

            } catch (e: Exception) {
                appendLog("Error: ${e.message}")
            }
        }
    }

    private fun startBluetoothServer() {
        GlobalScope.launch(Dispatchers.IO) {
            while(true) {
                try {
                    server = adapter.listenUsingRfcommWithServiceRecord("BTSerial", uuid)
                    appendLog("Waiting for connection...")
                    socket = server?.accept()
                    appendLog("Client connected: ${socket?.remoteDevice?.address}")

                    bt_input = socket?.inputStream
                    bt_output = socket?.outputStream

                    startSimulation() // Start sim only after BT connection established

                    // This loop now only monitors connection until closed
                    val buffer = ByteArray(1024)
                    while (true) {
                        val n = bt_input?.read(buffer) ?: break
                        if (n <= 0) break
                    }
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