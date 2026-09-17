package com.rutinaboxeo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MorningRoutineTest {
    @Test fun morningPlanIsFifteenMinutesAndAllStepsArePositive() {
        val steps = File("src/main/assets/rutina_plantilla.xlsx").inputStream().use(XlsxRoutineParser::parse).morningSteps
        assertEquals(15 * 60, steps.sumOf { it.seconds })
        assertEquals("Marcha suave", steps.first().title)
        assertTrue(steps.all { it.seconds > 0 && it.instruction.isNotBlank() })
    }
}
