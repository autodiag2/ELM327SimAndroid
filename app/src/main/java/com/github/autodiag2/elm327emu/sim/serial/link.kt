package com.github.autodiag2.elm327emu.sim.serial

import com.github.autodiag2.elm327emu.sim.serial.CustomView.Coordinates

open class LinkController(
    val from: Int,
    val to: Int,
    view: LinkView? = null,
    id: Int? = null
) : ElementController<LinkView>(
    view = view,
    id = id
)

open class LinkView(
    model: LinkController? = null
) : ElementView<LinkController>(model = model)