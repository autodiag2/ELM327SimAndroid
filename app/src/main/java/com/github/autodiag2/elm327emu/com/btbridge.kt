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

import android.util.Log
import com.github.autodiag2.elm327emu.BuildConfig

class BluetoothBridge(
    emu: EmuInterface,
    scope: CoroutineScope,
    activity: MainActivity,
    listener: Bridge.Listener? = null
) : Bridge(
    emu = emu,
    scope = scope,
    activity = activity,
    LOG_TAG = "BT SPP",
    listener = listener
) {

    private val classicalBtUUID =
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private val requestQueue =
        Channel<ByteArray>(Channel.UNLIMITED)

    private val acceptedSockets =
        Channel<BluetoothSocket>(Channel.UNLIMITED)

    /*
     * Independent from BridgeOrchestrator.scope.
     *
     * Cancellation of a connection worker cannot cancel the
     * orchestrator accept job.
     */
    private val bluetoothScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var secureServer: BluetoothServerSocket? = null
    private var insecureServer: BluetoothServerSocket? = null

    private var secureAcceptJob: Job? = null
    private var insecureAcceptJob: Job? = null

    private var socket: BluetoothSocket? = null
    private var bt_input: InputStream? = null
    private var bt_output: OutputStream? = null

    fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(
                "com.BluetoothBridge",
                message
            )
        }
    }

    override suspend fun accept() {

        appendLog(
            getString(R.string.log_bt_waiting_for_connection),
            LogLevel.INFO
        )

        try {

            /*
             * This receive is the only thing accept() does.
             *
             * Secure and insecure server sockets are independently
             * accepting connections in bluetoothScope.
             */
            val acceptedSocket =
                acceptedSockets.receive()

            socket = acceptedSocket

            var clientIdentifier = ""

            try {

                clientIdentifier =
                    acceptedSocket.remoteDevice.address

                appendLog(
                    getString(
                        R.string.log_bt_client_connected,
                        clientIdentifier
                    ),
                    LogLevel.INFO
                )

                listener?.onClientConnect(
                    clientIdentifier,
                    this@BluetoothBridge
                )

                val input =
                    acceptedSocket.inputStream

                val output =
                    acceptedSocket.outputStream

                bt_input = input
                bt_output = output

                /*
                 * These are children of the independent Bluetooth scope,
                 * NOT BridgeOrchestrator.scope.
                 */
                val connectionScope =
                    CoroutineScope(
                        Dispatchers.IO + SupervisorJob()
                    )

                val reader =
                    connectionScope.launch {

                        try {

                            while (isActive) {

                                val bufferBT =
                                    ByteArray(1024)

                                val n =
                                    input.read(bufferBT)

                                if (n <= 0) {
                                    break
                                }

                                requestQueue.send(
                                    bufferBT.copyOf(n)
                                )
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

                val worker =
                    connectionScope.launch {

                        val bufferLoop =
                            ByteArray(1024)

                        try {

                            while (isActive) {

                                val request =
                                    requestQueue.receive()

                                activity.onDataReceived(
                                    request,
                                    request.size
                                )

                                val n =
                                    emu.transact(
                                        request,
                                        request.size,
                                        bufferLoop
                                    )

                                if (n <= 0) {
                                    break
                                }

                                output.write(
                                    bufferLoop,
                                    0,
                                    n
                                )

                                output.flush()

                                activity.onDataSent(
                                    bufferLoop,
                                    n
                                )
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

                /*
                 * Only the connectionScope is cancelled.
                 *
                 * BridgeOrchestrator.scope is untouched.
                 */
                connectionScope.cancel()

                worker.join()

            } finally {

                listener?.onClientDisconnect(
                    clientIdentifier,
                    this@BluetoothBridge
                )

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
                    getString(
                        R.string.log_bt_connection_closed
                    ),
                    LogLevel.INFO
                )
            }

        } catch (e: CancellationException) {

            appendLog(
                getString(R.string.log_bt_cancelled),
                LogLevel.DEBUG
            )

            throw e

        } catch (e: Exception) {

            appendLog(
                getString(
                    R.string.log_bt_error,
                    e.message
                ),
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

        /*
         * Independent accept loops.
         *
         * There is no select(), therefore we never need to cancel
         * one accept operation when the other one succeeds.
         */
        secureAcceptJob =
            bluetoothScope.launch {

                try {

                    while (isActive) {

                        val accepted =
                            secureServer?.accept()
                                ?: break

                        acceptedSockets.send(
                            accepted
                        )
                    }

                } catch (e: Exception) {

                    if (isActive) {
                        appendLog(
                            getString(
                                R.string.log_bt_error,
                                e.message
                            ),
                            LogLevel.DEBUG
                        )
                    }
                }
            }

        insecureAcceptJob =
            bluetoothScope.launch {

                try {

                    while (isActive) {

                        val accepted =
                            insecureServer?.accept()
                                ?: break

                        acceptedSockets.send(
                            accepted
                        )
                    }

                } catch (e: Exception) {

                    if (isActive) {
                        appendLog(
                            getString(
                                R.string.log_bt_error,
                                e.message
                            ),
                            LogLevel.DEBUG
                        )
                    }
                }
            }
    }

    override fun stop() {

        try {

            bt_input?.close()
            bt_input = null

            bt_output?.close()
            bt_output = null

            socket?.close()
            socket = null

            secureServer?.close()
            secureServer = null

            insecureServer?.close()
            insecureServer = null

        } catch (_: Exception) {
        }

        secureAcceptJob?.cancel()
        secureAcceptJob = null

        insecureAcceptJob?.cancel()
        insecureAcceptJob = null

        /*
         * Stop all Bluetooth-specific coroutines.
         *
         * This does NOT cancel BridgeOrchestrator.scope.
         */
        bluetoothScope.coroutineContext.cancelChildren()
    }
}