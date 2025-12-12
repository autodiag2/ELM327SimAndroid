package com.example.btserial

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Bundle
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class MainActivity : Activity() {
    private val adapter = BluetoothAdapter.getDefaultAdapter()
    private val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var server: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GlobalScope.launch(Dispatchers.IO) {
            server = adapter.listenUsingRfcommWithServiceRecord("BTSerial", uuid)
            socket = server?.accept()
            input = socket?.inputStream
            output = socket?.outputStream
            val buffer = ByteArray(1024)
            while (true) {
                val n = input?.read(buffer) ?: break
                if (n > 0) output?.write(buffer, 0, n)
            }
        }
    }
}
