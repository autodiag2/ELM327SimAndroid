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
import com.autodiag.elm327emu.SettingsActivity
import androidx.appcompat.widget.Toolbar
import androidx.appcompat.app.ActionBarDrawerToggle

private const val REQUEST_CODE = 1

object libautodiag {
    init {
        System.loadLibrary("autodiag")
    }
    external fun launchEmu(): String
    external fun setTmpDir(path: String)
}

class MainActivity : AppCompatActivity() {
    private val adapter = BluetoothAdapter.getDefaultAdapter()
    private val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var server: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null
    private var bt_input: InputStream? = null
    private var bt_output: OutputStream? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var drawer: DrawerLayout
    private lateinit var logView: TextView
    private lateinit var toggle: ActionBarDrawerToggle

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            drawer.openDrawer(Gravity.LEFT)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        drawer = DrawerLayout(this)

        logView = TextView(this).apply {
            setPadding(16, 16, 16, 16)
            movementMethod = ScrollingMovementMethod()
        }

        val content = FrameLayout(this).apply {
            addView(logView)
        }

        val navView = NavigationView(this).apply {
            menu.add("Settings").setOnMenuItemClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                drawer.closeDrawer(Gravity.LEFT)
                true
            }
        }

        drawer.addView(content, DrawerLayout.LayoutParams(
            DrawerLayout.LayoutParams.MATCH_PARENT,
            DrawerLayout.LayoutParams.MATCH_PARENT
        ))
        drawer.addView(navView, DrawerLayout.LayoutParams(
            DrawerLayout.LayoutParams.WRAP_CONTENT,
            DrawerLayout.LayoutParams.MATCH_PARENT
        ).apply {
            gravity = Gravity.START
        })

        setContentView(drawer)

        // Add a toolbar to enable ActionBarDrawerToggle
        val toolbar = Toolbar(this)
        toolbar.title = "Bluetooth Server"
        drawer.addView(toolbar, DrawerLayout.LayoutParams(
            DrawerLayout.LayoutParams.MATCH_PARENT,
            dpToPx(56)
        ))

        setSupportActionBar(toolbar)

        toggle = ActionBarDrawerToggle(this, drawer, toolbar, R.string.open, R.string.close)
        drawer.addDrawerListener(toggle)
        toggle.syncState()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_CODE)
                return
            }
        }
        startBluetoothServer()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
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
            val layout = logView.layout
            if (layout != null) {
                val scroll = layout.getLineTop(logView.lineCount) - logView.height
                if (0 < scroll) logView.scrollTo(0, scroll) else logView.scrollTo(0, 0)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startBluetoothServer()
        }
    }
}