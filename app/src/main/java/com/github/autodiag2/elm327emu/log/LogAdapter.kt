package com.github.autodiag2.elm327emu.log

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.SimSignal
import com.github.autodiag2.elm327emu.sim.ecu.getName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogAdapter(
    private val context: Context
) : androidx.recyclerview.widget.RecyclerView.Adapter<LogAdapter.VH>() {

    private val prefs by lazy {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    }

    fun getString(resId: Int, vararg formatArgs: Any?): String {
        return context.getString(resId, *formatArgs.map { it ?: "" }.toTypedArray())
    }

    class VH(val tv: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(tv)

    var charsPerLine = 30
        set(value) {
            if (field == value) return
            field = value
            invalidateSearchIndex()
        }

    private val items = ArrayList<LogEntry>()
    private val positionById = HashMap<Long, Int>()
    private var searchRecordCache: Map<Long, SearchableEntry> = emptyMap()
    private val dirtyEntryIds = LinkedHashSet<Long>()
    private val deletedEntryIds = LinkedHashSet<Long>()
    private var indexedShowTimestamp: Boolean? = null
    private var indexedShowCount: Boolean? = null
    private var structuralRevision = 0L
    private var indexedStructuralRevision = -1L
    private var searchRequestGeneration = 0L
    private var appendSequence = 0L
    private var activeSearchAppendSequence: Long? = null
    private val appendedAfterActiveSearch = LinkedHashSet<Long>()
    private var searchQuery = ""
    private var matches: List<SearchMatch> = emptyList()
    private var matchedEntryIds: Set<Long> = emptySet()
    private var highlights: Map<HighlightFieldKey, List<HighlightRange>> = emptyMap()
    private var currentMatchIndex = -1

    internal data class SearchInput(
        val id: Long,
        val position: Int,
        val text: String,
        val data: ByteArray,
        val serial: ByteArray,
        val count: Int,
        val type: LogEntryType,
        val pendingSignal: Pair<SimSignal, Double>?,
        val timestamp: Long
    )

    internal data class SearchField(
        val id: Int,
        val text: String
    )

    internal data class SearchableEntry(
        val id: Long,
        val fields: List<SearchField>,
        val data: ByteArray,
        val byteDisplayRanges: List<List<IntRange>>
    )

    internal data class IndexedSearchEntry(
        val entry: SearchableEntry,
        val position: Int
    )

    internal data class SearchRequest(
        val query: String,
        val revision: Long,
        val requestGeneration: Long,
        val appendSequence: Long,
        val anchorPosition: Int,
        val incremental: Boolean,
        val retainPreviousMatches: Boolean,
        val items: List<LogEntry>?,
        val previousMatches: List<SearchMatch>,
        val previousMatchedEntryIds: Set<Long>,
        val previousHighlights: Map<HighlightFieldKey, List<HighlightRange>>,
        val previousCurrentMatchIndex: Int,
        val dirtyInputs: List<SearchInput>,
        val dirtyIds: Set<Long>,
        val cacheSnapshot: Map<Long, SearchableEntry>,
        val deletedIds: Set<Long>,
        val context: Context,
        val charsPerLine: Int,
        val showTimestamp: Boolean,
        val showCount: Boolean
    )

    internal data class SearchMatch(
        val entryId: Long,
        val position: Int,
        val field: Int,
        val ranges: List<IntRange>
    )

    internal data class HighlightFieldKey(
        val entryId: Long,
        val field: Int
    )

    internal data class HighlightRange(
        val start: Int,
        val endExclusive: Int,
        val matchIndex: Int
    )

    internal data class SearchResult(
        val query: String,
        val revision: Long,
        val requestGeneration: Long,
        val appendSequence: Long,
        val cache: Map<Long, SearchableEntry>,
        val matches: List<SearchMatch>,
        val matchedEntryIds: Set<Long>,
        val highlights: Map<HighlightFieldKey, List<HighlightRange>>,
        val currentMatchIndex: Int
    )

    internal data class SearchApplyResult(
        val accepted: Boolean,
        val position: Int?,
        val hasAppendedEntries: Boolean = false
    )

    private data class CardViews(
        val count: TextView,
        val badge: TextView,
        val timestamp: TextView,
        val content: TextView
    )

    private companion object {
        const val FIELD_BADGE = 1
        const val FIELD_TIMESTAMP = 2
        const val FIELD_CONTENT = 3
        const val FIELD_COUNT = 4
    }

    fun notifyChanged(entry: LogEntry) {
        val index = positionById[entry.id] ?: return
        if (needsSearchIndexTracking()) {
            dirtyEntryIds.add(entry.id)
        }
        structuralRevision++
        notifyItemChanged(index)
    }

    fun replace(entries: List<LogEntry>) {
        items.clear()
        items.addAll(entries)
        positionById.clear()
        rebuildPositions(0)
        searchRecordCache = emptyMap()
        dirtyEntryIds.clear()
        deletedEntryIds.clear()
        appendedAfterActiveSearch.clear()
        activeSearchAppendSequence = null
        structuralRevision++
        searchQuery = ""
        matches = emptyList()
        matchedEntryIds = emptySet()
        highlights = emptyMap()
        currentMatchIndex = -1
        indexedStructuralRevision = -1L
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
            val image = AppCompatImageView(parent.context).apply {
                setImageDrawable(
                    AppCompatResources.getDrawable(
                        context,
                        R.drawable.log_search_separator
                    )
                )
                scaleType = ImageView.ScaleType.CENTER
                layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(
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
                layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(
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
                gravity = Gravity.TOP
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
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val count = TextView(context).apply {
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                setPadding(
                    (4 * density).toInt(),
                    (3 * density).toInt(),
                    (4 * density).toInt(),
                    (3 * density).toInt()
                )
                layoutParams = LinearLayout.LayoutParams(
                    (40 * density).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val timestamp = TextView(context).apply {
                textSize = 11f
                alpha = 0.7f
                gravity = Gravity.TOP or Gravity.END
                maxLines = 1
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(
                    (100 * density).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            header.addView(badge)
            header.addView(count)
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

    private fun displayText(item: LogEntry): String {
        var text = item.text
        if (item.type != LogEntryType.NONE) {
            val data = if (item.serial.isEmpty()) item.data else item.serial
            if (data.isNotEmpty()) {
                text = dataLogFormat(data, data.size)
            }
        }
        return text
    }

    private fun timestampText(item: LogEntry): String {
        return if (prefs.getBoolean("log_timestamp", false)) {
            SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(item.ts))
        } else {
            ""
        }
    }

    private fun badgeText(item: LogEntry): String {
        val isReceived = item.type == LogEntryType.RECV
        var text = if (isReceived) {
            getString(R.string.log_main_data_received)
        } else {
            getString(R.string.log_main_data_sent)
        }
        val signal = item.pendingSignal
        if (item.type == LogEntryType.SENT && signal != null) {
            val signalText = getString(
                R.string.sim_ecu_log_signal,
                getName(context, signal.first),
                signal.second,
                signal.first.unit
            )
            text = "$text\n$signalText"
        }
        return text
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

        val contentText = displayText(item)
        val timestamp = timestampText(item)

        if (viewType == 2 && view is LinearLayout) {
            val cardViews = view.tag as CardViews
            val isReceived = item.type == LogEntryType.RECV
            val accentColor = ContextCompat.getColor(
                context,
                if (isReceived) R.color.sol_green else R.color.sol_blue
            )
            val showCount = item.count > 1 && prefs.getBoolean(
                LogRepository.PREF_LOG_GROUP,
                LogRepository.PREF_LOG_GROUP_DEFAULT
            )
            cardViews.count.text = highlightedText(
                if (showCount) "${item.count}" else "",
                item.id,
                FIELD_COUNT
            )
            cardViews.count.isVisible = showCount
            cardViews.count.setTextColor(resolveColor(android.R.attr.textColorSecondary))
            cardViews.badge.text = highlightedText(
                badgeText(item),
                item.id,
                FIELD_BADGE
            )
            cardViews.badge.setTextColor(accentColor)
            cardViews.badge.background = null
            cardViews.timestamp.text = highlightedText(
                timestamp,
                item.id,
                FIELD_TIMESTAMP
            )
            cardViews.timestamp.isVisible = timestamp.isNotEmpty()
            cardViews.content.text = highlightedText(
                contentText,
                item.id,
                FIELD_CONTENT
            )

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

        var displayText = contentText
        if (item.count > 1) {
            displayText = "×${item.count} $displayText"
        }
        if (timestamp.isNotEmpty()) {
            displayText = "$timestamp: $displayText"
        }
        if (view !is TextView) {
            return
        }
        view.text = highlightedText(displayText, item.id, FIELD_CONTENT)

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
        val previousSize = items.size
        val index = findInsertPosition(entry.id)
        items.add(index, entry)
        rebuildPositions(index)
        if (needsSearchIndexTracking()) {
            dirtyEntryIds.add(entry.id)
        }
        if (index == previousSize) {
            appendSequence++
            if (activeSearchAppendSequence != null) {
                appendedAfterActiveSearch.add(entry.id)
            }
        } else {
            structuralRevision++
        }
        notifyItemInserted(index)
    }

    fun remove(entry: LogEntry) {
        val index = positionById[entry.id] ?: return
        items.removeAt(index)
        positionById.remove(entry.id)
        dirtyEntryIds.remove(entry.id)
        if (needsSearchIndexTracking()) {
            deletedEntryIds.add(entry.id)
        }
        rebuildPositions(index)
        structuralRevision++
        notifyItemRemoved(index)
    }

    fun clear() {
        items.clear()
        positionById.clear()
        searchRecordCache = emptyMap()
        dirtyEntryIds.clear()
        deletedEntryIds.clear()
        appendedAfterActiveSearch.clear()
        activeSearchAppendSequence = null
        searchQuery = ""
        indexedStructuralRevision = -1L
        matches = emptyList()
        matchedEntryIds = emptySet()
        highlights = emptyMap()
        currentMatchIndex = -1
        structuralRevision++
        notifyDataSetChanged()
    }

    fun clearSearch() {
        searchRequestGeneration++
        activeSearchAppendSequence = null
        appendedAfterActiveSearch.clear()
        if (searchQuery.isEmpty() && matches.isEmpty()) return
        searchQuery = ""
        matches = emptyList()
        matchedEntryIds = emptySet()
        highlights = emptyMap()
        currentMatchIndex = -1
        notifyDataSetChanged()
    }

    fun matchCount(): Int = matches.size

    fun currentMatchNumber(): Int = if (currentMatchIndex >= 0) currentMatchIndex + 1 else 0

    fun moveMatch(direction: Int): Int? {
        if (matches.isEmpty()) return null
        val previousPosition = currentMatchPosition()
        currentMatchIndex = if (currentMatchIndex < 0) {
            if (direction < 0) matches.lastIndex else 0
        } else {
            (currentMatchIndex + direction + matches.size) % matches.size
        }
        val nextPosition = currentMatchPosition()
        previousPosition?.let { notifyItemChanged(it) }
        if (nextPosition != null && nextPosition != previousPosition) {
            notifyItemChanged(nextPosition)
        }
        return nextPosition
    }

    internal fun createSearchRequest(query: String, anchorPosition: Int): SearchRequest {
        val normalizedQuery = query.trim()
        val showTimestamp = prefs.getBoolean("log_timestamp", false)
        val showCount = prefs.getBoolean(
            LogRepository.PREF_LOG_GROUP,
            LogRepository.PREF_LOG_GROUP_DEFAULT
        )
        if ((indexedShowTimestamp != null && indexedShowTimestamp != showTimestamp) ||
            (indexedShowCount != null && indexedShowCount != showCount)
        ) {
            invalidateSearchIndex()
        }
        indexedShowTimestamp = showTimestamp
        indexedShowCount = showCount

        val pendingChanges = dirtyEntryIds.size + deletedEntryIds.size
        val incremental = indexedStructuralRevision == structuralRevision &&
            deletedEntryIds.isEmpty() &&
            canNarrowSearch(searchQuery, normalizedQuery) &&
            pendingChanges <= 4096 &&
            pendingChanges < (items.size / 2).coerceAtLeast(1)
        val retainPreviousMatches = incremental && searchQuery == normalizedQuery
        val dirtyInputs = if (incremental) {
            dirtyEntryIds.mapNotNull { id ->
                val position = positionById[id] ?: return@mapNotNull null
                searchInput(items[position], position)
            }
        } else {
            emptyList()
        }
        val fullRebuild = !incremental &&
            (dirtyEntryIds.size > 1024 || deletedEntryIds.size > 1024)
        searchRequestGeneration++
        activeSearchAppendSequence = appendSequence
        appendedAfterActiveSearch.clear()
        val cacheSnapshot = if (fullRebuild) emptyMap() else searchRecordCache
        val dirtySnapshot = if (fullRebuild) emptySet() else dirtyEntryIds.toSet()
        val deletedSnapshot = if (fullRebuild) emptySet() else deletedEntryIds.toSet()

        return SearchRequest(
            normalizedQuery,
            structuralRevision,
            searchRequestGeneration,
            appendSequence,
            anchorPosition,
            incremental,
            retainPreviousMatches,
            if (incremental) null else items.toList(),
            if (incremental) matches else emptyList(),
            if (retainPreviousMatches) matchedEntryIds else emptySet(),
            if (retainPreviousMatches) highlights else emptyMap(),
            if (retainPreviousMatches) currentMatchIndex else -1,
            dirtyInputs,
            dirtySnapshot,
            cacheSnapshot,
            deletedSnapshot,
            context.applicationContext,
            charsPerLine,
            showTimestamp,
            showCount
        )
    }

    internal suspend fun executeSearch(request: SearchRequest): SearchResult =
        withContext(Dispatchers.Default) {
            val records = ArrayList<IndexedSearchEntry>()
            var updatedCache: MutableMap<Long, SearchableEntry>? = null
            fun cacheEntry(id: Long, entry: SearchableEntry) {
                val cache = updatedCache ?: HashMap(request.cacheSnapshot).also {
                    updatedCache = it
                }
                cache[id] = entry
            }
            if (request.deletedIds.isNotEmpty()) {
                val cache = HashMap(request.cacheSnapshot)
                for (id in request.deletedIds) {
                    cache.remove(id)
                }
                updatedCache = cache
            }

            if (request.incremental) {
                if (!request.retainPreviousMatches) {
                    val addedIds = HashSet<Long>()
                    for (match in request.previousMatches) {
                        currentCoroutineContext().ensureActive()
                        if (!addedIds.add(match.entryId) || match.entryId in request.deletedIds ||
                            match.entryId in request.dirtyIds
                        ) {
                            continue
                        }
                        val cachedEntry = request.cacheSnapshot[match.entryId] ?: continue
                        records.add(IndexedSearchEntry(cachedEntry, match.position))
                    }
                }
                for (input in request.dirtyInputs) {
                    currentCoroutineContext().ensureActive()
                    val entry = buildSearchableEntry(input, request)
                    records.add(IndexedSearchEntry(entry, input.position))
                    cacheEntry(input.id, entry)
                }
            } else {
                val dirtyIds = request.dirtyIds
                val sourceItems = request.items ?: emptyList()
                for ((position, item) in sourceItems.withIndex()) {
                    if (position % 64 == 0) currentCoroutineContext().ensureActive()
                    if (item.type == LogEntryType.SEPARATOR) continue
                    val cachedEntry = if (item.id in dirtyIds) null else request.cacheSnapshot[item.id]
                    val entry = cachedEntry ?: buildSearchableEntry(
                        searchInput(item, position),
                        request
                    ).also {
                        cacheEntry(item.id, it)
                    }
                    records.add(IndexedSearchEntry(entry, position))
                }
            }

            val pattern = parseHexString(request.query)
            val resultMatches = ArrayList<SearchMatch>()
            if (request.retainPreviousMatches) {
                resultMatches.addAll(request.previousMatches)
            }
            val firstNewMatchIndex = resultMatches.size
            val lowerQuery = request.query

            if (lowerQuery.isNotEmpty()) {
                for ((index, indexed) in records.withIndex()) {
                    if (index % 64 == 0) currentCoroutineContext().ensureActive()
                    val entry = indexed.entry
                    var contentHasTextMatch = false
                    for (field in entry.fields) {
                        val fieldMatches = findTextMatches(field.text, lowerQuery)
                        if (field.id == FIELD_CONTENT && fieldMatches.isNotEmpty()) {
                            contentHasTextMatch = true
                        }
                        for (range in fieldMatches) {
                            resultMatches.add(
                                SearchMatch(entry.id, indexed.position, field.id, listOf(range))
                            )
                        }
                    }

                    if (pattern != null && pattern.isNotEmpty() &&
                        entry.data.isNotEmpty() && !contentHasTextMatch
                    ) {
                        addBinaryMatches(entry, indexed.position, pattern, resultMatches)
                    }
                }
            }

            val resultMatchedEntryIds = LinkedHashSet<Long>()
            val resultHighlights = HashMap<HighlightFieldKey, MutableList<HighlightRange>>()
            if (request.retainPreviousMatches) {
                resultMatchedEntryIds.addAll(request.previousMatchedEntryIds)
                for ((key, ranges) in request.previousHighlights) {
                    resultHighlights[key] = ArrayList(ranges)
                }
            }
            for (matchIndex in firstNewMatchIndex until resultMatches.size) {
                val match = resultMatches[matchIndex]
                resultMatchedEntryIds.add(match.entryId)
                val key = HighlightFieldKey(match.entryId, match.field)
                val ranges = resultHighlights.getOrPut(key) { ArrayList() }
                for (range in match.ranges) {
                    ranges.add(
                        HighlightRange(
                            range.first.coerceAtLeast(0),
                            range.last + 1,
                            matchIndex
                        )
                    )
                }
            }
            val initialMatchIndex = if (
                request.retainPreviousMatches && request.previousCurrentMatchIndex >= 0
            ) {
                request.previousCurrentMatchIndex
            } else {
                nearestMatchIndex(resultMatches, request.anchorPosition)
            }

            SearchResult(
                request.query,
                request.revision,
                request.requestGeneration,
                request.appendSequence,
                updatedCache ?: request.cacheSnapshot,
                resultMatches,
                resultMatchedEntryIds,
                resultHighlights,
                initialMatchIndex
            )
        }

    internal fun applySearchResult(result: SearchResult): SearchApplyResult {
        if (result.revision != structuralRevision ||
            result.requestGeneration != searchRequestGeneration
        ) {
            return SearchApplyResult(false, null)
        }

        val pendingAppends = if (activeSearchAppendSequence == result.appendSequence) {
            appendedAfterActiveSearch.toList()
        } else {
            emptyList()
        }
        searchRecordCache = result.cache
        dirtyEntryIds.clear()
        dirtyEntryIds.addAll(pendingAppends)
        deletedEntryIds.clear()
        appendedAfterActiveSearch.clear()
        activeSearchAppendSequence = null
        searchQuery = result.query
        indexedStructuralRevision = result.revision
        matches = result.matches
        matchedEntryIds = result.matchedEntryIds
        highlights = result.highlights
        currentMatchIndex = result.currentMatchIndex
        notifyDataSetChanged()
        return SearchApplyResult(true, currentMatchPosition(), pendingAppends.isNotEmpty())
    }

    private fun needsSearchIndexTracking(): Boolean {
        return searchQuery.isNotEmpty() || searchRecordCache.isNotEmpty() ||
            activeSearchAppendSequence != null
    }

    private fun searchInput(item: LogEntry, position: Int): SearchInput {
        return SearchInput(
            item.id,
            position,
            item.text,
            item.data,
            item.serial,
            item.count,
            item.type,
            item.pendingSignal,
            item.ts
        )
    }

    private fun canNarrowSearch(previous: String, next: String): Boolean {
        if (previous.isEmpty() || next.length < previous.length ||
            !next.startsWith(previous, ignoreCase = true)
        ) {
            return false
        }
        if (previous == next) return true
        val previousHex = parseHexString(previous)
        val nextHex = parseHexString(next)
        if (previousHex == null) return nextHex == null
        if (nextHex == null) return true
        if (nextHex.size < previousHex.size) return false
        for (index in previousHex.indices) {
            if (previousHex[index] != nextHex[index]) return false
        }
        return true
    }

    private fun invalidateSearchIndex() {
        searchRecordCache = emptyMap()
        dirtyEntryIds.clear()
        deletedEntryIds.clear()
        appendedAfterActiveSearch.clear()
        activeSearchAppendSequence = null
        searchQuery = ""
        indexedStructuralRevision = -1L
        matches = emptyList()
        matchedEntryIds = emptySet()
        highlights = emptyMap()
        currentMatchIndex = -1
        structuralRevision++
        notifyDataSetChanged()
    }

    private fun findInsertPosition(id: Long): Int {
        if (items.isEmpty() || items.last().id <= id) return items.size
        var low = 0
        var high = items.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (items[middle].id < id) low = middle + 1 else high = middle
        }
        return low
    }

    private fun rebuildPositions(start: Int) {
        for (position in start until items.size) {
            positionById[items[position].id] = position
        }
        positionById.entries.removeAll { it.value >= items.size }
    }

    private fun nearestMatchIndex(
        searchMatches: List<SearchMatch>,
        anchorPosition: Int
    ): Int {
        if (searchMatches.isEmpty()) return -1
        var low = 0
        var high = searchMatches.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (searchMatches[middle].position < anchorPosition) low = middle + 1 else high = middle
        }
        if (low >= searchMatches.size) return 0
        if (low == 0) return 0
        val afterPosition = searchMatches[low].position
        val beforePosition = searchMatches[low - 1].position
        return if (anchorPosition - beforePosition <= afterPosition - anchorPosition) {
            low - 1
        } else {
            low
        }
    }

    private fun currentMatchPosition(): Int? {
        return matches.getOrNull(currentMatchIndex)?.let { positionById[it.entryId] }
    }

    private fun highlightedText(
        text: String,
        entryId: Long,
        field: Int
    ): CharSequence {
        if (text.isEmpty()) return text
        val relevant = highlights[HighlightFieldKey(entryId, field)] ?: return text
        if (relevant.isEmpty()) return text

        val backgroundColor = resolveColor(android.R.attr.textColorPrimary)
        val foregroundColor = resolveColor(android.R.attr.colorBackground)
        val currentColor = ColorUtils.blendARGB(backgroundColor, Color.BLACK, 0.25f)

        val spannable = SpannableString(text)

        for (range in relevant) {
            val start = range.start.coerceAtLeast(0)
            val end = range.endExclusive.coerceAtMost(text.length)

            if (start < end) {
                val selected = range.matchIndex == currentMatchIndex

                spannable.setSpan(
                    BackgroundColorSpan(if (selected) currentColor else backgroundColor),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                spannable.setSpan(
                    ForegroundColorSpan(foregroundColor),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        return spannable
    }

    private fun buildSearchableEntry(
        input: SearchInput,
        request: SearchRequest
    ): SearchableEntry {
        val timestamp = if (request.showTimestamp) {
            SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(input.timestamp))
        } else {
            ""
        }
        val displayData = if (input.type == LogEntryType.NONE) {
            input.text
        } else {
            val source = if (input.serial.isEmpty()) input.data else input.serial
            if (source.isEmpty()) input.text
            else LogEntry.dataLogFormat(source, source.size, request.charsPerLine)
        }
        val fields = ArrayList<SearchField>(4)

        if (input.type == LogEntryType.RECV || input.type == LogEntryType.SENT) {
            var badge = request.context.getString(
                if (input.type == LogEntryType.RECV) {
                    R.string.log_main_data_received
                } else {
                    R.string.log_main_data_sent
                }
            )
            val signal = input.pendingSignal
            if (input.type == LogEntryType.SENT && signal != null) {
                val signalText = request.context.getString(
                    R.string.sim_ecu_log_signal,
                    getName(request.context, signal.first),
                    signal.second,
                    signal.first.unit
                )
                badge = "$badge\n$signalText"
            }
            fields.add(SearchField(FIELD_BADGE, badge))
            if (request.showCount && input.count > 1) {
                fields.add(SearchField(FIELD_COUNT, "${input.count}"))
            }
            if (timestamp.isNotEmpty()) {
                fields.add(SearchField(FIELD_TIMESTAMP, timestamp))
            }
            fields.add(SearchField(FIELD_CONTENT, displayData))
        } else {
            var displayText = displayData
            if (input.count > 1) {
                displayText = "×${input.count} $displayText"
            }
            if (timestamp.isNotEmpty()) {
                displayText = "$timestamp: $displayText"
            }
            fields.add(SearchField(FIELD_CONTENT, displayText))
        }

        val content = fields.firstOrNull { it.id == FIELD_CONTENT }?.text ?: ""
        val byteRanges = if (
            input.type == LogEntryType.RECV || input.type == LogEntryType.SENT
        ) {
            buildByteDisplayRanges(input.data, input.serial, request.charsPerLine)
        } else {
            emptyList()
        }

        return SearchableEntry(
            input.id,
            fields,
            if (input.type == LogEntryType.RECV || input.type == LogEntryType.SENT) input.data else ByteArray(0),
            if (content.isEmpty()) emptyList() else byteRanges
        )
    }

    private fun buildByteDisplayRanges(
        data: ByteArray,
        serial: ByteArray,
        charsPerLine: Int
    ): List<List<IntRange>> {
        if (data.isEmpty()) return emptyList()
        val result = ArrayList<List<IntRange>>(data.size)
        val decodedPositions = if (serial.isNotEmpty() && !serial.contentEquals(data)) {
            decodedHexSourcePositions(serial)
        } else {
            emptyList()
        }
        val source = if (serial.isNotEmpty()) serial else data
        for (byteIndex in data.indices) {
            val ranges = ArrayList<IntRange>()
            if (decodedPositions.size > byteIndex) {
                for (serialIndex in decodedPositions[byteIndex]) {
                    val displayIndex = formattedAsciiPosition(
                        serialIndex,
                        source.size,
                        charsPerLine
                    )
                    if (displayIndex >= 0) ranges.add(displayIndex..displayIndex)
                }
            } else {
                val hexStart = formattedHexPosition(byteIndex, source.size, charsPerLine)
                val asciiPosition = formattedAsciiPosition(byteIndex, source.size, charsPerLine)
                if (hexStart >= 0) ranges.add(hexStart..(hexStart + 1))
                if (asciiPosition >= 0) ranges.add(asciiPosition..asciiPosition)
            }
            result.add(ranges)
        }
        return result
    }

    private fun decodedHexSourcePositions(data: ByteArray): List<List<Int>> {
        val result = ArrayList<List<Int>>()
        var highIndex = -1
        fun hex(value: Int): Int = when (value) {
            in '0'.code..'9'.code -> value - '0'.code
            in 'A'.code..'F'.code -> value - 'A'.code + 10
            in 'a'.code..'f'.code -> value - 'a'.code + 10
            else -> -1
        }
        for (i in data.indices) {
            val value = data[i].toInt() and 0xff
            when (value) {
                '\r'.code, '\n'.code, ' '.code, '>'.code -> continue
            }
            if (hex(value) < 0) return emptyList()
            if (highIndex < 0) {
                highIndex = i
            } else {
                result.add(listOf(highIndex, i))
                highIndex = -1
            }
        }
        return if (highIndex < 0) result else emptyList()
    }

    private fun formattedHexPosition(
        byteIndex: Int,
        dataSize: Int,
        charsPerLine: Int
    ): Int {
        if (byteIndex < 0 || byteIndex >= dataSize) return -1
        val bytesPerLine = ((charsPerLine - 3) / 4).coerceIn(4, 64)
        val line = byteIndex / bytesPerLine
        val column = byteIndex % bytesPerLine
        val lineStart = line * (bytesPerLine * 4 + 4)
        return lineStart + 1 + column * 3
    }

    private fun formattedAsciiPosition(
        byteIndex: Int,
        dataSize: Int,
        charsPerLine: Int
    ): Int {
        if (byteIndex < 0 || byteIndex >= dataSize) return -1
        val bytesPerLine = ((charsPerLine - 3) / 4).coerceIn(4, 64)
        val line = byteIndex / bytesPerLine
        val column = byteIndex % bytesPerLine
        val lineStart = line * (bytesPerLine * 4 + 4)
        return lineStart + 1 + bytesPerLine * 3 + 2 + column
    }

    private fun parseHexString(text: String): ByteArray? {
        val hex = text.filter {
            !it.isWhitespace() && it != ':' && it != '-'
        }
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        if (!hex.all { it in '0'..'9' || it.uppercaseChar() in 'A'..'F' }) {
            return null
        }
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun findTextMatches(text: String, query: String): List<IntRange> {
        if (query.isEmpty() || text.isEmpty() || query.length > text.length) {
            return emptyList()
        }
        val result = ArrayList<IntRange>()
        var start = 0
        while (start <= text.length - query.length) {
            val index = text.indexOf(query, startIndex = start, ignoreCase = true)
            if (index < 0) break
            result.add(index..(index + query.length - 1))
            start = index + 1
        }
        return result
    }

    private fun addBinaryMatches(
        entry: SearchableEntry,
        position: Int,
        pattern: ByteArray,
        result: MutableList<SearchMatch>
    ) {
        if (pattern.isEmpty() || pattern.size > entry.data.size) return
        for (start in 0..entry.data.size - pattern.size) {
            var matchesPattern = true
            for (offset in pattern.indices) {
                if (entry.data[start + offset] != pattern[offset]) {
                    matchesPattern = false
                    break
                }
            }
            if (!matchesPattern) continue
            val ranges = ArrayList<IntRange>()
            for (byteIndex in start until start + pattern.size) {
                if (entry.byteDisplayRanges.size > byteIndex) {
                    ranges.addAll(entry.byteDisplayRanges[byteIndex])
                }
            }
            if (ranges.isNotEmpty()) {
                result.add(SearchMatch(entry.id, position, FIELD_CONTENT, ranges))
            }
        }
    }

}
