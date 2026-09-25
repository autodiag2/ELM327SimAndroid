package com.github.autodiag2.elm327emu.sim.serial

import com.github.autodiag2.elm327emu.LogEntry
import com.github.autodiag2.elm327emu.LogEntryType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

class LogReplay(
    private val scope: CoroutineScope
) {

    @Volatile
    private var replayJob: Job? = null

    fun isRunning(): Boolean {
        return replayJob?.isActive == true
    }

    fun replay(
        entries: List<LogEntry>,
        streams: QueueDuplexStreams,
        playSpeed: Double = 1.0,
        onAction: ((LogEntry) -> Unit)? = null,
        onMismatch: ((LogEntry, ByteArray) -> Unit)? = null,
        onFinished: (() -> Unit)? = null,
        onError: ((Throwable) -> Unit)? = null
    ) {
        stop()

        val speed =
            if (
                playSpeed.isFinite() &&
                playSpeed > 0.0
            ) {
                playSpeed
            } else {
                1.0
            }

        val actions =
            entries.filter {
                it.type == LogEntryType.RECV ||
                    it.type == LogEntryType.SENT
            }

        replayJob =
            scope.launch {
                try {
                    var previousTimestamp: Long? = null

                    for (entry in actions) {
                        if (!isActive) {
                            return@launch
                        }

                        val previous =
                            previousTimestamp

                        if (previous != null) {
                            val elapsed =
                                (
                                    entry.ts -
                                        previous
                                ).coerceAtLeast(0L)

                            val wait =
                                (
                                    elapsed /
                                        speed
                                ).toLong()

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
                                 * Tester -> StateMachine
                                 */
                                streams.bridgeOutput.write(
                                    entry.data
                                )

                                streams.bridgeOutput.flush()

                                onAction?.invoke(entry)
                            }

                            LogEntryType.SENT -> {
                                /*
                                 * StateMachine -> tester.
                                 *
                                 * Read the actual bytes generated
                                 * by the StateMachine.
                                 */
                                val actual =
                                    readOutput(
                                        streams
                                    )

                                if (
                                    !actual.contentEquals(
                                        entry.data
                                    )
                                ) {
                                    onMismatch?.invoke(
                                        entry,
                                        actual
                                    )
                                }

                                onAction?.invoke(entry)
                            }

                            LogEntryType.NONE -> {
                                // Ignored.
                            }
                        }

                        previousTimestamp =
                            entry.ts
                    }

                    onFinished?.invoke()

                } catch (e: IOException) {
                    if (isActive) {
                        onError?.invoke(e)
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        onError?.invoke(e)
                    }
                }
            }
    }

    private suspend fun readOutput(
        streams: QueueDuplexStreams
    ): ByteArray {

        /*
         * bridgeInput contains data written by the
         * StateMachine through streams.output.
         *
         * QueueInputStream.read() blocks until data arrives.
         */
        val buffer =
            ByteArray(4096)

        val count =
            streams.bridgeInput.read(
                buffer
            )

        if (count < 0) {
            throw IOException(
                "Replay output stream closed"
            )
        }

        return buffer.copyOf(count)
    }

    fun stop() {
        replayJob?.cancel()
        replayJob = null
    }

}