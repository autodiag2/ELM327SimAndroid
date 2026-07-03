package com.github.autodiag2.elm327emu.com

import kotlinx.coroutines.*
import java.io.*
import java.util.UUID
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.LogLevel
import com.github.autodiag2.elm327emu.MainActivity

class BluetoothBridge(
    private val activity: MainActivity,
    private val btAdapter: BluetoothAdapter
) : Bridge(activity) {

    private val classicalBtUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var server: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null
    private var bt_input: InputStream? = null
    private var bt_output: OutputStream? = null

    override suspend fun startInternal() {
        try {

            appendLog(getString(R.string.log_bt_waiting_for_connection), LogLevel.INFO)

            socket = server?.accept()
            appendLog(getString(R.string.log_bt_client_connected, socket?.remoteDevice?.address),
                LogLevel.INFO
            )

            bt_input = socket?.inputStream
            bt_output = socket?.outputStream

            emuStart()

            val bufferBT = ByteArray(1024)
            val bufferLoop = ByteArray(1024)

            val btToLoop = scope.launch {
                while (isActive) {
                    try {
                        val n = emuRecv(bufferBT)
                        if (n <= 0) break
                        emuSend(bufferBT, n)
                        activity.onDataReceived(bufferBT, n)
                    } catch(e: Exception) {
                        appendLog(getString(R.string.log_bt_btToLoop_failed, e.message),
                            LogLevel.DEBUG
                        )
                        break
                    }
                }
            }

            val loopToBt = scope.launch {
                while (isActive) {
                    try {
                        val n = loopbackInput?.read(bufferLoop) ?: break
                        if (n <= 0) break
                        bt_output?.write(bufferLoop, 0, n)
                        bt_output?.flush()
                        activity.onDataSent(bufferLoop, n)
                    } catch(e: Exception) {
                        appendLog(getString(R.string.log_bt_loopToBt_failed, e.message),
                            LogLevel.DEBUG
                        )
                        break
                    }
                }
            }

            btToLoop.join()
            loopToBt.cancel()

            emuStop()
        } catch (e: CancellationException) {
            appendLog(getString(R.string.log_bt_cancelled), LogLevel.DEBUG)
            throw e
        } catch (e: Exception) {
            appendLog(getString(R.string.log_bt_error, e.message), LogLevel.DEBUG)
        } finally {
            bt_input?.close()
            bt_output?.close()
            socket?.close()
            appendLog(getString(R.string.log_bt_connection_closed), LogLevel.INFO)
        }
    }

    override fun start() {
        if (!btAdapter.isEnabled) {
            activity.showBluetoothEnablePopup()
            return
        }
        server = btAdapter.listenUsingRfcommWithServiceRecord(getString(R.string.app_name), classicalBtUUID)
        super.start()
    }

    override fun stop() {
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

        super.stop()
    }
}