package com.github.autodiag2.elm327emu

import android.view.View

enum class EcuType(val label: String) {
    GUI("GUI"),
    SCRIPT("Data Script");

    override fun toString() = label
}
data class EcuConfig(
    val id: Int,
    var name: String,
    var type: EcuType,
    var screen: View
)