package com.github.autodiag2.elm327emu

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView

class StatsView(
    context: Context
) : LinearLayout(context) {

    private val requestsText: TextView
    private val responsesText: TextView
    private val qpsText: TextView
    private val topContainer: LinearLayout

    private var requestCount = 0
    private var responseCount = 0

    private val startTime = System.currentTimeMillis()

    // count per command
    private val requestStats = mutableMapOf<String, Int>()

    init {
        inflate(context, R.layout.view_stats, this)

        requestsText = findViewById(R.id.stats_requests)
        responsesText = findViewById(R.id.stats_responses)
        qpsText = findViewById(R.id.stats_qps)
        topContainer = findViewById(R.id.stats_top_requests)
    }

    private fun runOnUi(block: () -> Unit) {
        post { block() }
    }

    private var lastUiUpdate = 0L
    private val uiIntervalMs = 300L

    private fun maybeUpdateUI() {
        val now = System.currentTimeMillis()
        if (now - lastUiUpdate > uiIntervalMs) {
            lastUiUpdate = now
            runOnUi {
                updateUI()
            }
        }
    }

    // --- PUBLIC API ---

    fun onDataReceived(data: ByteArray, size_used: Int) {
        requestCount++

        val cmd = extractCommand(data, size_used)
        if (cmd != null) {
            requestStats[cmd] = (requestStats[cmd] ?: 0) + 1
        }

        maybeUpdateUI()
    }

    fun onDataSent(data: ByteArray, size_used: Int) {
        responseCount++
        maybeUpdateUI()
    }

    // --- INTERNALS ---

    private fun updateUI() {
        requestsText.text = "Requests: $requestCount"
        responsesText.text = "Responses: $responseCount"

        val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000f)
            .coerceAtLeast(1f)

        val qps = requestCount / elapsedSec
        qpsText.text = "Queries/s: %.2f".format(qps)

        updateTopRequests()
    }

    private fun updateTopRequests() {
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