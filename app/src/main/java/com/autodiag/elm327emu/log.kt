package com.autodiag.elm327emu

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

class LogRepository(
    private val context: Context
) {

    public val LOG_MAX_ENTRIES = 1000
    private val buffer = ArrayList<LogEntry>()
    private val mutex = Mutex()
    private var counter = 0L
    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    @Volatile
    private var pagingSource: LogPagingSource? = null

    suspend fun append(text: String, level: LogLevel = LogLevel.DEBUG) {
        mutex.withLock {
            if (buffer.size >= prefs.getInt("log_max_entries", LOG_MAX_ENTRIES)) {
                buffer.removeAt(0)
            }
            buffer.add(LogEntry(counter++, text, level))
        }
        pagingSource?.invalidate()
    }

    suspend fun clear() {
        mutex.withLock {
            buffer.clear()
        }
        pagingSource?.invalidate()
    }

    fun snapshotUnsafe(): List<LogEntry> {
        if (mutex.tryLock()) {
            try {
                return buffer.toList()
            } finally {
                mutex.unlock()
            }
        }
        return emptyList()
    }

    suspend fun snapshot(): List<LogEntry> =
        mutex.withLock {
            buffer.toList()
        }

    fun pager(): Pager<Int, LogEntry> =
        Pager(
            PagingConfig(
                pageSize = LogPagingSource.PAGE_SIZE,
                enablePlaceholders = false
            )
        ) {
            LogPagingSource(snapshotUnsafe()).also {
                pagingSource = it
            }
        }
}

class LogAdapter :
    PagingDataAdapter<LogEntry, LogAdapter.VH>(DIFF) {

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = TextView(parent.context).apply {
            setPadding(16, 8, 16, 8)
        }
        return VH(tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position) ?: return
        holder.tv.text = item.text

        val ctx = holder.tv.context

        val colorInt = when (item.level) {
            LogLevel.INFO -> ctx.getColor(R.color.sol_blue)
            LogLevel.ERROR -> ctx.getColor(R.color.sol_red)
            LogLevel.DEBUG -> {
                val ta = ctx.theme.obtainStyledAttributes(
                    intArrayOf(android.R.attr.textColor)
                )
                try {
                    ta.getColor(0, 0)
                } finally {
                    ta.recycle()
                }
            }
        }

        holder.tv.setTextColor(colorInt)
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<LogEntry>() {
            override fun areItemsTheSame(a: LogEntry, b: LogEntry) = a.id == b.id
            override fun areContentsTheSame(a: LogEntry, b: LogEntry) = a == b
        }
    }
}

class LogView(
    private val activity: MainActivity
) : FrameLayout(activity) {
    
    private var stickToBottom = false
    private lateinit var logAdapter: LogAdapter
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val saveLogLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri = result.data?.data ?: return@registerForActivityResult
                scope.launch(Dispatchers.IO) {
                    activity.contentResolver.openOutputStream(uri)?.use { out ->
                        val text = activity.logRepo.snapshotUnsafe().joinToString("\n") { it.text }
                        out.write(text.toByteArray())
                    }
                }
            }
        }

    public fun build() {
        logAdapter = LogAdapter()

        val vertical = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        fun buildButtons(): LinearLayout =
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16, 16, 16, 16)

                addView(Button(activity).apply {
                    text = "Download log"
                    setOnClickListener {
                        scope.launch {
                            val file = File(activity.getExternalFilesDir(null), "elm327emu_log.txt")
                            file.writeText(activity.logRepo.snapshotUnsafe().joinToString("\n") { it.text })
                            append("Log written to: ${file.absolutePath}", LogLevel.INFO)
                        }
                    }
                })

                addView(Button(activity).apply {
                    text = "Save as..."
                    setOnClickListener { openSaveLogDialog() }
                })

                addView(Button(activity).apply {
                    text = "Clear log"
                    setOnClickListener {
                        scope.launch {
                            activity.logRepo.clear()
                        }
                    }
                })
            }

        val buttons = buildButtons()
        vertical.addView(
            buttons,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val rv = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity).apply {
                stackFromEnd = false
            }
            adapter = logAdapter
            itemAnimator = null

            isFocusable = true
            isFocusableInTouchMode = true
        }
        rv.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (dy < 0) {
                        stickToBottom = false
                    }
                }
            }
        )

        vertical.addView(
            rv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val overlayButtons = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(8, 8, 8, 8)
            elevation = activity.dpToPx(6).toFloat()

            addView(Button(activity).apply {
                text = "↑"
                setOnClickListener {
                    stickToBottom = false
                    (rv.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(0, 0)
                }
            })

            addView(Button(activity).apply {
                text = "↓"
                setOnClickListener {
                    stickToBottom = true
                    val count = logAdapter.itemCount
                    if (count > 0) {
                        rv.scrollToPosition(count - 1)
                    }
                }
            })
        }

        this.addView(
            overlayButtons,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END or Gravity.BOTTOM
                marginEnd = activity.dpToPx(8)
                bottomMargin = activity.dpToPx(8)
            }
        )

        this.addView(vertical)

        activity.lifecycleScope.launch {
            activity.logRepo.pager()
                .flow
                .cachedIn(this)
                .collectLatest { pagingData ->
                    if (stickToBottom) {
                        logAdapter.submitData(pagingData)
                        rv.post {
                            val count = logAdapter.itemCount
                            if (count > 0) rv.scrollToPosition(count - 1)
                        }
                    } else {
                        val anchor = captureScrollAnchor(rv)
                        logAdapter.submitData(pagingData)
                        restoreScrollAnchor(rv, anchor)
                    }
                }
        }
    }
    
    private fun captureScrollAnchor(rv: RecyclerView): Pair<Int, Int>? {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return null
        val pos = lm.findFirstVisibleItemPosition()
        if (pos == RecyclerView.NO_POSITION) return null
        val view = rv.getChildAt(0) ?: return null
        return pos to view.top
    }

    private fun restoreScrollAnchor(
        rv: RecyclerView,
        anchor: Pair<Int, Int>?
    ) {
        if (anchor == null) return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        rv.post {
            lm.scrollToPositionWithOffset(anchor.first, anchor.second)
        }
    }

    public fun openSaveLogDialog() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "elm327emu_log.txt")
        }
        saveLogLauncher.launch(intent)
    }

    fun append(text: String, level: LogLevel = LogLevel.DEBUG) {
        val currentLogLevel = activity.prefs.getInt("log_level", LogLevel.INFO.ordinal)
        if (currentLogLevel < level.ordinal) return

        scope.launch {
            activity.logRepo.append(text, level)
            withContext(Dispatchers.Main) {
                logAdapter.refresh()
            }
        }
    }

}