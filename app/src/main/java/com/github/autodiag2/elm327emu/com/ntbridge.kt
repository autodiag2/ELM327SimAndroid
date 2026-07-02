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

class NetworkBridge(
    private val activity: MainActivity,
    private val basePort: Int = 35000
): Bridge(activity) {

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null

    private var netInput: InputStream? = null
    private var netOutput: OutputStream? = null

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

    override fun start() {
        serverSocket = openServer()
        super.start()
    }

    override suspend fun startInternal() {
        try {
            clientSocket = serverSocket!!.accept()
            appendLog(
                getString(R.string.log_network_client_connected, clientSocket!!.inetAddress.hostAddress, clientSocket!!.port),
                LogLevel.INFO
            )

            netInput = clientSocket!!.getInputStream()
            netOutput = clientSocket!!.getOutputStream()

            emuStart()

            val bufferNet = ByteArray(1024)
            val bufferLoop = ByteArray(1024)

            val netToLoop = scope.launch {
                while (isActive) {
                    try {
                        val n = netInput?.read(bufferNet) ?: break
                        if (n <= 0) break
                        loopbackOutput?.write(bufferNet, 0, n)
                        loopbackOutput?.flush()
                        activity.onDataReceived(bufferNet, n)
                    } catch (e: Exception) {
                        appendLog(getString(R.string.log_network_netToLoop_failed, e.message),
                            LogLevel.DEBUG
                        )
                        break
                    }
                }
            }

            val loopToNet = scope.launch {
                while (isActive) {
                    try {
                        val n = loopbackInput?.read(bufferLoop) ?: break
                        if (n <= 0) break
                        netOutput?.write(bufferLoop, 0, n)
                        netOutput?.flush()
                        activity.onDataSent(bufferLoop, n)
                    } catch (e: Exception) {
                        appendLog(getString(R.string.log_network_loopToNet_failed, e.message),
                            LogLevel.DEBUG
                        )
                        break
                    }
                }
            }

            netToLoop.join()
            loopToNet.cancel()


            emuStop()

        } catch (e: CancellationException) {
            appendLog(getString(R.string.log_network_cancelled), LogLevel.DEBUG)
            throw e
        } catch (e: Exception) {
            appendLog(getString(R.string.log_network_error, e.message), LogLevel.DEBUG)
        } finally {
            netInput?.close()
            netOutput?.close()
            clientSocket?.close()

            netInput = null
            netOutput = null
            clientSocket = null

            appendLog(getString(R.string.log_network_connection_closed), LogLevel.INFO)
        }
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

        super.stop()
    }
}