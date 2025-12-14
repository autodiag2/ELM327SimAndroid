package com.autodiag.elm327emu

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.TextView
import android.widget.FrameLayout
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import com.autodiag.elm327emu.MainActivity
import android.view.Gravity
import android.content.Intent

class SettingsActivity : AppCompatActivity() {
    private lateinit var drawer: DrawerLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        drawer = DrawerLayout(this)

        val settingsScreen = TextView(this).apply {
            text = "Settings"
            setPadding(32, 32, 32, 32)
        }
        val content = FrameLayout(this).apply {
            addView(settingsScreen)
        }
        val navView = NavigationView(this).apply {
            menu.add("Home").setOnMenuItemClickListener {
                startActivity(Intent(this@SettingsActivity, MainActivity::class.java))
                drawer.closeDrawer(Gravity.LEFT)
                true
            }
        }

        drawer.addView(content, DrawerLayout.LayoutParams(
            DrawerLayout.LayoutParams.MATCH_PARENT,
            DrawerLayout.LayoutParams.MATCH_PARENT
        ))
        drawer.addView(navView, DrawerLayout.LayoutParams(
            DrawerLayout.LayoutParams.WRAP_CONTENT,
            DrawerLayout.LayoutParams.MATCH_PARENT
        ).apply {
            gravity = Gravity.START
        })

        setContentView(drawer)
    }
}
