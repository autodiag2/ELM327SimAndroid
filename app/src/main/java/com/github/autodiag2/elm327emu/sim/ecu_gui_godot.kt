package com.github.autodiag2.elm327emu.sim

import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentContainerView
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.sim.ecu.EcuGui
import com.github.autodiag2.elm327emu.R
import org.godotengine.godot.GodotFragment

class GuiGodot(
    private val activity: MainActivity
) {
    private var isShowing = false
    private var godotFragment: GodotFragment? = null

    private val handler = Handler(Looper.getMainLooper())

    private var refreshRunnable: Runnable? = null
    private var fragmentContainer: FragmentContainerView? = null

    fun show() {
        isShowing = true

        activity.requestedOrientation =
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        if ( fragmentContainer != null ) {
            val container = fragmentContainer ?: return
            activity.showNestedScreen(container)
            return
        }

        val godotContainer = FragmentContainerView(activity).apply {
            id = View.generateViewId()

            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        fragmentContainer = godotContainer

        activity.showNestedScreen(godotContainer)

        godotFragment = GodotFragment()

        activity.supportFragmentManager.beginTransaction()
            .replace(
                godotContainer.id,
                godotFragment!!
            )
            .commitNow()

        refreshPeriodically(10)
    }

    fun refreshPeriodically(hz: Int = 10) {
        refreshRunnable?.let {
            handler.removeCallbacks(it)
        }

        val intervalMs = (1000L / hz)

        refreshRunnable = object : Runnable {
            override fun run() {
                if (!isShowing) {
                    return
                }

                updateSignal(
                    "SAEJ1979.engine_speed",
                    CarBridge.getRpm().toDouble()
                )

                updateSignal(
                    "SAEJ1979.vehicle_speed",
                    CarBridge.getSpeed().toDouble()
                )

                updateSignal(
                    "SAEJ1979.fuel_tank_level_input",
                    CarBridge.getFuel().toDouble()
                )

                updateSignal(
                    "SAEJ1979.relative_accelerator_pedal_position",
                    CarBridge.getAcceleratorRelativePosition().toDouble()
                )

                updateSignal(
                    "SAEJ1979.actual_engine_percent_torque",
                    CarBridge.getActualEnginePercentTorque().toDouble()
                )

                handler.postDelayed(
                    this,
                    intervalMs
                )
            }
        }

        handler.post(refreshRunnable!!)
    }

    fun updateSignal(
        signalPath: String,
        value: Double
    ) {
        activity.screenStack.lastOrNull()?.let { screen ->
            (screen as? EcuGui)?.let { ecuGui ->
                ecuGui.setSignalValue(
                    signalPath,
                    value
                )
            }
        }
    }

    fun onDestroy() {
        isShowing = false

        refreshRunnable?.let {
            handler.removeCallbacks(it)
        }

        refreshRunnable = null

        activity.requestedOrientation =
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    fun isVisible(): Boolean {
        return isShowing
    }
}