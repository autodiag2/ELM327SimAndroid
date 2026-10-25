package com.github.autodiag2.elm327emu

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView

class StatsController() {
    private var requestCount = 0
    private var responseCount = 0
    private val startTime = System.currentTimeMillis()
    private var view: StatsView? = null

    // count per command
    private val requestStats = mutableMapOf<String, Int>()

    fun attachView(view: StatsView?) {
        this.view = view
        refresh()
    }

    fun detachView() {
        this.view = null
    }

    fun onDataReceived(data: ByteArray, size_used: Int) {
        requestCount++

        val cmd = extractCommand(data, size_used)
        if (cmd != null) {
            requestStats[cmd] = (requestStats[cmd] ?: 0) + 1
        }
        refresh()
    }

    fun onDataSent(data: ByteArray, size_used: Int) {
        responseCount++
        refresh()
    }

    fun refresh() {
        val view = this.view
        if ( view != null ) {
            val elapsedMs = System.currentTimeMillis() - startTime
            view.maybeUpdateUI(requestStats, requestCount, responseCount, elapsedMs)
        }
    }

    private fun extractCommand(data: ByteArray, size: Int): String? {
        return try {
            val raw = String(data, 0, size).trim()

            // normalize typical ELM commands
            raw
                .replace("\r", "")
                .replace("\n", "")
                .uppercase()
                .takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

}
class StatsView(
    context: Context
) : LinearLayout(context) {

    private val requestsText: TextView
    private val responsesText: TextView
    private val qpsText: TextView
    private val topContainer: LinearLayout
    private var lastUiUpdate = 0L
    private val uiIntervalMs = 300L

    fun getString(resId: Int, vararg formatArgs: Any?): String {
        return context.getString(resId, *formatArgs.map { it ?: "" }.toTypedArray())
    }

    init {
        inflate(context, R.layout.stats, this)

        requestsText = findViewById(R.id.stats_requests)
        responsesText = findViewById(R.id.stats_responses)
        qpsText = findViewById(R.id.stats_qps)
        topContainer = findViewById(R.id.stats_top_requests)
        maybeUpdateUI()
    }

    private fun runOnUi(block: () -> Unit) {
        post { block() }
    }

    fun maybeUpdateUI(requestStats: MutableMap<String, Int> = mutableMapOf(), requestCount: Int = 0, responseCount: Int = 0, elapsedMs: Long = 0L) {
        val now = System.currentTimeMillis()
        if (now - lastUiUpdate > uiIntervalMs) {
            lastUiUpdate = now
            runOnUi {
                updateUI(requestStats, requestCount, responseCount, elapsedMs)
            }
        }
    }

    private fun updateUI(requestStats: MutableMap<String, Int> = mutableMapOf(), requestCount: Int = 0, responseCount: Int = 0, elapsedMs: Long = 0L) {
        requestsText.text = getString(R.string.stats_requests, requestCount)
        responsesText.text = getString(R.string.stats_responses, responseCount)

        val elapsedSec = (elapsedMs / 1000.0)
            .coerceAtLeast(1.0)

        val qps = requestCount / elapsedSec
        qpsText.text = getString(R.string.stats_qps, qps)

        updateTopRequests(requestStats, requestCount)
    }

    private fun updateTopRequests(requestStats: MutableMap<String, Int> = mutableMapOf(), requestCount: Int = 0) {
        topContainer.removeAllViews()

        val top = requestStats.entries
            .sortedByDescending { it.value }
            .take(5)

        val total = requestCount.coerceAtLeast(1)

        for ((cmd, count) in top) {
            val percent = (count * 100f / total)
            val tv = TextView(context).apply {
                text = "%s : %d (%.1f%%)".format(cmd, count, percent)
            }
            topContainer.addView(tv)
        }
    }

}