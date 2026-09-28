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
import kotlinx.coroutines.currentCoroutineContext

class LogReplay(
    emuProvider: EmuInterface.Provider,
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
    private var replayTimeline: ReplayTimelineView? = null
    var logEntriesProvider: (() -> List<LogEntry>)? = null

    @Volatile
    private var job: Job? = null

    fun setReplayTimelineView(replayTimeline: ReplayTimelineView?) {
        this.replayTimeline = replayTimeline
    }
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
            playSpeedProvider = null,
            onFinished = null,
            onError = null,
            onProgress = null
        )
    }

    fun getSpeed(playSpeedProvider:(() -> Double)? = null): Double {
        val playSpeed = playSpeedProvider?.invoke() ?: 1.0
        val speed =
            if (
                playSpeed.isFinite() &&
                playSpeed > 0.0
            ) {
                playSpeed
            } else {
                1.0
            }
        return speed
    }

    private suspend fun delayDynamic(
        duration: Long,
        playSpeedProvider: (() -> Double)?
    ) {
        var remaining = duration.toDouble()

        while (remaining > 0.0 && currentCoroutineContext().isActive) {

            val speed = getSpeed(playSpeedProvider)

            val wallDelay =
                (remaining / speed)
                    .coerceAtMost(16.0)
                    .toLong()
                    .coerceAtLeast(1L)

            val start =
                System.currentTimeMillis()

            delay(wallDelay)

            val actualElapsed =
                (
                    System.currentTimeMillis() -
                        start
                    ).coerceAtLeast(0L)

            remaining -=
                actualElapsed * speed
        }
    }

    fun start(
        playSpeedProvider:(() -> Double)? = null,
        onFinished: (() -> Unit)? = null,
        onError: ((Throwable) -> Unit)? = null,
        onProgress: ((Int, Int) -> Unit)? = null,
    ) {

        val entries: List<LogEntry>? = logEntriesProvider?.invoke()

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

        replayTimeline?.setEntries(entries)
        job =
            scope.launch {

                try {
                    replayTimeline?.startPlayback {
                        getSpeed(playSpeedProvider)
                    }
                    var previousTimestamp: Long? = null

                    val total = entries.size
                    for ((index, entry) in entries.withIndex()) {

                        if (!isActive) {
                            replayTimeline?.stopPlayback()
                            return@launch
                        }
                       
                        val previous =
                            previousTimestamp

                        if (previous != null) {

                            val elapsed =
                                (entry.ts - previous)
                                    .coerceAtLeast(0L)

                            if (elapsed > 0L) {
                                delayDynamic(
                                    elapsed,
                                    playSpeedProvider
                                )
                            }
                        }

                        if (!isActive) {
                            replayTimeline?.stopPlayback()
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

                        previousTimestamp = entry.ts

                        val played = index + 1
                        activity.runOnUiThread {
                            replayTimeline?.setPlayedEntries(played)
                        }
                        onProgress?.invoke(played, total)
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
                    replayTimeline?.stopPlayback()
                    onFinished?.invoke()
                }
            }
    }

    override fun stop() {
        replayTimeline?.stopPlayback()
        job?.cancel()
        job = null
    }

}