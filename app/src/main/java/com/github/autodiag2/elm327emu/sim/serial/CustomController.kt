package com.github.autodiag2.elm327emu.sim.serial

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.setPadding
import org.json.JSONArray
import org.json.JSONObject
import com.github.autodiag2.elm327emu.R
import android.util.Log
import com.github.autodiag2.elm327emu.BuildConfig
import com.github.autodiag2.elm327emu.ui.JsonConfigurable
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.LogLevel
import com.github.autodiag2.elm327emu.LogEntry
import com.github.autodiag2.elm327emu.sim.EmuInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import android.widget.ImageButton

const val SCHEMA = "autodiag/sim/elm327/serialscript"
const val VERSION = 1.0

class CustomController(
    public val activity: MainActivity,
    private val listener: CustomController.Listener? = null
) : LinearLayout(activity),
    CustomView.Listener,
    JsonConfigurable,
    StateMachine.Listener {

    interface Listener {
        abstract fun customSerialOnRunStateChange(newState: Boolean)
    }

    public var view: CustomView

    private val stateMachine =
        StateMachine(
            this,
            this
        )

    private val scope =
        CoroutineScope(
            Dispatchers.IO +
                SupervisorJob()
        )

    private val logReplay = LogReplay(emuProvider = stateMachine, activity = activity, scope = scope)

    private lateinit var replayToolbarContent: View
    private lateinit var replayToolbarArrow: TextView
    private lateinit var replaySpeed: SeekBar
    private lateinit var replaySpeedValue: TextView
    private lateinit var replayStart: ImageButton
    private lateinit var replayToolbar: View

    public val blocks =
        mutableListOf<BlockController>()

    public val links =
        mutableListOf<LinkController>()

    val selectedBlocks =
        mutableSetOf<Int>()

    val selectedLinks =
        mutableSetOf<Pair<Int, Int>>()

    init {
        orientation = VERTICAL

        LayoutInflater.from(activity).inflate(
            R.layout.sim_custom_serial_screen,
            this,
            true
        )

        view =
            findViewById(
                R.id.custom_serial_view
            )

        view.model = this

        setupReplayToolbar()
    }

    fun isRunning(): Boolean {
        return stateMachine.isRunning()
    }

    // ------------ Replay ------------

    fun setReplayEntriesProvider(
        provider: () -> List<LogEntry>
    ) {
        logReplay.logEntriesProvider = provider
    }

    private fun updateReplayToolbarArrow() {
        if (replayToolbarContent.visibility == View.VISIBLE) {
            replayToolbarContent.post {
                replayToolbarArrow.translationY =
                    replayToolbar.height.toFloat()
            }
        } else {
            replayToolbarArrow.translationY = 0f
        }
    }

    private fun setupReplayToolbar() {
        replayToolbar = findViewById(R.id.custom_serial_replay_toolbar)
        replayToolbarContent =
            findViewById(
                R.id.custom_serial_replay_toolbar_content
            )

        replayToolbarArrow =
            findViewById(
                R.id.custom_serial_replay_toolbar_arrow
            )

        replaySpeed =
            findViewById(
                R.id.custom_serial_replay_speed
            )

        replaySpeedValue =
            findViewById(
                R.id.custom_serial_replay_speed_value
            )

        replayStart =
            findViewById(
                R.id.custom_serial_replay_start
            )

        replayToolbarArrow.setOnClickListener {
            val expanded = replayToolbarContent.visibility != View.VISIBLE

            replayToolbarContent.visibility =
                if (expanded) View.VISIBLE else View.GONE

            replayToolbarArrow.setText(
                if (expanded) {
                    R.string.custom_serial_toolbar_arrow_up
                } else {
                    R.string.custom_serial_toolbar_arrow_down
                }
            )

            updateReplayToolbarArrow()
        }

        replaySpeed.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {

                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    replaySpeedValue.text =
                        formatReplaySpeed(
                            replaySpeedToValue(
                                progress
                            )
                        )
                }

                override fun onStartTrackingTouch(
                    seekBar: SeekBar?
                ) {
                }

                override fun onStopTrackingTouch(
                    seekBar: SeekBar?
                ) {
                }
            }
        )

        replaySpeedValue.text =
            formatReplaySpeed(
                replaySpeedToValue(
                    replaySpeed.progress
                )
            )

        replayStart.setOnClickListener {
            if (logReplay.isRunning()) {
                stateMachine.stop()
                logReplay.stop()
                listener?.customSerialOnRunStateChange(false)
                replayUpdatePlayPause()
            } else {
                stateMachine.stop()
                listener?.customSerialOnRunStateChange(false)
                val speed = replaySpeedToValue(replaySpeed.progress)
                scope.launch {   
                    val emuProvider = activity.bridgeOrchestrator as EmuInterface.Provider
                    emuProvider.resetEmu()
                    stateMachine.start()
                    logReplay.start(
                        playSpeed = speed,
                        onFinished = {
                            listener?.customSerialOnRunStateChange(true)
                        },
                        onError = { error ->
                            logDebug(
                                "Replay error: ${error.message}"
                            )

                            activity.runOnUiThread {
                                replayUpdatePlayPause()

                                Toast.makeText(
                                    activity,
                                    getString(
                                        R.string.custom_serial_replay_error,
                                        error.message ?: ""
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    )
                    replayUpdatePlayPause()
                }
            }
        }
        updateReplayToolbarArrow()
    }

    private fun replayUpdatePlayPause() {
        activity.runOnUiThread {
            if (logReplay.isRunning()) {
                replayStart.setImageResource(R.drawable.ic_pause)
                replayStart.contentDescription =
                    getString(R.string.custom_serial_replay_start)
            } else {
                replayStart.setImageResource(R.drawable.ic_play)
                replayStart.contentDescription =
                    getString(R.string.custom_serial_replay_stop)
            }
        }
    }

    private fun replaySpeedToValue(
        progress: Int
    ): Double {
        /*
         * 0   -> 0.25x
         * 50  -> 1.00x
         * 100 -> 4.00x
         */
        return 0.25 *
            Math.pow(
                16.0,
                progress.coerceIn(0, 100) / 100.0
            )
    }

    private fun formatReplaySpeed(
        speed: Double
    ): String {
        return when {
            speed < 1.0 ->
                "x%.2f".format(speed)

            speed < 2.0 ->
                "x%.1f".format(speed)

            else ->
                "x%.1f".format(speed)
        }
    }

    // ------------ Data change ------------

    /**
     * Called whenever the script model has changed.
     *
     * This is deliberately separate from selection and runtime
     * state changes.
     */
    public fun onDataChanged() {
        stateMachine.customModelChanged()
        view.refresh()
    }

    // ------------ listener state machine ------------

    override fun onBlockStateChanged(
        block: BlockController,
        state: BlockController.State
    ) {
        activity.runOnUiThread {
            view.refresh()
        }
    }

    fun resetBlockStates() {
        for(block in blocks) {
            block.resetState()
        }
        view.refresh()
    }

    // ------------ Listeners of view ------------

    override fun onElementClicked(
        element: ElementView<*>
    ) {
        if ( element is BlockView ) {
            element.model!!.edit()
        } else if ( element is LinkView ) {
            toggleLinkSelection(element.model!!)
        }
    }

    override fun onElementLongPress(
        element: ElementView<*>
    ) {
        if ( element is BlockView ) {
            toggleBlockSelection(element.model!!)
        } else if ( element is LinkView ) {
            toggleLinkSelection(element.model!!)
        }
    }

    override fun onLinkToBlock(
        from: BlockView,
        to: BlockView
    ) {
        val linkModel =
            LinkController(
                from.model!!.id,
                to.model!!.id
            )

        val linkView =
            view.addLink(
                model = linkModel
            )

        linkModel.view = linkView

        links.add(linkModel)

        onDataChanged()
    }

    override fun onBlockIncluded(
        parent: BlockView,
        child: BlockView
    ) {
        assert(parent != child)

        if (
            !parent.model!!.children.contains(
                child.model!!.id
            )
        ) {
            parent.model!!.children.add(
                child.model!!.id
            )
        }

        child.model!!.parent =
            parent.model!!

        debugBlockTree()
        onDataChanged()
    }

    override fun onBlockExcluded(
        parent: BlockView,
        child: BlockView
    ) {
        assert(parent != child)

        parent.model!!.children.remove(
            child.model!!.id
        )

        child.model!!.parent = null

        onDataChanged()
    }

    override fun onUnselectAll() {
        selectedBlocks.clear()
        selectedLinks.clear()
    }

    // ------------ End Listeners view ------------

    // ------------ StateMachine ------------

    private fun startScript() {
        scope.launch {
            val emuProvider = activity.bridgeOrchestrator as EmuInterface.Provider
            val scriptEmu = stateMachine as EmuInterface
            emuProvider.setEmu(scriptEmu)
            stateMachine.start()
        }
    }

    private fun stopScript() {
        scope.launch {
            logReplay.stop()

            replayUpdatePlayPause()

            stateMachine.stop()
            val emuProvider = activity.bridgeOrchestrator as EmuInterface.Provider
            emuProvider.resetEmu()
        }
    }

    // ------------ End StateMachine ------------

    fun onRunStateChange(
        state: Boolean
    ) {
        if (state) {
            startScript()
        } else {
            stopScript()
        }
    }

    fun isBlockSelected(
        block: Any?
    ): Boolean {
        val blockId =
            when (block) {
                is Int ->
                    block

                is BlockController ->
                    block.id

                null ->
                    return false

                else ->
                    return false
            }

        return selectedBlocks.contains(
            blockId
        )
    }

    fun isLinkSelected(
        link: Any?
    ): Boolean {
        val linkKey =
            when (link) {
                is Pair<*, *> -> {
                    val from =
                        link.first as? Int
                            ?: return false

                    val to =
                        link.second as? Int
                            ?: return false

                    Pair(
                        from,
                        to
                    )
                }

                is Int -> {
                    val linkModel =
                        links.find {
                            it.id == link
                        } ?: return false

                    Pair(
                        linkModel.from,
                        linkModel.to
                    )
                }

                is LinkController ->
                    Pair(
                        link.from,
                        link.to
                    )

                null ->
                    return false

                else ->
                    return false
            }

        return selectedLinks.contains(
            linkKey
        )
    }

    fun toggleBlockSelection(
        block: BlockController
    ) {
        if (!selectedBlocks.add(block.id)) {
            selectedBlocks.remove(block.id)
        }

        view.refresh()
    }

    fun toggleLinkSelection(
        link: LinkController
    ) {
        val key =
            Pair(
                link.from,
                link.to
            )

        if (!selectedLinks.add(key)) {
            selectedLinks.remove(key)
        }

        view.refresh()
    }

    fun clearSelection() {
        selectedBlocks.clear()
        selectedLinks.clear()
        view.refresh()
    }

    fun hasSelection(): Boolean {
        return selectedBlocks.isNotEmpty() ||
            selectedLinks.isNotEmpty()
    }

    public fun logDebug(
        message: String
    ) {
        if (BuildConfig.DEBUG) {
            Log.d(
                "sim.serial.Custom",
                message
            )
        }
    }

    fun getString(
        resId: Int,
        vararg formatArgs: Any?
    ): String {
        return activity.getString(
            resId,
            *formatArgs.map {
                it ?: ""
            }.toTypedArray()
        )
    }

    private fun feedbackIfRunning(): Boolean {
        if ( stateMachine.isRunning() ) {
            AlertDialog.Builder(
                activity
            )
                .setTitle(
                    R.string.sim_custom_serial_script_clear_emu_running
                )
                .setPositiveButton(
                    android.R.string.ok
                ) { _, _ ->

                }
                .show()
            return true
        }
        return false
    }
    public fun clear() {
        if ( ! feedbackIfRunning() ) {
            blocks.clear()
            links.clear()
            onUnselectAll()
    
            onDataChanged()
        }
    }

    public fun clearWithDialog() {
        AlertDialog.Builder(
            activity
        )
            .setTitle(
                R.string.sim_custom_serial_script_clear_confirm
            )
            .setPositiveButton(
                android.R.string.ok
            ) { _, _ ->
                clear()
            }
            .setNegativeButton(
                android.R.string.cancel,
                null
            )
            .show()
    }

    private fun rmLink(
        link: Any
    ) {
        if ( feedbackIfRunning() ) {
            return
        }
        var linko = link

        if (link is Int) {
            if (0 < link) {
                linko =
                    links.find {
                        it.id == link
                    } as LinkController
            }
        }

        assert(
            linko is LinkController
        )

        val linkm =
            linko as LinkController

        selectedLinks.remove(
            Pair(
                linkm.from,
                linkm.to
            )
        )

        links.remove(linkm)

        onDataChanged()
    }

    private fun rmBlock(
        block: Any
    ) {
        if ( feedbackIfRunning() ) {
            return
        }
        var blocko = block

        if (block is Int) {
            if (0 < block) {
                blocko =
                    blocks.find {
                        it.id == block
                    } as BlockController
            }
        }

        assert(
            blocko is BlockController
        )

        val blockm =
            blocko as BlockController

        for (
            childblock in
            blockm.children.toList()
        ) {
            rmBlock(childblock)
        }

        if (blockm.parent != null) {
            blockm.parent!!.children.removeAll {
                it == blockm.id
            }

            blockm.parent = null
        }

        blocks.remove(blockm)

        if (isBlockSelected(blockm)) {
            toggleBlockSelection(blockm)
        }

        for (link in links.toList()) {
            if (
                link.from == blockm.id ||
                link.to == blockm.id
            ) {
                rmLink(link)
            }
        }

        onDataChanged()
    }

    private fun addBlock(
        type: BlockController.Type,
        to: BlockController? = null,
        name: String = ""
    ) {
        if ( feedbackIfRunning() ) {
            return
        }
        var blockName = name

        if (name.isEmpty()) {
            blockName =
                when (type) {
                    BlockController.Type.DELAY ->
                        getString(
                            R.string.sim_custom_serial_script_block_name_delay
                        )

                    BlockController.Type.RECV ->
                        getString(
                            R.string.sim_custom_serial_script_block_name_recv
                        )

                    BlockController.Type.SEND ->
                        getString(
                            R.string.sim_custom_serial_script_block_name_send
                        )

                    BlockController.Type.CONTAINER ->
                        getString(
                            R.string.sim_custom_serial_script_block_name_container
                        )
                    BlockController.Type.AND ->
                        getString(
                            R.string.sim_custom_serial_script_block_name_and
                        )
                    BlockController.Type.OR ->
                        getString(
                            R.string.sim_custom_serial_script_block_name_or
                        )
                    BlockController.Type.XOR ->
                        getString(
                            R.string.sim_custom_serial_script_block_name_xor
                        )
                    BlockController.Type.NOT ->
                        getString(
                            R.string.sim_custom_serial_script_block_name_not
                        )
                }
        }

        val block =
            BlockController(
                type = type,
                name = blockName
            )

        val blockView =
            view.addBlock(
                model = block
            )

        block.viewLink(
            blockView
        )

        blocks.add(block)

        if (
            to?.type ==
            BlockController.Type.CONTAINER
        ) {
            to.children.add(
                block.id
            )

            block.parent = to
        }

        debugBlockTree()
        onDataChanged()
    }

    private fun getScriptName(): String {
        return "TODO.json"
    }

    // ------- Action Menu listener -------

    public fun onImportClipboard() {
        if ( feedbackIfRunning() ) {
            return
        }
        val clipboard =
            activity.getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as android.content.ClipboardManager

        if (!clipboard.hasPrimaryClip()) {
            Toast.makeText(
                activity,
                getString(
                    R.string.sim_custom_serial_script_import_clipboard_empty
                ),
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val clip =
            clipboard.primaryClip
                ?: return

        if (clip.itemCount == 0) {
            Toast.makeText(
                activity,
                getString(
                    R.string.sim_custom_serial_script_import_clipboard_empty
                ),
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val text =
            clip.getItemAt(0)
                .coerceToText(activity)
                .toString()

        if (text.isBlank()) {
            Toast.makeText(
                activity,
                getString(
                    R.string.sim_custom_serial_script_import_clipboard_empty
                ),
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        try {
            val json =
                JSONObject(text)

            fromJson(json)

            Toast.makeText(
                activity,
                getString(
                    R.string.sim_custom_serial_script_import_clipboard_success
                ),
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            logDebug(
                "Clipboard import failed: " +
                    e.message
            )

            Toast.makeText(
                activity,
                getString(
                    R.string.sim_custom_serial_script_import_clipboard_error,
                    e.message ?: ""
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    public fun onExportClipboard() {
        val text =
            toJson().toString()

        val clipboard =
            activity.getSystemService(
                android.content.Context.CLIPBOARD_SERVICE
            ) as android.content.ClipboardManager

        val clip =
            android.content.ClipData.newPlainText(
                getScriptName(),
                text
            )

        clipboard.setPrimaryClip(clip)

        Toast.makeText(
            activity,
            getString(
                R.string.sim_custom_serial_script_export_clipboard_success
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    public fun onExportFile() {
        activity.fileExportPendingData =
            toJson().toString()

        val intent =
            Intent(
                Intent.ACTION_CREATE_DOCUMENT
            ).apply {
                addCategory(
                    Intent.CATEGORY_OPENABLE
                )

                type = "application/json"

                putExtra(
                    Intent.EXTRA_TITLE,
                    getScriptName()
                )
            }

        activity.fileExportLauncher.launch(
            intent
        )
    }

    public fun shareConfigAsText() {
        val text =
            toJson().toString()

        val intent =
            Intent(
                Intent.ACTION_SEND
            ).apply {
                type = "text/plain"

                putExtra(
                    Intent.EXTRA_SUBJECT,
                    getScriptName()
                )

                putExtra(
                    Intent.EXTRA_TEXT,
                    text
                )
            }

        activity.startActivity(
            Intent.createChooser(
                intent,
                getString(
                    R.string.sim_custom_serial_script_share_script_title
                )
            )
        )
    }

    public fun onAddAnd() {
        addBlock(
            BlockController.Type.AND
        )
    }

    public fun onAddOr() {
        addBlock(
            BlockController.Type.OR
        )
    }

    public fun onAddXor() {
        addBlock(
            BlockController.Type.XOR
        )
    }

    public fun onAddNot() {
        addBlock(
            BlockController.Type.NOT
        )
    }

    public fun onAddDelay() {
        addBlock(
            BlockController.Type.DELAY
        )
    }

    public fun onAddRecv() {
        addBlock(
            BlockController.Type.RECV
        )
    }

    public fun onAddSend() {
        addBlock(
            BlockController.Type.SEND
        )
    }

    public fun onAddContainer() {
        addBlock(
            BlockController.Type.CONTAINER
        )
    }
    fun onStart() {
        onRunStateChange(true)
        listener?.customSerialOnRunStateChange(true)
    }
    fun onStop() {
        onRunStateChange(false)
        listener?.customSerialOnRunStateChange(false)
    }
    public fun onDuplicate() {
        if ( feedbackIfRunning() ) {
            return
        }
        val selected =
            selectedBlocks
                .mapNotNull { id ->
                    blocks.find {
                        it.id == id
                    }
                }

        if (selected.isEmpty()) {
            return
        }

        /*
        * If both a container and one of its children are selected,
        * duplicate the child through the container only once.
        */
        val roots =
            selected.filter { block ->
                var parent = block.parent

                while (parent != null) {
                    if (selected.contains(parent)) {
                        return@filter false
                    }

                    parent = parent.parent
                }

                true
            }

        val originalToDuplicate =
            mutableMapOf<Int, BlockController>()

        fun duplicateBlock(
            original: BlockController,
            parent: BlockController?
        ): BlockController {
            val newId =
                ElementController.gen_id_track()

            val json =
                original.toJson()

            val duplicate =
                BlockController.fromJson(
                    json,
                    newId,
                    null
                ) ?: throw IllegalStateException(
                    "Unable to duplicate block #${original.id}"
                )

            /*
            * Children are rebuilt below, so don't keep the original IDs.
            */
            duplicate.children.clear()
            duplicate.parent = parent

            val blockView =
                view.addBlock(
                    model = duplicate
                )

            duplicate.viewLink(
                blockView
            )

            blocks.add(duplicate)

            originalToDuplicate[
                original.id
            ] = duplicate

            /*
            * Preserve the original block position with an offset.
            */
            original.view?.let { sourceView ->
                duplicate.view?.let { targetView ->
                    targetView.x =
                        sourceView.x +
                            dp(20)

                    targetView.y =
                        sourceView.y +
                            dp(20)
                }
            }

            /*
            * Rebuild the container hierarchy using the new IDs.
            */
            for (childId in original.children) {
                val child =
                    blocks.find {
                        it.id == childId
                    } ?: continue

                val duplicateChild =
                    duplicateBlock(
                        child,
                        duplicate
                    )

                duplicate.children.add(
                    duplicateChild.id
                )
            }

            return duplicate
        }

        /*
        * Duplicate the selected root subtrees.
        */
        for (root in roots) {
            duplicateBlock(
                root,
                null
            )
        }

        /*
        * Duplicate links whose source and destination were
        * both duplicated.
        *
        * This includes links inside containers and links between
        * selected root blocks.
        */
        val duplicatedLinks =
            links.filter { link ->
                originalToDuplicate.containsKey(
                    link.from
                ) &&
                    originalToDuplicate.containsKey(
                        link.to
                    )
            }

        for (link in duplicatedLinks) {
            val newFrom =
                originalToDuplicate[
                    link.from
                ] ?: continue

            val newTo =
                originalToDuplicate[
                    link.to
                ] ?: continue

            val duplicateLink =
                LinkController(
                    from = newFrom.id,
                    to = newTo.id
                )

            val linkView =
                view.addLink(
                    model = duplicateLink
                )

            duplicateLink.viewLink(
                linkView
            )

            links.add(
                duplicateLink
            )
        }

        /*
        * Select the duplicated blocks instead of the originals.
        */
        selectedBlocks.clear()

        for (duplicate in originalToDuplicate.values) {
            selectedBlocks.add(
                duplicate.id
            )
        }

        selectedLinks.clear()

        for (link in duplicatedLinks) {
            val from =
                originalToDuplicate[
                    link.from
                ] ?: continue

            val to =
                originalToDuplicate[
                    link.to
                ] ?: continue

            selectedLinks.add(
                Pair(
                    from.id,
                    to.id
                )
            )
        }

        debugBlockTree()
        onDataChanged()
    }

    public fun onDelete() {
        val blocksToDelete =
            selectedBlocks
                .mapNotNull { blockId ->
                    blocks.find {
                        it.id == blockId
                    }
                }
                .toList()

        val linksToDelete =
            selectedLinks
                .mapNotNull { (from, to) ->
                    links.find {
                        it.from == from &&
                            it.to == to
                    }
                }
                .toList()

        for (block in blocksToDelete) {
            rmBlock(block)
        }

        for (link in linksToDelete) {
            rmLink(link)
        }

        onDataChanged()
    }

    // ------- End Action Menu listener -------

    fun isSomeSelection(): Boolean {
        return !selectedBlocks.isEmpty() ||
            !selectedLinks.isEmpty()
    }

    override fun toJson(): JSONObject {
        val root =
            JSONObject()

        root.put(
            "schema",
            SCHEMA
        )

        root.put(
            "version",
            VERSION
        )

        val content =
            JSONObject()

        root.put(
            "content",
            content
        )

        val jsonBlocks =
            JSONArray()

        for (block in blocks) {
            jsonBlocks.put(
                block.toJson()
            )
        }

        content.put(
            "block",
            jsonBlocks
        )

        val jsonFlow =
            JSONArray()

        for (link in links) {
            val linkObject =
                JSONObject()

            linkObject.put(
                "from",
                link.from
            )

            linkObject.put(
                "to",
                link.to
            )

            jsonFlow.put(
                linkObject
            )
        }

        content.put(
            "flow",
            jsonFlow
        )

        return root
    }

    override fun fromJson(
        desc: JSONObject,
        parseErrorHandler: ((String) -> Unit)?
    ) {
        val schema =
            desc.optString(
                "schema",
                ""
            )

        if (schema != SCHEMA) {
            parseErrorHandler?.invoke(
                "Unsupported script schema: $schema"
            )
            return
        }

        val version =
            desc.optDouble(
                "version",
                -VERSION
            )

        if (version != VERSION) {
            parseErrorHandler?.invoke(
                "Unsupported script version: $version"
            )
            return
        }

        val content =
            desc.optJSONObject(
                "content"
            )

        if (content == null) {
            parseErrorHandler?.invoke(
                "Missing script content"
            )
            return
        }

        val jsonBlocks =
            content.optJSONArray(
                "block"
            ) ?: JSONArray()

        val jsonFlow =
            content.optJSONArray(
                "flow"
            ) ?: JSONArray()

        /*
         * Build the models first. This allows container references
         * to refer to blocks appearing later in the JSON array.
         */
        val importedBlocks =
            mutableListOf<BlockController>()

        val blockIds =
            mutableSetOf<Int>()

        for (
            i in 0 until jsonBlocks.length()
        ) {
            val jsonBlock =
                jsonBlocks.getJSONObject(i)

            val id =
                jsonBlock.getInt("id")

            if (!blockIds.add(id)) {
                parseErrorHandler?.invoke(
                    "Duplicate block id: $id"
                )
                return
            }

            val block =
                BlockController.fromJson(
                    jsonBlock,
                    id,
                    parseErrorHandler
                )

            if (block != null) {
                importedBlocks.add(block)
            }
        }

        /*
         * Validate and rebuild parent relationships.
         */
        for (parent in importedBlocks) {
            for (childId in parent.children) {
                val child =
                    importedBlocks.find {
                        it.id == childId
                    }

                if (child == null) {
                    parseErrorHandler?.invoke(
                        "Block #$childId referenced by " +
                            "container #${parent.id} does not exist"
                    )
                    return
                }

                if (child === parent) {
                    parseErrorHandler?.invoke(
                        "Block #${parent.id} cannot contain itself"
                    )
                    return
                }

                if (
                    child.parent != null &&
                    child.parent !== parent
                ) {
                    parseErrorHandler?.invoke(
                        "Block #$childId has multiple parents"
                    )
                    return
                }

                child.parent = parent
            }
        }

        /*
         * Replace the current script.
         */
        clear()

        /*
         * Add blocks to the view.
         */
        for (block in importedBlocks) {
            val blockView =
                view.addBlock(
                    model = block
                )

            block.viewLink(
                blockView
            )
        }

        for (block in importedBlocks) {
            blocks.add(block)
        }

        /*
         * Restore exact saved coordinates.
         *
         * x/y are:
         *   - world coordinates for root blocks
         *   - parent-relative coordinates for children
         */
        for (
            i in 0 until jsonBlocks.length()
        ) {
            val jsonBlock =
                jsonBlocks.getJSONObject(i)

            val blockId =
                jsonBlock.getInt("id")

            val block =
                blocks.find {
                    it.id == blockId
                } ?: continue

            val jsonView =
                jsonBlock.optJSONObject(
                    "view"
                ) ?: continue

            block.view?.x =
                jsonView.optDouble(
                    "x",
                    block.view?.x?.toDouble()
                        ?: 0.0
                ).toFloat()

            block.view?.y =
                jsonView.optDouble(
                    "y",
                    block.view?.y?.toDouble()
                        ?: 0.0
                ).toFloat()
        }

        /*
         * Restore links.
         */
        for (
            i in 0 until jsonFlow.length()
        ) {
            val jsonLink =
                jsonFlow.getJSONObject(i)

            val from =
                jsonLink.getInt("from")

            val to =
                jsonLink.getInt("to")

            if (blocks.none { it.id == from }) {
                parseErrorHandler?.invoke(
                    "Link source block #$from does not exist"
                )
                return
            }

            if (blocks.none { it.id == to }) {
                parseErrorHandler?.invoke(
                    "Link destination block #$to does not exist"
                )
                return
            }

            val link =
                LinkController(
                    from = from,
                    to = to
                )

            val linkView =
                view.addLink(
                    model = link
                )

            link.viewLink(
                linkView
            )

            links.add(link)
        }

        debugBlockTree()

        onDataChanged()
    }

    public fun dp(
        value: Int
    ): Int {
        return (
            value *
                resources.displayMetrics.density
            ).toInt()
    }

    private fun debugBlockTree() {
        if (!BuildConfig.DEBUG) {
            return
        }

        fun printBlock(
            block: BlockController,
            depth: Int
        ) {
            val indent =
                "  ".repeat(depth)

            val parentId =
                block.parent
                    ?.id
                    ?.toString()
                    ?: "null"

            val childrenIds =
                if (block.children.isEmpty()) {
                    "[]"
                } else {
                    block.children.joinToString(
                        prefix = "[",
                        postfix = "]"
                    )
                }

            logDebug(
                "${indent}Block #${block.id} " +
                    "type=${block.type} " +
                    "parent=$parentId " +
                    "children=$childrenIds"
            )

            for (childId in block.children) {
                val child =
                    blocks.find {
                        it.id == childId
                    }

                if (child != null) {
                    printBlock(
                        child,
                        depth + 1
                    )
                } else {
                    logDebug(
                        "${indent}  MISSING CHILD #$childId"
                    )
                }
            }
        }

        logDebug(
            "========== BLOCK TREE =========="
        )

        val roots =
            blocks.filter {
                it.parent == null
            }

        for (root in roots) {
            printBlock(
                root,
                0
            )
        }

        logDebug(
            "========== BLOCKS NOT REACHED =========="
        )

        val reached =
            mutableSetOf<Int>()

        fun collect(
            block: BlockController
        ) {
            if (!reached.add(block.id)) {
                return
            }

            for (childId in block.children) {
                blocks.find {
                    it.id == childId
                }?.let {
                    collect(it)
                }
            }
        }

        for (root in roots) {
            collect(root)
        }

        for (block in blocks) {
            if (!reached.contains(block.id)) {
                logDebug(
                    "UNREACHED Block #${block.id} " +
                        "type=${block.type} " +
                        "parent=${block.parent?.id ?: "null"} " +
                        "children=${block.children}"
                )
            }
        }

        logDebug(
            "================================"
        )
    }
}