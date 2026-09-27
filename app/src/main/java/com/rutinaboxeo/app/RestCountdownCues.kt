package com.rutinaboxeo.app

/** Filtra los segundos del descanso para emitir cada aviso una sola vez. */
class RestCountdownCues {
    /** Último segundo anunciado en el tramo actual del temporizador. */
    private var lastSecond: Int? = null

    /** Indica si corresponde avisar en los últimos cinco segundos. */
    fun shouldBeep(seconds: Int): Boolean {
        if (seconds !in 1..5 || seconds == lastSecond) return false
        lastSecond = seconds
        return true
    }

    /** Reinicia los avisos al arrancar o reanudar un descanso. */
    fun reset() { lastSecond = null }
}
