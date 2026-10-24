package com.github.autodiag2.elm327emu.sim.serial

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.setPadding
import com.github.autodiag2.elm327emu.BuildConfig
import com.github.autodiag2.elm327emu.log.LogEntry
import com.github.autodiag2.elm327emu.log.LogLevel
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.sim.EmuInterface
import com.github.autodiag2.elm327emu.ui.JsonConfigurable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import com.github.autodiag2.elm327emu.EmulatorForegroundService
import android.os.Handler
import android.os.Looper

class ReplayToolbar(
    private val service: EmulatorForegroundService,
    private val customController: CustomController,
    private val scope: CoroutineScope
) {
    @Volatile
    private var starting = false

    private var startJob: Job? = null

    private lateinit var content: View
    private lateinit var arrow: TextView
    private lateinit var arrowContainer: View
    private lateinit var speed: SeekBar
    private lateinit var speedValue: TextView
    private lateinit var startButton: ImageButton
    private lateinit var root: View

    private var toolbarExpanded = false
    private var toolbarAnimationGeneration = 0
    private var toolbarHeightAnimator: ValueAnimator? = null

    private fun animateToolbar(expanded: Boolean) {
        toolbarExpanded = expanded
        val generation = ++toolbarAnimationGeneration

        toolbarHeightAnimator?.cancel()
        toolbarHeightAnimator = null

        content.animate().cancel()
        arrow.animate().cancel()

        if (expanded) {
            content.layoutParams = content.layoutParams.apply {
                height = 0
            }
            content.alpha = 0f
            root.visibility = View.VISIBLE
            content.visibility = View.VISIBLE

            content.post {
                if (generation != toolbarAnimationGeneration || !toolbarExpanded) {
                    return@post
                }

                val width = content.width.takeIf { it > 0 } ?: root.width
                if (width <= 0) return@post

                content.measure(
                    View.MeasureSpec.makeMeasureSpec(
                        width,
                        View.MeasureSpec.EXACTLY
                    ),
                    View.MeasureSpec.makeMeasureSpec(
                        0,
                        View.MeasureSpec.UNSPECIFIED
                    )
                )

                val targetHeight = content.measuredHeight
                toolbarHeightAnimator = ValueAnimator.ofInt(0, targetHeight).apply {
                    duration = 250L
                    addUpdateListener { animator ->
                        content.layoutParams = content.layoutParams.apply {
                            height = animator.animatedValue as Int
                        }
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            if (generation != toolbarAnimationGeneration) return

                            content.layoutParams = content.layoutParams.apply {
                                height = ViewGroup.LayoutParams.WRAP_CONTENT
                            }
                            toolbarHeightAnimator = null
                        }
                    })
                    start()
                }

                content.animate()
                    .alpha(1f)
                    .setDuration(250L)
                    .start()
            }
        } else {
            val currentHeight = if (content.layoutParams.height >= 0) {
                content.layoutParams.height
            } else {
                content.height
            }

            // Collapse the container upward while fading it out.
            toolbarHeightAnimator = ValueAnimator.ofInt(currentHeight, 0).apply {
                duration = 250L

                addUpdateListener { animator ->
                    if (generation == toolbarAnimationGeneration) {
                        val height = animator.animatedValue as Int

                        content.layoutParams = content.layoutParams.apply {
                            this.height = height
                        }
                        content.requestLayout()
                    }
                }

                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (generation == toolbarAnimationGeneration) {
                            content.visibility = View.GONE
                            root.visibility = View.GONE
                            content.alpha = 1f

                            content.layoutParams = content.layoutParams.apply {
                                height = ViewGroup.LayoutParams.WRAP_CONTENT
                            }
                            content.requestLayout()

                            arrowContainer.translationY = 0f
                            toolbarHeightAnimator = null
                        }
                    }
                })

                start()
            }

            content.animate()
                .alpha(0f)
                .setDuration(250L)
                .start()
        }

        arrow.animate()
            .rotation(if (expanded) 180f else 0f)
            .setDuration(250L)
            .start()
    }

    fun setupReplayToolbar() {
        root = customController.view!!.parentView.findViewById(
            R.id.custom_serial_replay_toolbar
        )

        content = customController.view!!.parentView.findViewById(
            R.id.custom_serial_replay_toolbar_content
        )

        content.setOnTouchListener { _, _ ->
            true
        }

        val replayTimeline: ReplayTimelineView =
            content.findViewById(R.id.custom_serial_replay_timeline)

        customController.logReplay.setReplayTimelineView(replayTimeline)

        arrow = customController.view!!.parentView.findViewById(
            R.id.custom_serial_replay_toolbar_arrow
        )

        arrowContainer = customController.view!!.parentView.findViewById(
            R.id.custom_serial_replay_toolbar_arrow_container
        )

        speed = customController.view!!.parentView.findViewById(
            R.id.custom_serial_replay_speed
        )

        speedValue = customController.view!!.parentView.findViewById(
            R.id.custom_serial_replay_speed_value
        )

        startButton = customController.view!!.parentView.findViewById(
            R.id.custom_serial_replay_start
        )

        // Keep the arrow at the bottom as the container height changes.
        root.addOnLayoutChangeListener {
                _, _, _, _, _, _, _, _, _ ->
            if (content.visibility == View.VISIBLE) {
                arrowContainer.translationY = root.height.toFloat()
            }
        }

        arrowContainer.setOnClickListener {
            animateToolbar(!toolbarExpanded)
        }

        speed.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    speedValue.text = formatspeed(
                        speedToValue(progress)
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

        speedValue.text = formatspeed(
            speedToValue(speed.progress)
        )

        startButton.setOnClickListener {
            if (customController.logReplay.isRunning() || starting) {
                startJob?.cancel()
                startJob = null
                starting = false

                customController.logReplay.stop()
                customController.stateMachine.stop()

                val emuProvider =
                    service.bridgeOrchestrator as EmuInterface.Provider

                emuProvider.resetEmu()

                replayUpdatePlayPause()
                return@setOnClickListener
            }

            starting = true
            replayUpdatePlayPause()

            startJob = scope.launch {
                try {
                    val emuProvider = service.bridgeOrchestrator as EmuInterface.Provider

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
                            speedToValue(speed.progress)
                        },
                        onFinished = {
                            starting = false
                            replayUpdatePlayPause()
                            customController.stateMachine.stop()
                        },
                        onError = { error ->
                            starting = false

                            logDebug("Replay error: ${error.message}")

                            Handler(Looper.getMainLooper()).post {
                                replayUpdatePlayPause()

                                Toast.makeText(
                                    service,
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
    }

    fun replayUpdatePlayPause() {
        Handler(Looper.getMainLooper()).post {
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
        return 0.25 * Math.pow(
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

    fun logDebug(
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
        return service.getString(
            resId,
            *formatArgs.map {
                it ?: ""
            }.toTypedArray()
        )
    }
}