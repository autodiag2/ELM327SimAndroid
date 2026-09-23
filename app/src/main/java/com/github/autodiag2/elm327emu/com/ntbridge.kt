package com.github.autodiag2.elm327emu.com

import kotlinx.coroutines.*
import java.io.*
import java.net.Socket
import com.github.autodiag2.elm327emu.LogLevel
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.R
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import kotlinx.coroutines.channels.Channel
import com.github.autodiag2.elm327emu.sim.EmuInterface

class NetworkBridge(
    emu: EmuInterface,
    scope: CoroutineScope,
    activity: MainActivity,
    private val basePort: Int = 35000,
    listener: Bridge.Listener? = null
): Bridge(emu = emu, scope = scope, activity = activity, LOG_TAG = "NT", listener = listener) {

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private val requestQueue = Channel<ByteArray>(Channel.UNLIMITED)

    private var netInput: InputStream? = null
    private var netOutput: OutputStream? = null

    override suspend fun accept() {
        var clientIdentifier = ""
        try {
            clientSocket = serverSocket!!.accept()
            appendLog(
                getString(R.string.log_network_client_connected, clientSocket!!.inetAddress.hostAddress, clientSocket!!.port),
                LogLevel.INFO
            )
            clientIdentifier = "${clientSocket!!.inetAddress.hostAddress}:${clientSocket!!.port}"
            listener?.onClientConnect(clientIdentifier, this)

            netInput = clientSocket!!.getInputStream()
            netOutput = clientSocket!!.getOutputStream()

            val reader = scope.launch {
                val bufferNet = ByteArray(1024)
                while (isActive) {
                    try {
                        val n = netInput?.read(bufferNet) ?: break
                        if (n <= 0) break
                        requestQueue.send(bufferNet.copyOf(n))
                    } catch (e: Exception) {
                        appendLog(getString(R.string.log_network_netToLoop_failed, e.message),
                            LogLevel.DEBUG
                        )
                        break
                    }
                }
            }

            val worker = scope.launch {
                val bufferLoop = ByteArray(1024)

                while (isActive) {
                    try {
                        val request = requestQueue.receive()

                        activity.onDataReceived(request, request.size)

                        val n = emu.transact(
                            request,
                            request.size,
                            bufferLoop
                        )
                        if (n <= 0) break

                        netOutput?.write(bufferLoop, 0, n)
                        netOutput?.flush()

                        activity.onDataSent(bufferLoop, n)

                    } catch (e: Exception) {
                        appendLog(
                            getString(R.string.log_network_loopToNet_failed, e.message),
                            LogLevel.DEBUG
                        )
                        break
                    }
                }
            }

            reader.join()
            worker.cancel()

        } catch (e: CancellationException) {
            appendLog(getString(R.string.log_network_cancelled), LogLevel.DEBUG)
            throw e
        } catch (e: Exception) {
            appendLog(getString(R.string.log_network_error, e.message), LogLevel.DEBUG)
        } finally {
            listener?.onClientDisconnect(clientIdentifier, this)
            netInput?.close()
            netOutput?.close()
            clientSocket?.close()

            netInput = null
            netOutput = null
            clientSocket = null

            appendLog(getString(R.string.log_network_connection_closed), LogLevel.INFO)
        }
    }

    override suspend fun start() {
        serverSocket = openServer()
        
    }

    override fun stop() {
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

}