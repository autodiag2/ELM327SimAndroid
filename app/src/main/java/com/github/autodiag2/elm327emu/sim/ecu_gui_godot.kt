package com.github.autodiag2.elm327emu.sim

import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentContainerView
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.sim.ecu.EcuGui
import org.godotengine.godot.GodotFragment

class GuiGodot(
    private val activity: MainActivity
) {
    private var isShowing = false
    private var godotFragment: GodotFragment? = null

    private val handler = Handler(Looper.getMainLooper())

    private var refreshRunnable: Runnable? = null

    fun show() {
        isShowing = true

        activity.requestedOrientation =
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        val godotContainer = FragmentContainerView(activity).apply {
            id = View.generateViewId()

            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

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
                    CarBridge.rpm.toDouble()
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