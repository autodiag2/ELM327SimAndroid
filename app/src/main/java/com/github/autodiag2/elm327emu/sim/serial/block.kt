package com.github.autodiag2.elm327emu.sim.serial

import com.github.autodiag2.elm327emu.sim.serial.CustomView.Coordinates

open class BlockController(
    var type: BlockController.Type,
    var delay: Int = 0,
    var text: String = "",
    var match: String = "exact",
    var includeEol: Boolean = false,
    var interpretEscapes: Boolean = true,
    var name: String = "",
    view: BlockView? = null,
    val children: MutableList<Int> = mutableListOf(),
    var parent: BlockController? = null,
    id: Int? = null
) : ElementController<BlockView>(
    view = view,
    id = id
) {
    enum class Type {
        DELAY,
        RECV,
        SEND,
        CONTAINER
    }
}

open class BlockView(
    /**
     * Relative to container
     */
    var x: Float,
    /**
     * Relative to container
     */
    var y: Float,
    var width: Float = 260f,
    var height: Float = 100f,
    model: BlockController? = null
) : ElementView<BlockController>(model = model) {
    
    private fun getWorldCoordsRecurse(node: BlockView): Coordinates {
        if ( node.model!!.parent == null ) {
            return Coordinates(node.x, node.y)
        } else {
            val coords = getWorldCoordsRecurse(node.model!!.parent!!.view!!)
            coords.x += node.x
            coords.y += node.y
            return coords
        }
    }

    /**
     * Get absolute position from the node contained relative ones
     */
    public fun getWorldCoords(): Coordinates {
        return getWorldCoordsRecurse(this)
    }
}