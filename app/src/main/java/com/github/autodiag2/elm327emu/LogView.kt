package com.github.autodiag2.elm327emu

import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.TextView

import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import android.content.Context
import android.widget.FrameLayout
import kotlinx.coroutines.launch
import java.io.File
import androidx.lifecycle.lifecycleScope

import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity.RESULT_OK
import android.view.LayoutInflater
import android.widget.EditText
import androidx.core.widget.doAfterTextChanged
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.LogEntryType
import android.util.Log
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.core.view.isVisible
import android.view.View
import androidx.core.graphics.ColorUtils
import android.widget.Button
import android.widget.Toast
import com.github.autodiag2.elm327emu.LogRepository.AppendResult
import com.github.autodiag2.elm327emu.LogEntry

class LogAdapter(
    private val context: Context
) : RecyclerView.Adapter<LogAdapter.VH>() {

    private val prefs by lazy { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
    public var charsPerLine = 30

    private val items = ArrayList<LogEntry>()

    fun notifyChanged(entry: LogEntry) {
        val index = items.indexOfFirst { it.id == entry.id }
        if (index >= 0) {
            notifyItemChanged(index)
        }
    }

    fun replace(entries: List<LogEntry>) {
        items.clear()
        items.addAll(entries)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = TextView(parent.context).apply {
            setPadding(16, 8, 16, 8)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        return VH(tv)
    }

    fun testTextView(context: Context): TextView {
        return TextView(context).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 0, 0, 0)
        }
    }

    override fun getItemCount(): Int = items.size

    fun dataLogFormat(
        data: ByteArray,
        length: Int = data.size
    ): String {
        val charsPerLine = charsPerLine
        val sb = StringBuilder()

        // fixed layout parts
        val indent = 1
        val hexByteWidth = 3      // "FF "
        val asciiSeparator = 2    // "  "

        // reserve space for ASCII area (~1 char per byte)
        val usable = charsPerLine - indent - asciiSeparator

        // each byte takes ~4 chars in total (hex + space)
        val bytesPerLine = (usable / 4).coerceIn(4, 64)

        for (i in 0 until length step bytesPerLine) {

            sb.append(" ")

            val lineEnd = minOf(i + bytesPerLine, length)

            // HEX PART
            for (j in i until i + bytesPerLine) {
                if (j < lineEnd) {
                    sb.append(String.format("%02X ", data[j]))
                } else {
                    sb.append("   ")
                }
            }

            sb.append("  ")

            // ASCII PART
            for (j in i until lineEnd) {
                val b = data[j].toInt() and 0xFF
                val c = if (b in 32..126) b.toChar() else '.'
                sb.append(c)
            }

            sb.append("\n")
        }

        return sb.toString()
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        if ( items.size <= position ) {
            Log.w("LogView", "requested item does not exists")
            return
        }
        val item = items[position]
        var displayText = item.text
        if ( item.data.size > 0 && item.type != LogEntryType.NONE ) {
            displayText = context.getString(
                if (item.type == LogEntryType.RECV)
                    R.string.log_main_data_received
                else
                    R.string.log_main_data_sent,
                dataLogFormat(item.data, item.data.size)
            )
        }
        if (item.count > 1) {
            holder.tv.text = "×${item.count} ${displayText}"
        } else {
            if ( prefs.getBoolean("log_timestamp", false) ) {
                holder.tv.text = "${SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(item.ts))}: ${displayText}"
            } else {
                holder.tv.text = "${displayText}"
            }
        }
        val ctx = holder.tv.context
        val primaryColor = run {
            val ta = ctx.theme.obtainStyledAttributes(
                intArrayOf(android.R.attr.textColorPrimary)
            )
            try {
                ta.getColor(0, 0xFFAAAAAA.toInt())
            } finally {
                ta.recycle()
            }
        }
        val colorInt = when (item.level) {
            LogLevel.INFO -> primaryColor
            LogLevel.ERROR -> ContextCompat.getColor(ctx, R.color.sol_red)
            LogLevel.WARNING -> ContextCompat.getColor(ctx, R.color.sol_orange)
            LogLevel.NONE -> ContextCompat.getColor(ctx, R.color.sol_magenta)
            LogLevel.DEBUG -> androidx.core.graphics.ColorUtils.setAlphaComponent(primaryColor, 128)
        }

        holder.tv.setTextColor(colorInt)
    }

    fun append(entry: LogEntry) {
        items.add(entry)
        notifyItemInserted(items.size - 1)
    }

    fun remove(entry: LogEntry) {
        val i = items.indexOf(entry)
        if ( i < 0 ) {
            return
        }
        items.remove(entry)
        notifyItemRemoved(i)
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }
}
class LogView(
    private val activity: MainActivity
) : FrameLayout(activity) {
    val prefs by lazy { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainScope = CoroutineScope(Dispatchers.Main)
    public var search = ""

    public val logAdapter = LogAdapter(context)
    private lateinit var logTopBar: View
    private var logTopBarExpanded = false
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var overlayButtons: View
    companion object {
        val OVERLAY_BUTTONS_ANIM_MS: Long = 1000
    }

    private val hideOverlayRunnable = Runnable {
        overlayButtons.animate()
            .alpha(0f)
            .translationY(32f)
            .setDuration(250)
            .withEndAction {
                overlayButtons.isVisible = false
            }
            .start()
    }
    private fun showOverlayButtons() {
        uiHandler.removeCallbacks(hideOverlayRunnable)

        if (!overlayButtons.isVisible) {
            overlayButtons.apply {
                alpha = 0f
                translationY = 32f
                isVisible = true
            }
        }

        overlayButtons.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(150)
            .start()

        uiHandler.postDelayed(hideOverlayRunnable, OVERLAY_BUTTONS_ANIM_MS)
    }
    public var modelText = ""
    val saveLogLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri = result.data?.data ?: return@registerForActivityResult
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    activity.contentResolver.openOutputStream(uri)?.use { out ->
                        val text = modelText
                        out.write(text.toByteArray())
                    }
                }
            }
        }

    public var stickToBottom = true
    public var userTouching = false

    init {
        LayoutInflater.from(context).inflate(R.layout.log, this, true)
        setupLogsView()
    }

    private fun updateLayoutMetrics(rv: RecyclerView) {
        rv.post {
            val tv = logAdapter.testTextView(rv.context)

            val paint = tv.paint

            val sampleCharWidth = paint.measureText("M")

            val availableWidthPx = rv.width.toFloat()
            if (availableWidthPx <= 0) return@post

            logAdapter.charsPerLine = (availableWidthPx / sampleCharWidth).toInt().coerceAtLeast(20)

        }
    }

    private lateinit var searchInput: EditText
    private fun setupLogsView() {

        logTopBar = findViewById(R.id.log_top_bar)
        logTopBar.isVisible = false
        val rv = findViewById<RecyclerView>(R.id.rvLogs)

        rv.viewTreeObserver.addOnGlobalLayoutListener {
            updateLayoutMetrics(rv)
        }
        val btnUp = findViewById<Button>(R.id.btnUp)
        val btnDown = findViewById<Button>(R.id.btnDown)
        overlayButtons = findViewById(R.id.overlayButtons)
        uiHandler.postDelayed(hideOverlayRunnable, OVERLAY_BUTTONS_ANIM_MS)
        val btnSearch = findViewById<EditText>(R.id.log_search)
        searchInput = btnSearch

        btnSearch.doAfterTextChanged {
            search = it?.toString() ?: ""
            refresh()
        }

        rv.layoutManager = LinearLayoutManager(context)
        rv.adapter = logAdapter
        rv.itemAnimator = null

        // ---- user scroll detection (IMPORTANT) ----
        rv.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> userTouching = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> userTouching = false
            }
            false
        }

        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    uiHandler.postDelayed(hideOverlayRunnable, OVERLAY_BUTTONS_ANIM_MS)
                } else {
                    showOverlayButtons()
                }
            }
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy < 0) {
                    stickToBottom = false
                }
                showOverlayButtons()
            }
        })

        btnUp.setOnClickListener {
            stickToBottom = false
            rv.scrollToPosition(0)
        }

        btnDown.setOnClickListener {
            stickToBottom = true
            scrollToBottom(rv)
        }

    }

    fun toggleTopBar() {
        logTopBarExpanded = !logTopBarExpanded
        logTopBar.isVisible = logTopBarExpanded
        if (logTopBarExpanded) {
            search = searchInput.getText().toString()
        } else {
            search = ""
        }
        refresh()
    }

    // ---- PUBLIC BUTTON ACTIONS ----

    fun scrollUp() {
        val rv = findViewById<RecyclerView>(R.id.rvLogs)
        stickToBottom = false
        rv.scrollToPosition(0)
    }

    fun scrollDown() {
        val rv = findViewById<RecyclerView>(R.id.rvLogs)
        stickToBottom = true
        scrollToBottom(rv)
    }

    fun save(filename: String) {
        openSaveLogDialog(filename)
    }
    
    fun getString(resId: Int, vararg formatArgs: Any?): String {
        return context.getString(resId, *formatArgs.map { it ?: "" }.toTypedArray())
    }
    fun onDownload(filename: String) {
        Toast.makeText(
            context,
            getString(R.string.logview_saved_as, filename),
            Toast.LENGTH_SHORT
        ).show()
    }

    // ---- SAFE SCROLL ----
    public fun scrollToBottomSafe() {
        val rv = findViewById<RecyclerView>(R.id.rvLogs)
        rv.post {
            val count = logAdapter.itemCount
            if (count > 0) {
                rv.scrollToPosition(count - 1)
            }
        }
    }

    private fun scrollToBottom(rv: RecyclerView) {
        rv.post {
            val count = logAdapter.itemCount
            if (count > 0) rv.scrollToPosition(count - 1)
        }
    }

    fun openSaveLogDialog(filename: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, filename)
        }

        saveLogLauncher.launch(intent)
    }

    fun refresh(result: AppendResult? = null, entries: List<LogEntry>? = null) {
        mainScope.launch {
            if (result != null && search.isBlank()) {
                if (result.deleted != null) {
                    logAdapter.remove(result.deleted)
                }
                if (result.modified != null) {
                    logAdapter.notifyChanged(result.modified)
                }
                if (result.inserted) {
                    logAdapter.append(result.entry)
                } else {
                    logAdapter.notifyChanged(result.entry)
                }
            } else {
                if (entries != null) {
                    logAdapter.replace(entries)
                }
                if (stickToBottom && search.isBlank()) {
                    scrollToBottomSafe()
                }
            }
        }
    }

}