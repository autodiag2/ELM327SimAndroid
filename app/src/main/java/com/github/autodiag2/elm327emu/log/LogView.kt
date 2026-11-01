package com.github.autodiag2.elm327emu.log

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.log.LogRepository.AppendResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.github.autodiag2.elm327emu.sim.ecu.getName
import android.util.Log

class LogAdapter(
    private val context: Context
) : RecyclerView.Adapter<LogAdapter.VH>() {

    private val prefs by lazy {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    }

    fun getString(resId: Int, vararg formatArgs: Any?): String {
        return context.getString(resId, *formatArgs.map { it ?: "" }.toTypedArray())
    }

    class VH(val tv: View) : RecyclerView.ViewHolder(tv)

    var charsPerLine = 30

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

    override fun getItemViewType(position: Int): Int =
        when (items[position].type) {
            LogEntryType.SEPARATOR -> 1
            LogEntryType.RECV, LogEntryType.SENT -> 2
            else -> 0
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        if (viewType == 1) {
            val image = androidx.appcompat.widget.AppCompatImageView(parent.context).apply {
                setImageDrawable(
                    androidx.appcompat.content.res.AppCompatResources.getDrawable(
                        context,
                        R.drawable.log_search_separator
                    )
                )
                scaleType = android.widget.ImageView.ScaleType.CENTER
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (24 * context.resources.displayMetrics.density).toInt()
                )
            }
            return VH(image)
        }

        if (viewType == 2) {
            val density = context.resources.displayMetrics.density
            val horizontalPadding = (12 * density).toInt()
            val verticalPadding = (8 * density).toInt()
            val marginHorizontal = (8 * density).toInt()
            val marginVertical = (4 * density).toInt()

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    horizontalPadding,
                    verticalPadding,
                    horizontalPadding,
                    verticalPadding
                )
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(
                        marginHorizontal,
                        marginVertical,
                        marginHorizontal,
                        marginVertical
                    )
                }
            }

            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val count = TextView(context).apply {
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(
                    (4 * density).toInt(),
                    (3 * density).toInt(),
                    (8 * density).toInt(),
                    (3 * density).toInt()
                )
            }
            val badge = TextView(context).apply {
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(
                    (8 * density).toInt(),
                    (3 * density).toInt(),
                    (8 * density).toInt(),
                    (3 * density).toInt()
                )
            }

            val timestamp = TextView(context).apply {
                textSize = 11f
                alpha = 0.7f
                gravity = android.view.Gravity.END or
                    android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            header.addView(count)
            header.addView(badge)
            header.addView(timestamp)

            val content = TextView(context).apply {
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextIsSelectable(true)
                setPadding(0, (6 * density).toInt(), 0, 0)
            }

            container.addView(header)
            container.addView(content)
            container.tag = CardViews(count, badge, timestamp, content)
            return VH(container)
        }

        val tv = TextView(parent.context).apply {
            setPadding(16, 8, 16, 8)
            typeface = Typeface.MONOSPACE
        }
        return VH(tv)
    }

    private fun resolveColor(attribute: Int): Int {
        val ta = context.theme.obtainStyledAttributes(intArrayOf(attribute))
        return try {
            ta.getColor(
                0,
                ContextCompat.getColor(context, R.color.sol_magenta)
            )
        } finally {
            ta.recycle()
        }
    }

    private data class CardViews(
        val count: TextView,
        val badge: TextView,
        val timestamp: TextView,
        val content: TextView
    )

    fun testTextView(context: Context): TextView {
        return TextView(context).apply {
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, 0)
        }
    }

    override fun getItemCount(): Int = items.size

    fun dataLogFormat(
        data: ByteArray,
        length: Int = data.size
    ): String {
        return LogEntry.dataLogFormat(data, length, charsPerLine)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        if (items.size <= position) {
            android.util.Log.w("LogView", "Failed to display $position/${items.size}")
            return
        }

        val item = items[position]
        val view = holder.tv
        val viewType = getItemViewType(position)

        if (viewType == 1) {
            view.layoutParams = view.layoutParams.apply {
                height = (24 * context.resources.displayMetrics.density).toInt()
            }
            view.setBackgroundColor(resolveColor(android.R.attr.colorBackground))
            return
        }

        var displayText = item.text

        if (item.type != LogEntryType.NONE) {
            var data: ByteArray? = item.serial
            if (data == null || data.isEmpty()) {
                data = item.data
            }

            if (data.isNotEmpty()) {
                displayText = dataLogFormat(data, data.size)
            }
        }

        val timestamp = if (prefs.getBoolean("log_timestamp", false)) {
            SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(item.ts))
        } else {
            ""
        }

        if (viewType == 2 && view is LinearLayout) {
            val cardViews = view.tag as CardViews
            val isReceived = item.type == LogEntryType.RECV
            val accentColor = ContextCompat.getColor(
                context,
                if (isReceived) R.color.sol_green else R.color.sol_blue
            )
            cardViews.count.text = if (item.count > 1) "${item.count}" else ""
            cardViews.count.setTextColor(
                resolveColor(android.R.attr.textColorSecondary)
            )
            cardViews.badge.text = if (isReceived) getString(R.string.log_main_data_received) else getString(R.string.log_main_data_sent)
            if ( item.type == LogEntryType.SENT && item.pendingSignal != null ) {
                val signalText = getString(R.string.sim_ecu_log_signal, getName(context, item.pendingSignal!!.first), item.pendingSignal!!.second, item.pendingSignal!!.first.unit)
                cardViews.badge.text = "${cardViews.badge.text}\n${signalText}"
            }
            cardViews.badge.setTextColor(accentColor)
            cardViews.badge.background = null
            cardViews.timestamp.text = timestamp
            cardViews.content.text = displayText

            val textColor = when (item.level) {
                LogLevel.INFO -> resolveColor(android.R.attr.textColorPrimary)
                LogLevel.ERROR -> ContextCompat.getColor(context, R.color.sol_red)
                LogLevel.WARNING -> ContextCompat.getColor(context, R.color.sol_orange)
                LogLevel.NONE -> ContextCompat.getColor(context, R.color.sol_magenta)
                LogLevel.DEBUG -> ColorUtils.setAlphaComponent(
                    resolveColor(android.R.attr.textColorPrimary),
                    160
                )
            }

            cardViews.content.setTextColor(textColor)

            view.background = GradientDrawable().apply {
                cornerRadius = 10 * context.resources.displayMetrics.density
                setColor(resolveColor(android.R.attr.colorBackground))
                setStroke(
                    (context.resources.displayMetrics.density).toInt().coerceAtLeast(1),
                    accentColor
                )
            }
            return
        }

        if (item.count > 1) {
            displayText = "×${item.count} $displayText"
        }

        if (view !is TextView) {
            return
        }

        view.text = if (timestamp.isNotEmpty()) {
            "$timestamp: $displayText"
        } else {
            displayText
        }

        val colorInt = when (item.level) {
            LogLevel.INFO -> resolveColor(android.R.attr.textColorPrimary)
            LogLevel.ERROR -> ContextCompat.getColor(context, R.color.sol_red)
            LogLevel.WARNING -> ContextCompat.getColor(context, R.color.sol_orange)
            LogLevel.NONE -> ContextCompat.getColor(context, R.color.sol_magenta)
            LogLevel.DEBUG -> ColorUtils.setAlphaComponent(
                resolveColor(android.R.attr.textColorPrimary),
                128
            )
        }

        view.setTextColor(colorInt)
    }

    fun append(entry: LogEntry) {
        items.add(entry)
        notifyItemInserted(items.size - 1)
    }

    fun remove(entry: LogEntry) {
        val i = items.indexOf(entry)
        if (i < 0) {
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

    companion object {
        val OVERLAY_BUTTONS_ANIM_MS: Long = 1000
    }

    val prefs by lazy {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainScope = CoroutineScope(Dispatchers.Main)

    var onSearchChanged: ((search: String) -> Unit)? = null
    var onWidthChanged: (() -> Unit)? = null
    lateinit var btnSearch: EditText

    val logAdapter = LogAdapter(context)
    private lateinit var logTopBar: View
    private var logTopBarExpanded = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var overlayButtons: View

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

    var modelText = ""

    val saveLogLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == AppCompatActivity.RESULT_OK) {
                val uri = result.data?.data ?: return@registerForActivityResult
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    activity.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(modelText.toByteArray())
                    }
                }
            }
        }

    var stickToBottom = true
    var userTouching = false
    private lateinit var searchInput: EditText

    init {
        LayoutInflater.from(context).inflate(R.layout.log, this, true)
        setupLogsView()
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

    private fun updateLayoutMetrics(rv: RecyclerView) {
        rv.post {
            val tv = logAdapter.testTextView(rv.context)
            val sampleCharWidth = tv.paint.measureText("M")
            val availableWidthPx = rv.width.toFloat()
            if (availableWidthPx <= 0) return@post

            logAdapter.charsPerLine =
                (availableWidthPx / sampleCharWidth).toInt().coerceAtLeast(20)

            onWidthChanged?.invoke()
        }
    }

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

        btnSearch = findViewById(R.id.log_search)
        searchInput = btnSearch

        btnSearch.doAfterTextChanged {
            onSearchChanged?.invoke(btnSearch.text.toString())
        }

        rv.layoutManager = LinearLayoutManager(context)
        rv.adapter = logAdapter
        rv.itemAnimator = null

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
        previousSearchState = null
        logTopBarExpanded = !logTopBarExpanded
        logTopBar.isVisible = logTopBarExpanded

        if (logTopBarExpanded) {
            onSearchChanged?.invoke(btnSearch.text.toString())
        } else {
            onSearchChanged?.invoke("")
        }
    }

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
        post {
            Toast.makeText(
                context,
                getString(R.string.logview_saved_as, filename),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun scrollToBottomSafe() {
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
            if (count > 0) {
                rv.scrollToPosition(count - 1)
            }
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

    var previousSearchState: Boolean? = null

    fun refresh(result: AppendResult? = null, entries: List<LogEntry>? = null) {
        val searchIsBlank =
            !logTopBarExpanded || btnSearch.text.toString().isBlank()

        mainScope.launch {
            if (previousSearchState != searchIsBlank) {
                if (entries != null) {
                    logAdapter.replace(entries)
                }
                if (stickToBottom && searchIsBlank) {
                    scrollToBottomSafe()
                }
            } else if (result != null && searchIsBlank) {
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
                if (stickToBottom && searchIsBlank) {
                    scrollToBottomSafe()
                }
            }
        }

        previousSearchState = searchIsBlank
    }
}