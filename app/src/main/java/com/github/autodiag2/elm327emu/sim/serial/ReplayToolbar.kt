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
    private var starting = false
    private var startJob: Job? = null

    private lateinit var content: View
    private lateinit var arrow: TextView
    private lateinit var speed: SeekBar
    private lateinit var speedValue: TextView
    private lateinit var startButton: ImageButton
    private lateinit var root: View

    fun setupReplayToolbar() {
        root = customController.findViewById(R.id.custom_serial_replay_toolbar)
        content =
            customController.findViewById(
                R.id.custom_serial_replay_toolbar_content
            )
        content.setOnTouchListener { _, _ ->
            true
        }
        var replayTimeline: ReplayTimelineView = content.findViewById(R.id.custom_serial_replay_timeline)
        customController.logReplay.setReplayTimelineView(replayTimeline)

        arrow =
            customController.findViewById(
                R.id.custom_serial_replay_toolbar_arrow
            )

        speed =
            customController.findViewById(
                R.id.custom_serial_replay_speed
            )

        speedValue =
            customController.findViewById(
                R.id.custom_serial_replay_speed_value
            )

        startButton =
            customController.findViewById(
                R.id.custom_serial_replay_start
            )

        arrow.setOnClickListener {
            val expanded = content.visibility != View.VISIBLE

            content.visibility =
                if (expanded) View.VISIBLE else View.GONE

            arrow.setText(
                if (expanded) {
                    R.string.custom_serial_toolbar_arrow_up
                } else {
                    R.string.custom_serial_toolbar_arrow_down
                }
            )

            updatearrow()
        }

        speed.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {

                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    speedValue.text =
                        formatspeed(
                            speedToValue(
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

        speedValue.text =
            formatspeed(
                speedToValue(
                    speed.progress
                )
            )

        startButton.setOnClickListener {
            if (customController.logReplay.isRunning() || starting) {
                startJob?.cancel()
                startJob = null
                starting = false

                customController.logReplay.stop()
                customController.stateMachine.stop()

                val emuProvider =
                    activity.bridgeOrchestrator as EmuInterface.Provider

                emuProvider.resetEmu()

                replayUpdatePlayPause()
                return@setOnClickListener
            }

            starting = true
            replayUpdatePlayPause()

            startJob =
                scope.launch {
                    try {
                        val emuProvider =
                            activity.bridgeOrchestrator as EmuInterface.Provider

                        emuProvider.resetEmu()

                        val scriptEmu =
                            customController.stateMachine as EmuInterface

                        emuProvider.setEmu(scriptEmu)

                        customController.stateMachine.start()

                        if (!isActive || !starting) {
                            return@launch
                        }

                        customController.logReplay.start(
                            playSpeedProvider = {
                                speedToValue(
                                    speed.progress
                                )
                            },
                            onFinished = {
                                starting = false
                                replayUpdatePlayPause()
                                customController.stateMachine.stop()
                            },
                            onError = { error ->
                                starting = false

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

                        starting = false
                        replayUpdatePlayPause()

                    } finally {
                        startJob = null
                    }
                }
        }
        updatearrow()
    }

    private fun updatearrow() {
        if (content.visibility == View.VISIBLE) {
            content.post {
                arrow.translationY =
                    root.height.toFloat()
            }
        } else {
            arrow.translationY = 0f
        }
    }

    fun replayUpdatePlayPause() {
        activity.runOnUiThread {
            if (customController.logReplay.isRunning() || starting) {
                startButton.setImageResource(R.drawable.ic_pause)
                startButton.contentDescription =
                    getString(R.string.custom_serial_replay_start)
            } else {
                startButton.setImageResource(R.drawable.ic_play)
                startButton.contentDescription =
                    getString(R.string.custom_serial_replay_stop)
            }
        }
    }

    private fun speedToValue(
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

    private fun formatspeed(
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