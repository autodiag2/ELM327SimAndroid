package com.github.autodiag2.elm327emu.log

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LogView(
    private val activity: MainActivity
) : FrameLayout(activity) {

    companion object {
        val OVERLAY_BUTTONS_ANIM_MS: Long = 1000
        private const val SEARCH_DEBOUNCE_MS = 180L
        private const val SEARCH_UPDATE_DEBOUNCE_MS = 80L
    }

    val prefs by lazy {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    }

    private val mainScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    var onSearchChanged: ((search: String) -> Unit)? = null
    var onWidthChanged: (() -> Unit)? = null
    lateinit var btnSearch: EditText

    val logAdapter = LogAdapter(context)
    private lateinit var logTopBar: View
    private var logTopBarExpanded = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var overlayButtons: View
    private lateinit var searchInput: EditText
    private lateinit var previousMatchButton: Button
    private lateinit var nextMatchButton: Button
    private var searchText = ""
    private var searchJob: Job? = null
    private var searchGeneration = 0L

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
    var stickToBottom = true
    var userTouching = false

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
            val charsPerLine =
                (availableWidthPx / sampleCharWidth).toInt().coerceAtLeast(20)
            if (charsPerLine != logAdapter.charsPerLine) {
                logAdapter.charsPerLine = charsPerLine
                onWidthChanged?.invoke()
            }
        }
    }

    private fun createMatchButton(label: String, description: String): Button {
        val density = resources.displayMetrics.density
        return Button(context).apply {
            text = label
            textSize = 20f
            isAllCaps = false
            setMinWidth(0)
            setMinimumWidth(0)
            setMinHeight(0)
            setMinimumHeight(0)
            contentDescription = description
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                (40 * density).toInt(),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    private fun addMatchNavigationButtons() {
        val parent = searchInput.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(searchInput)
        if (index < 0) return
        val originalLayoutParams = searchInput.layoutParams
        val inputHeight = originalLayoutParams.height
        val originalId = searchInput.id
        parent.removeView(searchInput)
        val container = LinearLayout(context).apply {
            id = originalId
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = originalLayoutParams
        }
        searchInput.id = View.generateViewId()
        parent.addView(container, index)
        container.addView(
            searchInput,
            LinearLayout.LayoutParams(0, inputHeight, 1f)
        )
        previousMatchButton = createMatchButton("‹", "Previous match")
        nextMatchButton = createMatchButton("›", "Next match")
        container.addView(previousMatchButton)
        container.addView(nextMatchButton)
        previousMatchButton.setOnClickListener {
            navigateToMatch(-1)
        }
        nextMatchButton.setOnClickListener {
            navigateToMatch(1)
        }
    }

    private fun updateMatchNavigationState() {
        val enabled = logAdapter.matchCount() > 0
        previousMatchButton.isEnabled = enabled
        nextMatchButton.isEnabled = enabled
        val count = logAdapter.matchCount()
        val current = logAdapter.currentMatchNumber()
        previousMatchButton.contentDescription = if (count > 0) {
            "Previous match, $current of $count"
        } else {
            "Previous match"
        }
        nextMatchButton.contentDescription = if (count > 0) {
            "Next match, $current of $count"
        } else {
            "Next match"
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
        addMatchNavigationButtons()

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

        updateMatchNavigationState()
    }

    fun search(text: String) {
        scheduleSearch(text, SEARCH_DEBOUNCE_MS)
    }

    fun refreshSearch() {
        if (searchText.isBlank() || searchJob?.isActive == true) return
        scheduleSearch(
            searchText,
            SEARCH_UPDATE_DEBOUNCE_MS,
            scrollToMatch = false
        )
    }

    fun cancelSearch() {
        searchGeneration++
        searchJob?.cancel()
        searchJob = null
        logAdapter.clearSearch()
        updateMatchNavigationState()
    }

    private fun scheduleSearch(
        text: String,
        debounceMs: Long,
        scrollToMatch: Boolean = true
    ) {
        searchText = text
        searchGeneration++
        val generation = searchGeneration
        searchJob?.cancel()
        searchJob = null

        if (text.isBlank()) {
            logAdapter.clearSearch()
            updateMatchNavigationState()
            return
        }

        searchJob = mainScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val rv = findViewById<RecyclerView>(R.id.rvLogs)
            val anchorPosition = (rv.layoutManager as? LinearLayoutManager)
                ?.findFirstVisibleItemPosition()
                ?.coerceAtLeast(0) ?: 0
            val request = logAdapter.createSearchRequest(text, anchorPosition)
            val result = logAdapter.executeSearch(request)
            if (!isActive || generation != searchGeneration) return@launch

            val applied = logAdapter.applySearchResult(result)
            if (!applied.accepted) {
                scheduleSearch(searchText, SEARCH_UPDATE_DEBOUNCE_MS)
                return@launch
            }

            updateMatchNavigationState()
            if (scrollToMatch && applied.position != null) {
                stickToBottom = false
                rv.scrollToPosition(applied.position)
            }

            if (applied.hasAppendedEntries) {
                scheduleSearch(
                    searchText,
                    SEARCH_UPDATE_DEBOUNCE_MS,
                    scrollToMatch = false
                )
            }
        }
    }

    private fun navigateToMatch(direction: Int) {
        val position = logAdapter.moveMatch(direction)
        updateMatchNavigationState()
        if (position != null) {
            stickToBottom = false
            findViewById<RecyclerView>(R.id.rvLogs).scrollToPosition(position)
        }
    }

    fun toggleTopBar() {
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

    fun refresh(result: AppendResult? = null, entries: List<LogEntry>? = null) {
        mainScope.launch {
            if (entries != null) {
                logAdapter.replace(entries)
            } else if (result != null) {
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
            }
            updateMatchNavigationState()
            if (searchText.isNotBlank()) {
                refreshSearch()
            } else if (stickToBottom && !userTouching) {
                scrollToBottomSafe()
            }
        }
    }
}
