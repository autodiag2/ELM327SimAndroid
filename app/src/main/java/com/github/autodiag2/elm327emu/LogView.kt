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
import android.widget.Button
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

enum class LogLevel(val value: Int) {
    NONE(0),
    ERROR(1),
    WARNING(2),
    INFO(3),
    DEBUG(4)
}

val LogLevel_DEFAULT = LogLevel.DEBUG

data class LogEntry(
    val id: Long,
    val text: String,
    val level: LogLevel,
    val data: ByteArray,
    var match: Boolean = false
)

private fun parseHexString(text: String): ByteArray? {
    val hex = text.filter {
        !it.isWhitespace() && it != ':' && it != '-'
    }

    if (hex.length % 2 != 0) return null
    if (!hex.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) return null

    return ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

private fun containsSubArray(data: ByteArray, pattern: ByteArray): Boolean {
    if (pattern.isEmpty()) return true
    if (pattern.size > data.size) return false

    outer@ for (i in 0 .. data.size - pattern.size) {
        for (j in pattern.indices) {
            if (data[i + j] != pattern[j]) {
                continue@outer
            }
        }
        return true
    }

    return false
}

fun search_match(entry: LogEntry, search: String): Boolean {
    if (entry.text.contains(search, ignoreCase = true)) {
        return true
    }

    val pattern = parseHexString(search) ?: return false

    return containsSubArray(entry.data, pattern)
}
class LogRepository(private val context: Context) {

    private val buffer = ArrayList<LogEntry>()
    private val mutex = Mutex()
    private var counter = 0L

    suspend fun search(text: String): List<LogEntry> {
        return mutex.withLock {

            if (text.isBlank()) {
                return@withLock buffer.toList()
            }

            val result = ArrayList<LogEntry>()
            val added = HashSet<Long>()

            val showSz = 2

            for (i in buffer.indices) {

                if (!search_match(buffer[i], text))
                    continue

                val first = maxOf(0, i - showSz)
                val last = minOf(buffer.lastIndex, i + showSz)

                val previousMatchInWindow =
                    (first until i).any { search_match(buffer[it], text) }

                if (!previousMatchInWindow) {
                    result += LogEntry(
                        id = -1,
                        text = "======================================",
                        level = LogLevel.INFO,
                        data = ByteArray(0)
                    )
                }

                for (j in first..last) {
                    if (added.add(buffer[j].id)) {
                        buffer[j].match = (i == j)
                        result += buffer[j]
                    }
                }

                val nextMatchInWindow =
                    (i + 1..last).any { search_match(buffer[it], text) }

                if (!nextMatchInWindow) {
                    result += LogEntry(
                        id = -2,
                        text = "======================================",
                        level = LogLevel.INFO,
                        data = ByteArray(0)
                    )
                }
            }

            return result
        }
    }

    suspend fun append(text: String, level: LogLevel = LogLevel.DEBUG, data: ByteArray? = null, size_used: Int = data?.size ?: 0): LogEntry {
        return mutex.withLock {

            val entry = LogEntry(
                counter++, text, level,
                data = data?.copyOf(size_used) ?: ByteArray(0),
            )
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

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tv.text = item.text

        val ctx = holder.tv.context

        val colorInt = when (item.level) {
            LogLevel.INFO -> ctx.getColor(R.color.sol_blue)
            LogLevel.ERROR -> ctx.getColor(R.color.sol_red)
            LogLevel.WARNING -> ctx.getColor(R.color.sol_orange)
            LogLevel.NONE -> ctx.getColor(R.color.sol_magenta)
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

    val logRepo: LogRepository = LogRepository(activity)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private var search = ""

    private val logAdapter = LogAdapter()

    val saveLogLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri = result.data?.data ?: return@registerForActivityResult
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    activity.contentResolver.openOutputStream(uri)?.use { out ->
                        val text = logRepo.snapshotUnsafe()
                            .joinToString("\n") { it.text }
                        out.write(text.toByteArray())
                    }
                }
            }
        }

    private var stickToBottom = true
    private var userTouching = false

    var charsPerLine = 30

    init {
        LayoutInflater.from(context).inflate(R.layout.log, this, true)
        setupLogsView()
    }

    private fun refresh() {
        scope.launch {
            val entries = logRepo.search(search)
            mainScope.launch {
                logAdapter.replace(entries)
                if (stickToBottom && search.isBlank()) {
                    scrollToBottomSafe()
                }
            }
        }
    }

    private fun decodeHexAscii(data: ByteArray, length: Int): ByteArray? {
        val out = ArrayList<Byte>()

        var hi = -1

        fun hex(c: Int): Int = when (c) {
            in '0'.code..'9'.code -> c - '0'.code
            in 'A'.code..'F'.code -> c - 'A'.code + 10
            in 'a'.code..'f'.code -> c - 'a'.code + 10
            else -> -1
        }

        for (i in 0 until length) {
            val b = data[i].toInt() and 0xff

            when (b) {
                '\r'.code, '\n'.code, ' '.code, '>'.code -> continue
            }

            val v = hex(b)
            if (v < 0) {
                return null
            }

            if (hi < 0) {
                hi = v
            } else {
                out += ((hi shl 4) or v).toByte()
                hi = -1
            }
        }

        if (hi >= 0) {
            return null
        }

        return out.toByteArray()
    }

    enum class DataType {
        RECV, SENT
    }

    fun logData(
        type: DataType,
        data: ByteArray,
        length: Int = data.size
    ) {
        val binary = decodeHexAscii(data, length)
            ?: ByteArray(0)
        
        append(
            activity.getString(
                if (type == DataType.RECV)
                    R.string.log_main_data_received
                else
                    R.string.log_main_data_sent,
                dataLogFormat(data, length)
            ),
            LogLevel.DEBUG,
            binary,
            binary.size
        )
    }

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
        
        val btnSearch = findViewById<EditText>(R.id.log_search)

        btnSearch.doAfterTextChanged {
            search = it?.toString() ?: ""
            refresh()
        }

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
                logRepo.clear()
                mainScope.launch {
                    refresh()
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
    fun append(text: String, level: LogLevel = LogLevel.DEBUG, data: ByteArray? = null, size_used: Int = data?.size ?: 0) {

        val currentLevel = activity.prefs.getInt("log_level", LogLevel_DEFAULT.ordinal)
        if (currentLevel < level.ordinal) return

        scope.launch {

            val entry = logRepo.append(text, level, data, size_used)

            mainScope.launch {

                if (search.isBlank()) {
                    logAdapter.append(entry)
                } else {
                    refresh()
                }

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

        saveLogLauncher.launch(intent)
    }
}