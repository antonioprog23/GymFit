package com.rutinaboxeo.app

import org.junit.Assert.*
import org.junit.Test

/** Verifica la cuenta atrás sonora sin depender del dispositivo de audio. */
class RestCountdownCuesTest {
    /** Solo los últimos cinco segundos producen un aviso. */
    @Test fun onlyLastFiveSecondsBeep() {
        val cues = RestCountdownCues()
        assertFalse(cues.shouldBeep(60))
        assertFalse(cues.shouldBeep(6))
        for (second in 5 downTo 1) {
            assertTrue(cues.shouldBeep(second))
            assertFalse(cues.shouldBeep(second))
        }
        assertFalse(cues.shouldBeep(0))
        assertFalse(cues.shouldBeep(-1))
    }

    /** Un descanso corto o reanudado avisa desde su segundo inicial. */
    @Test fun resetsForNewOrResumedRest() {
        val cues = RestCountdownCues()
        assertTrue(cues.shouldBeep(3))
        cues.reset()
        assertTrue(cues.shouldBeep(3))
        assertTrue(cues.shouldBeep(2))
    }
}
