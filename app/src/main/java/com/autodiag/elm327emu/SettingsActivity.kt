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
import android.view.View

class SettingsActivity : AppCompatActivity() {

    private lateinit var drawer: DrawerLayout
    private lateinit var prefs: SharedPreferences
    private val PROTOCOL_OFFSET = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        drawer = DrawerLayout(this)
        prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            gravity = Gravity.TOP
        }

        /* ========= General section ========= */

        val generalTitle = TextView(this).apply {
            text = "General"
            textSize = 18f
        }

        val autoScrollToggle = Switch(this).apply {
            text = "Auto-scroll on output"
            isChecked = prefs.getBoolean("auto_scroll", true)
            setOnCheckedChangeListener { _, v ->
                prefs.edit().putBoolean("auto_scroll", v).apply()
            }
        }

        root.addView(generalTitle)
        root.addView(autoScrollToggle)

        /* ========= ELM327 parameters section ========= */

        val elmTitle = TextView(this).apply {
            text = "ELM327 parameters"
            textSize = 18f
            setPadding(0, 32, 0, 0)
        }

        val protocols = libautodiag.getProtocols()
        val currentProto = libautodiag.getProtocol()

        val spinner = Spinner(this)
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            protocols
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        spinner.adapter = adapter

        val spinnerIndex = currentProto - PROTOCOL_OFFSET
        if (spinnerIndex in protocols.indices) {
            spinner.setSelection(spinnerIndex)
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?,
                pos: Int,
                id: Long
            ) {
                libautodiag.setProtocol(pos + PROTOCOL_OFFSET)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        root.addView(elmTitle)
        root.addView(spinner)

        val content = ScrollView(this).apply {
            addView(root)
        }

        val navView = NavigationView(this).apply {
            menu.add("Home").setOnMenuItemClickListener {
                finish()
                drawer.closeDrawer(Gravity.LEFT)
                true
            }
        }

        drawer.addView(
            content,
            DrawerLayout.LayoutParams(
                DrawerLayout.LayoutParams.MATCH_PARENT,
                DrawerLayout.LayoutParams.MATCH_PARENT
            )
        )

        drawer.addView(
            navView,
            DrawerLayout.LayoutParams(
                DrawerLayout.LayoutParams.WRAP_CONTENT,
                DrawerLayout.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.START }
        )

        setContentView(drawer)
    }
}

