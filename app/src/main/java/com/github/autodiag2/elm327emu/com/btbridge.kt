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
import kotlinx.coroutines.channels.Channel
import com.github.autodiag2.elm327emu.sim.EmuInterface
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

class BluetoothBridge(
    emu: EmuInterface,
    scope: CoroutineScope,
    activity: MainActivity,
    listener: Bridge.Listener? = null
) : Bridge(emu = emu, scope = scope, activity = activity, LOG_TAG = "BT SPP", listener = listener) {

    private val classicalBtUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val requestQueue = Channel<ByteArray>(Channel.UNLIMITED)

    private var secureServer: BluetoothServerSocket? = null
    private var insecureServer: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null
    private var bt_input: InputStream? = null
    private var bt_output: OutputStream? = null

    override suspend fun accept() {
        appendLog(
            getString(R.string.log_bt_waiting_for_connection),
            LogLevel.INFO
        )

        try {
            coroutineScope {
                val secureAccept = async(Dispatchers.IO) {
                    secureServer?.accept()
                }

                val insecureAccept = async(Dispatchers.IO) {
                    insecureServer?.accept()
                }

                val acceptedSocket = select<BluetoothSocket?> {
                    secureAccept.onAwait { socket ->
                        insecureAccept.cancel()
                        socket
                    }

                    insecureAccept.onAwait { socket ->
                        secureAccept.cancel()
                        socket
                    }
                }

                if (acceptedSocket == null) {
                    return@coroutineScope
                }

                socket = acceptedSocket

                if (socket == null) {
                    return@coroutineScope
                }

                var clientIdentifier = ""

                try {
                    clientIdentifier = acceptedSocket.remoteDevice.address

                    appendLog(
                        getString(
                            R.string.log_bt_client_connected,
                            clientIdentifier
                        ),
                        LogLevel.INFO
                    )

                    listener?.onClientConnect(clientIdentifier, this@BluetoothBridge)

                    val input = acceptedSocket.inputStream
                    val output = acceptedSocket.outputStream

                    bt_input = input
                    bt_output = output

                    val reader = launch(Dispatchers.IO) {
                        try {
                            while (isActive) {
                                val bufferBT = ByteArray(1024)
                                val n = input.read(bufferBT)

                                if (n <= 0) break

                                requestQueue.send(bufferBT.copyOf(n))
                            }
                        } catch (e: Exception) {
                            appendLog(
                                getString(
                                    R.string.log_bt_btToLoop_failed,
                                    e.message
                                ),
                                LogLevel.DEBUG
                            )
                        }
                    }

                    val worker = launch(Dispatchers.IO) {
                        val bufferLoop = ByteArray(1024)

                        try {
                            while (isActive) {
                                val request = requestQueue.receive()

                                activity.onDataReceived(request, request.size)

                                val n = emu.transact(
                                    request,
                                    request.size,
                                    bufferLoop
                                )

                                if (n <= 0) break

                                output.write(bufferLoop, 0, n)
                                output.flush()

                                activity.onDataSent(bufferLoop, n)
                            }
                        } catch (e: Exception) {
                            appendLog(
                                getString(
                                    R.string.log_bt_loopToBt_failed,
                                    e.message
                                ),
                                LogLevel.DEBUG
                            )
                        }
                    }

                    reader.join()
                    worker.cancelAndJoin()

                } finally {
                    listener?.onClientDisconnect(clientIdentifier, this@BluetoothBridge)

                    try {
                        acceptedSocket.close()
                    } catch (_: Exception) {
                    }

                    bt_input?.close()
                    bt_output?.close()
                    bt_input = null
                    bt_output = null
                    socket = null
                    appendLog(
                        getString(R.string.log_bt_connection_closed),
                        LogLevel.INFO
                    )
                }
            }
        } catch (e: CancellationException) {
            appendLog(
                getString(R.string.log_bt_cancelled),
                LogLevel.DEBUG
            )
            throw e
        } catch (e: Exception) {
            appendLog(
                getString(R.string.log_bt_error, e.message),
                LogLevel.DEBUG
            )
        }
    }

    override suspend fun start() {
        if (!activity.btAdapter.isEnabled) {
            activity.showBluetoothEnablePopup()
            return
        }
        secureServer =
            activity.btAdapter.listenUsingRfcommWithServiceRecord(
                getString(R.string.app_name),
                classicalBtUUID
            )

        insecureServer =
            activity.btAdapter.listenUsingInsecureRfcommWithServiceRecord(
                getString(R.string.app_name),
                classicalBtUUID
            )
    }

    override fun stop() {
        try {
            bt_input?.close()
            bt_input = null

            bt_output?.close()
            bt_output = null

            socket?.close()
            socket = null

            insecureServer?.close()
            insecureServer = null

            secureServer?.close()
            secureServer = null
        } catch (_: Exception) {
            
        }
    }

}