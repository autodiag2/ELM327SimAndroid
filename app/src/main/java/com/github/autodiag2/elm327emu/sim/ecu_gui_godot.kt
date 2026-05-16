package com.github.autodiag2.elm327emu.sim

import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentContainerView
import org.godotengine.godot.GodotFragment
import com.github.autodiag2.elm327emu.MainActivity

class GuiGodot(
    private val activity: MainActivity
) {
    private var isShowing = false

    fun show() {
        isShowing = true
        val godotContainer = FragmentContainerView(activity).apply {
            id = View.generateViewId()

            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        activity.showNestedScreen(godotContainer)
        
        val godotFragment = GodotFragment()

        activity.supportFragmentManager.beginTransaction()
            .replace(
                godotContainer.id,
                godotFragment
            )
            .commitNow()
    }

    fun onDestroy() {
        isShowing = false
    }

    fun isVisible(): Boolean {
        return isShowing
    }

}