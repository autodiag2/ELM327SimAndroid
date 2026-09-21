package com.github.autodiag2.elm327emu.sim.serial

open class ElementController<V>(
    var view: V? = null,
    id: Int? = null
) {
    var id: Int = if (id == null) {
        gen_id_track()
    } else {
        use_id(id)
    }

    companion object {
        private var id_track: Int = 1

        public fun gen_id_track(): Int {
            return id_track++
        }

        public fun use_id(id: Int): Int {
            if (id_track <= id) {
                id_track = id + 1
            }

            return id
        }
    }

    fun viewLink(view_arg: V) {
        view = view_arg
    }
}

open class ElementView<M>(
    var model: M? = null
) {
    fun modelLink(model_arg: M) {
        model = model_arg
    }
}