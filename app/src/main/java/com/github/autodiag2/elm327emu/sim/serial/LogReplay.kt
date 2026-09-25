package com.github.autodiag2.elm327emu.sim.serial

import com.github.autodiag2.elm327emu.LogEntry
import com.github.autodiag2.elm327emu.LogEntryType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.github.autodiag2.elm327emu.R
import android.util.Log
import com.github.autodiag2.elm327emu.BuildConfig
import android.widget.Toast
import android.content.Context

class LogReplay(
    private var context: Context,
    private val scope: CoroutineScope
) {

    public var logEntriesProvider: (() -> List<LogEntry>)? = null
    public var streams: QueueDuplexStreams = QueueDuplexStreams()
    @Volatile
    private var job: Job? = null

    fun isRunning(): Boolean {
        return job?.isActive == true
    }

    fun getString(
        resId: Int,
        vararg formatArgs: Any?
    ): String {
        return context.getString(
            resId,
            *formatArgs.map {
                it ?: ""
            }.toTypedArray()
        )
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

    fun start(
        playSpeed: Double = 1.0,
        onFinished: (() -> Unit)? = null,
        onError: ((Throwable) -> Unit)? = null
    ) {
        val entries: List<LogEntry>? = logEntriesProvider?.invoke()
        if (entries == null || entries.isEmpty()) {
            Toast.makeText(
                context,
                getString(
                    R.string.custom_serial_replay_no_log
                ),
                Toast.LENGTH_SHORT
            ).show()

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
                                streams.bridgeOutput.write(
                                    entry.data
                                )

                                streams.bridgeOutput.flush()
                            }

                            LogEntryType.SENT -> {
                                // nothing to do
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

    private fun readReplayOutput(
        expectedLength: Int
    ): ByteArray {
        if (expectedLength <= 0) {
            return ByteArray(0)
        }

        val result =
            ByteArrayOutputStream(
                expectedLength
            )

        val buffer =
            ByteArray(512)

        while (result.size() < expectedLength) {
            val count =
                streams.bridgeInput.read(
                    buffer
                )

            if (count < 0) {
                throw IOException(
                    "Replay output stream closed"
                )
            }

            if (count > 0) {
                val remaining =
                    expectedLength -
                        result.size()

                result.write(
                    buffer,
                    0,
                    minOf(
                        count,
                        remaining
                    )
                )
            }
        }

        return result.toByteArray()
    }

    fun stop() {
        job?.cancel()
        job = null
    }

}