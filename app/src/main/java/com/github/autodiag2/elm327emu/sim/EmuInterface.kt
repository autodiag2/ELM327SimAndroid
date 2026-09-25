package com.github.autodiag2.elm327emu.sim

import com.github.autodiag2.elm327emu.com.HookableIO
import java.io.InputStream
import java.io.OutputStream

abstract class EmuInterface(
    input: InputStream? = null,
    output: OutputStream? = null,
    LOG_TAG: String = "sim.EmuInterface"
): HookableIO(input = input, output = output, LOG_TAG = LOG_TAG) {

}