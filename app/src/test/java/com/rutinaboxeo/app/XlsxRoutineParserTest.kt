package com.rutinaboxeo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class XlsxRoutineParserTest {
    @Test fun bundledTemplateHasExpectedStructure() {
        val plan = File("src/main/assets/rutina_plantilla.xlsx").inputStream().use(XlsxRoutineParser::parse)
        assertEquals(listOf(1, 2, 3, 4), plan.weeks())
        assertEquals("Piernas + potencia", plan.session("Lunes").title)
        assertTrue(plan.forDay(1, "Lunes").isNotEmpty())
        assertTrue(plan.forDay(1, "Viernes").any { it.block == "Recuperación" })
        assertTrue(plan.exercises.any { it.note.contains("Alternativas:") })
        assertEquals(6, plan.morningSteps.size)
        assertEquals("Estiramiento suave", plan.morningSteps.last().title)
    }

}
