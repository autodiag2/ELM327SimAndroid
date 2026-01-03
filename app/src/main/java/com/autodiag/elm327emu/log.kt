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

class LogRepository {

    private val buffer = ArrayList<LogEntry>()
    private val mutex = Mutex()
    private var counter = 0L

    suspend fun append(text: String, level: LogLevel) {
        mutex.withLock {
            buffer.add(LogEntry(counter++, text, level))
        }
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

    suspend fun pager(): Pager<Int, LogEntry> =
        Pager(
            PagingConfig(
                pageSize = LogPagingSource.PAGE_SIZE,
                enablePlaceholders = false
            )
        ) {
            LogPagingSource(snapshotUnsafe())
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
