package com.github.autodiag2.elm327emu.sim.serial

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.setPadding
import org.json.JSONArray
import org.json.JSONObject
import com.github.autodiag2.elm327emu.R
import android.util.Log
import com.github.autodiag2.elm327emu.BuildConfig
import com.github.autodiag2.elm327emu.ui.JsonConfigurable
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.LogLevel
import com.github.autodiag2.elm327emu.LogEntry
import com.github.autodiag2.elm327emu.sim.EmuInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import android.widget.ImageButton
import android.app.Activity

class ReplayToolbar(
    private val activity: MainActivity,
    private val customController: CustomController,
    private val scope: CoroutineScope
) {
    @Volatile
    private var replayStarting = false
    private var replayStartJob: Job? = null

    private lateinit var replayToolbarContent: View
    private lateinit var replayToolbarArrow: TextView
    private lateinit var replaySpeed: SeekBar
    private lateinit var replaySpeedValue: TextView
    private lateinit var replayStart: ImageButton
    private lateinit var replayToolbar: View

    fun setupReplayToolbar() {
        replayToolbar = customController.findViewById(R.id.custom_serial_replay_toolbar)
        replayToolbarContent =
            customController.findViewById(
                R.id.custom_serial_replay_toolbar_content
            )
        replayToolbarContent.setOnTouchListener { _, _ ->
            true
        }
        var replayTimeline: ReplayTimelineView = replayToolbarContent.findViewById(R.id.custom_serial_replay_timeline)
        customController.logReplay.setReplayTimelineView(replayTimeline)

        replayToolbarArrow =
            customController.findViewById(
                R.id.custom_serial_replay_toolbar_arrow
            )

        replaySpeed =
            customController.findViewById(
                R.id.custom_serial_replay_speed
            )

        replaySpeedValue =
            customController.findViewById(
                R.id.custom_serial_replay_speed_value
            )

        replayStart =
            customController.findViewById(
                R.id.custom_serial_replay_start
            )

        replayToolbarArrow.setOnClickListener {
            val expanded = replayToolbarContent.visibility != View.VISIBLE

            replayToolbarContent.visibility =
                if (expanded) View.VISIBLE else View.GONE

            replayToolbarArrow.setText(
                if (expanded) {
                    R.string.custom_serial_toolbar_arrow_up
                } else {
                    R.string.custom_serial_toolbar_arrow_down
                }
            )

            updateReplayToolbarArrow()
        }

        replaySpeed.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {

                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    replaySpeedValue.text =
                        formatReplaySpeed(
                            replaySpeedToValue(
                                progress
                            )
                        )
                }

                override fun onStartTrackingTouch(
                    seekBar: SeekBar?
                ) {
                }

                override fun onStopTrackingTouch(
                    seekBar: SeekBar?
                ) {
                }
            }
        )

        replaySpeedValue.text =
            formatReplaySpeed(
                replaySpeedToValue(
                    replaySpeed.progress
                )
            )

        replayStart.setOnClickListener {
            if (customController.logReplay.isRunning() || replayStarting) {
                replayStartJob?.cancel()
                replayStartJob = null
                replayStarting = false

                customController.logReplay.stop()
                customController.stateMachine.stop()

                val emuProvider =
                    activity.bridgeOrchestrator as EmuInterface.Provider

                emuProvider.resetEmu()

                replayUpdatePlayPause()
                return@setOnClickListener
            }

            replayStarting = true
            replayUpdatePlayPause()

            replayStartJob =
                scope.launch {
                    try {
                        val emuProvider =
                            activity.bridgeOrchestrator as EmuInterface.Provider

                        emuProvider.resetEmu()

                        val scriptEmu =
                            customController.stateMachine as EmuInterface

                        emuProvider.setEmu(scriptEmu)

                        customController.stateMachine.start()

                        if (!isActive || !replayStarting) {
                            return@launch
                        }

                        customController.logReplay.start(
                            playSpeedProvider = {
                                replaySpeedToValue(
                                    replaySpeed.progress
                                )
                            },
                            onFinished = {
                                replayStarting = false
                                replayUpdatePlayPause()
                                customController.stateMachine.stop()
                            },
                            onError = { error ->
                                replayStarting = false

                                logDebug(
                                    "Replay error: ${error.message}"
                                )

                                activity.runOnUiThread {
                                    replayUpdatePlayPause()

                                    Toast.makeText(
                                        activity,
                                        getString(
                                            R.string.custom_serial_replay_error,
                                            error.message ?: ""
                                        ),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        )

                        replayStarting = false
                        replayUpdatePlayPause()

                    } finally {
                        replayStartJob = null
                    }
                }
        }
        updateReplayToolbarArrow()
    }

    private fun updateReplayToolbarArrow() {
        if (replayToolbarContent.visibility == View.VISIBLE) {
            replayToolbarContent.post {
                replayToolbarArrow.translationY =
                    replayToolbar.height.toFloat()
            }
        } else {
            replayToolbarArrow.translationY = 0f
        }
    }

    fun replayUpdatePlayPause() {
        activity.runOnUiThread {
            if (customController.logReplay.isRunning() || replayStarting) {
                replayStart.setImageResource(R.drawable.ic_pause)
                replayStart.contentDescription =
                    getString(R.string.custom_serial_replay_start)
            } else {
                replayStart.setImageResource(R.drawable.ic_play)
                replayStart.contentDescription =
                    getString(R.string.custom_serial_replay_stop)
            }
        }
    }

    private fun replaySpeedToValue(
        progress: Int
    ): Double {
        /*
         * 0   -> 0.25x
         * 50  -> 1.00x
         * 100 -> 4.00x
         */
        return 0.25 *
            Math.pow(
                16.0,
                progress.coerceIn(0, 100) / 100.0
            )
    }

    private fun formatReplaySpeed(
        speed: Double
    ): String {
        return when {
            speed < 1.0 ->
                "x%.2f".format(speed)

            speed < 2.0 ->
                "x%.1f".format(speed)

            else ->
                "x%.1f".format(speed)
        }
    }

    public fun logDebug(
        message: String
    ) {
        if (BuildConfig.DEBUG) {
            Log.d(
                "sim.serial.ReplayToolbar",
                message
            )
        }
    }

    fun getString(
        resId: Int,
        vararg formatArgs: Any?
    ): String {
        return activity.getString(
            resId,
            *formatArgs.map {
                it ?: ""
            }.toTypedArray()
        )
    }
}