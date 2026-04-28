package com.github.autodiag2.elm327emu

import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.TextView

import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.DiffUtil

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingDataAdapter

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Button
import android.view.Gravity
import kotlinx.coroutines.launch
import java.io.File
import androidx.lifecycle.lifecycleScope

import kotlinx.coroutines.flow.collectLatest
import androidx.paging.cachedIn
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity.RESULT_OK
import android.view.LayoutInflater

public enum class LogLevel(val value: Int) {
    ERROR(0),
    INFO(1),
    DEBUG(2)
}

public fun hexDump(buffer: ByteArray, size: Int): String {
    val colSize = 20
    val result = StringBuilder()
    var byteI = 0

    while (byteI < size) {
        val hexCollector = StringBuilder()
        val asciiCollector = StringBuilder()
        var col = 0

        while (col < colSize && byteI < size) {
            val b = buffer[byteI].toInt() and 0xFF

            if (col > 0) hexCollector.append(' ')
            hexCollector.append(String.format("%02x", b))

            asciiCollector.append(
                if (b in 0x20..0x7E) b.toChar() else '.'
            )

            col += 1
            byteI += 1
        }

        result.append(
            String.format(
                "%59s | %20s\n",
                hexCollector.toString(),
                asciiCollector.toString()
            )
        )
    }

    return result.toString()
}

data class LogEntry(
    val id: Long,
    val text: String,
    val level: LogLevel
)

class LogPagingSource(
    private val snapshot: List<LogEntry>
) : PagingSource<Int, LogEntry>() {

    override fun getRefreshKey(state: PagingState<Int, LogEntry>): Int? =
        state.anchorPosition?.let { it / PAGE_SIZE }

    override suspend fun load(
        params: LoadParams<Int>
    ): PagingSource.LoadResult<Int, LogEntry> {

        val page = params.key ?: 0
        val from = page * PAGE_SIZE
        if (from >= snapshot.size) {
            return PagingSource.LoadResult.Page(
                data = emptyList(),
                prevKey = null,
                nextKey = null
            )
        }

        val to = minOf(from + PAGE_SIZE, snapshot.size)

        return PagingSource.LoadResult.Page(
            data = snapshot.subList(from, to),
            prevKey = if (page == 0) null else page - 1,
            nextKey = if (to >= snapshot.size) null else page + 1
        )
    }

    companion object {
        const val PAGE_SIZE = 200
    }
}
class LogRepository(private val context: Context) {

    private val buffer = ArrayList<LogEntry>()
    private val mutex = Mutex()
    private var counter = 0L

    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    suspend fun append(text: String, level: LogLevel = LogLevel.DEBUG): LogEntry {
        return mutex.withLock {
            val max = prefs.getInt("log_max_entries", 1000)

            if (buffer.size >= max) {
                buffer.removeAt(0)
            }

            val entry = LogEntry(counter++, text, level)
            buffer.add(entry)
            entry
        }
    }

    suspend fun clear() {
        mutex.withLock {
            buffer.clear()
        }
    }

    fun snapshotUnsafe(): List<LogEntry> {
        return if (mutex.tryLock()) {
            try {
                buffer.toList()
            } finally {
                mutex.unlock()
            }
        } else {
            emptyList()
        }
    }

}
class LogAdapter :
    RecyclerView.Adapter<LogAdapter.VH>() {

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    private val items = ArrayList<LogEntry>()

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

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tv.text = item.text

        val ctx = holder.tv.context

        val colorInt = when (item.level) {
            LogLevel.INFO -> ctx.getColor(R.color.sol_blue)
            LogLevel.ERROR -> ctx.getColor(R.color.sol_red)
            LogLevel.DEBUG -> {
                val ta = ctx.theme.obtainStyledAttributes(
                    intArrayOf(android.R.attr.textColorPrimary)
                )
                try {
                    ta.getColor(0, 0xFFAAAAAA.toInt())
                } finally {
                    ta.recycle()
                }
            }
        }

        holder.tv.setTextColor(colorInt)
    }

    fun append(entry: LogEntry) {
        items.add(entry)
        notifyItemInserted(items.size - 1)
    }

    fun clear() {
        val size = items.size
        items.clear()
        notifyItemRangeRemoved(0, size)
    }
}
class LogView(
    private val activity: MainActivity
) : FrameLayout(activity) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainScope = CoroutineScope(Dispatchers.Main)

    private val logAdapter = LogAdapter()

    private var stickToBottom = true
    private var userTouching = false

    var charsPerLine = 30

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

            charsPerLine = (availableWidthPx / sampleCharWidth).toInt().coerceAtLeast(20)

        }
    }

    private fun setupLogsView() {

        val rv = findViewById<RecyclerView>(R.id.rvLogs)

        rv.viewTreeObserver.addOnGlobalLayoutListener {
            updateLayoutMetrics(rv)
        }

        val btnClear = findViewById<Button>(R.id.btnClear)
        val btnUp = findViewById<Button>(R.id.btnUp)
        val btnDown = findViewById<Button>(R.id.btnDown)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnDownload = findViewById<Button>(R.id.btnDownload)

        rv.layoutManager = LinearLayoutManager(activity)
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
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy < 0) stickToBottom = false
            }
        })

        // ---- Buttons ----
        btnClear.setOnClickListener {
            scope.launch {
                activity.logRepo.clear()
                mainScope.launch {
                    logAdapter.clear()
                }
            }
        }

        btnUp.setOnClickListener {
            stickToBottom = false
            rv.scrollToPosition(0)
        }

        btnDown.setOnClickListener {
            stickToBottom = true
            scrollToBottom(rv)
        }

        btnSave.setOnClickListener {
            openSaveLogDialog()
        }

        btnDownload.setOnClickListener {
            scope.launch {
                val file = File(activity.getExternalFilesDir(null), "elm327emu_log.txt")
                file.writeText(logAdapterSnapshot())
            }
        }
    }

    // ---- PUBLIC APPEND API ----
    fun append(text: String, level: LogLevel = LogLevel.DEBUG) {

        val currentLevel = activity.prefs.getInt("log_level", LogLevel.INFO.ordinal)
        if (currentLevel < level.ordinal) return

        scope.launch {

            val entry = activity.logRepo.append(text, level)

            mainScope.launch {

                logAdapter.append(entry)

                // ---- ONLY auto-scroll if allowed ----
                if (stickToBottom && !userTouching) {
                    scrollToBottomSafe()
                }
            }
        }
    }

    // ---- SAFE SCROLL ----
    private fun scrollToBottomSafe() {
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

    private fun logAdapterSnapshot(): String {
        // simple export helper (no repo snapshot needed anymore)
        return ""
    }

    fun openSaveLogDialog() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "elm327emu_log.txt")
        }

        activity.saveLogLauncher.launch(intent)
    }
}