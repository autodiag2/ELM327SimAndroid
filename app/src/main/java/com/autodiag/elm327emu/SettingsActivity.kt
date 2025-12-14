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
import android.content.SharedPreferences
import android.content.Context
import android.widget.*

class SettingsActivity : AppCompatActivity() {
    private lateinit var drawer: DrawerLayout
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        drawer = DrawerLayout(this)
        prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        val settingsScreen = TextView(this).apply {
            text = "Settings"
            setPadding(32, 32, 32, 32)
        }
        val autoScrollToggle = Switch(this).apply {
            text = "Auto-scroll on output"
            isChecked = prefs.getBoolean("auto_scroll", true)
            setPadding(32, 32, 32, 32)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("auto_scroll", isChecked).apply()
            }
        }
        val content = FrameLayout(this).apply {
            addView(settingsScreen)
            addView(autoScrollToggle)
        }
        val navView = NavigationView(this).apply {
            menu.add("Home").setOnMenuItemClickListener {
                finish()
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
