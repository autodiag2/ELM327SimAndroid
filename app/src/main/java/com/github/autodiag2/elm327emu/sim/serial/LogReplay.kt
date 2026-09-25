package com.github.autodiag2.elm327emu.sim.serial

import com.github.autodiag2.elm327emu.LogEntry
import com.github.autodiag2.elm327emu.LogEntryType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.github.autodiag2.elm327emu.R
import android.util.Log
import com.github.autodiag2.elm327emu.BuildConfig
import android.widget.Toast
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.com.Bridge
import com.github.autodiag2.elm327emu.sim.EmuInterface
import com.github.autodiag2.elm327emu.com.EmuProvider

class LogReplay(
    emuProvider: EmuProvider,
    scope: CoroutineScope,
    activity: MainActivity,
    listener: Listener? = null
) : Bridge(
    emuProvider = emuProvider,
    scope = scope,
    activity = activity,
    LOG_TAG = "LR",
    listener = listener
) {

    var logEntriesProvider: (() -> List<LogEntry>)? = null

    @Volatile
    private var job: Job? = null

    fun getEmu(): EmuInterface {
        return emuProvider.getEmu()
    }
    fun isRunning(): Boolean {
        return job?.isActive == true
    }

    public fun logDebug(
        message: String
    ) {
        if (BuildConfig.DEBUG) {
            Log.d(
                "sim.serial.LogReplay",
                message
            )
        }
    }

    override suspend fun start() {
        start(
            playSpeed = 1.0,
            onFinished = null,
            onError = null
        )
    }

    fun start(
        playSpeed: Double = 1.0,
        onFinished: (() -> Unit)? = null,
        onError: ((Throwable) -> Unit)? = null
    ) {

        val entries: List<LogEntry>? =
            logEntriesProvider?.invoke()

        if (
            entries == null ||
            entries.isEmpty()
        ) {
            activity.runOnUiThread {
                Toast.makeText(
                    activity,
                    getString(
                        R.string.custom_serial_replay_no_log
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
            onFinished?.invoke()
            return
        }

        job?.cancel()

        val speed =
            if (
                playSpeed.isFinite() &&
                playSpeed > 0.0
            ) {
                playSpeed
            } else {
                1.0
            }

        job =
            scope.launch {

                try {

                    var previousTimestamp: Long? = null

                    for (entry in entries) {

                        if (!isActive) {
                            return@launch
                        }

                        val previous =
                            previousTimestamp

                        if (previous != null) {

                            val elapsed =
                                (entry.ts - previous)
                                    .coerceAtLeast(0L)

                            val wait =
                                (elapsed / speed)
                                    .toLong()

                            if (wait > 0L) {
                                delay(wait)
                            }
                        }

                        if (!isActive) {
                            return@launch
                        }

                        when (entry.type) {

                            LogEntryType.RECV -> {

                                /*
                                 * Log RECV means data received by
                                 * the original emulator from the
                                 * external client.
                                 *
                                 * During replay, send this data
                                 * directly to the emulated
                                 * StateMachine.
                                 */
                                val hex =
                                    entry.serial.joinToString("") {
                                        "%02X".format(it)
                                    }
                                logDebug("Sending: data=${hex} data.size=${entry.serial.size} text=${entry.text}")

                                getEmu().send(
                                    entry.serial,
                                    entry.serial.size,
                                    2000L
                                )

                                logDebug(
                                    "Sent"
                                )
                            }

                            LogEntryType.SENT -> {
                                val actual =
                                    ByteArray(
                                        entry.serial.size + 100
                                    )

                                val count =
                                    getEmu().recv(
                                        actual,
                                        2000L
                                    )
                                logDebug("Received ${count} bytes")
                                if (
                                    count != entry.serial.size ||
                                    !actual.contentEquals(
                                        entry.serial
                                    )
                                ) {
                                    logDebug(
                                        "Replay SENT mismatch: " +
                                            "expected=${entry.serial.size} bytes " +
                                            "actual=$count bytes"
                                    )
                                }
                            }

                            LogEntryType.NONE -> {
                            }
                        }

                        previousTimestamp =
                            entry.ts
                    }

                } catch (e: IOException) {

                    if (isActive) {
                        onError?.invoke(e)
                    }

                } catch (e: Exception) {

                    if (isActive) {
                        onError?.invoke(e)
                    }

                } finally {

                    onFinished?.invoke()
                }
            }
    }

    override fun stop() {

        job?.cancel()
        job = null
    }
}