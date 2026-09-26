package com.rutinaboxeo.app

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.YearMonth
import java.util.zip.ZipInputStream

/** Comprueba el contrato de intercambio Excel, su período y la separación de resultados. */
class RoutineWorkbookTest {
    /** La plantilla generada se puede importar conservando todas las semanas y el mes elegido. */
    @Test fun bundledExampleRoundTripsWithMonth() {
        val original = File("src/main/assets/rutina_plantilla.xlsx").inputStream().use(XlsxRoutineParser::parse)
        val period = YearMonth.of(2026, 10)
        val bytes = ByteArrayOutputStream().also { RoutineWorkbook.write(it, original, period) }.toByteArray()
        val restored = XlsxRoutineParser.parse(bytes.inputStream())
        assertEquals(period, restored.month)
        assertEquals(original.exercises.size, restored.exercises.size)
        assertEquals(original.morningSteps, restored.morningSteps)
        assertEquals(original.sessions, restored.sessions)
        assertEquals(original.exercises.map { it.exercise }, restored.exercises.map { it.exercise })
    }

    /** Exporta un solo documento y la reimportación lee planificación, nunca resultados. */
    @Test fun exportsResultsAsLiteralTextAndKeepsHistorySeparate() {
        val exercise = RoutineExercise(1, "Lunes", "Fuerza", "Remo & control", "3", "10", "60 s", instruction = "<Suave>")
        val plan = RoutinePlan(listOf(exercise))
        val doc = MonthlyRoutine("09_2026", YearMonth.of(2026, 9), "2026-09-01", plan)
        doc.progress[exercise.key()] = ExerciseProgress(weight = "25", userNote = "=1+1", done = true)
        doc.workouts += WorkoutRecord("run-1", 1, "Lunes", "2026-09-02", results = doc.progress.toMutableMap())
        val bytes = ByteArrayOutputStream().also { RoutineWorkbook.write(it, plan, doc.month, doc) }.toByteArray()
        val restored = XlsxRoutineParser.parse(bytes.inputStream())
        assertEquals(exercise, restored.exercises.single())
        val xml = mutableListOf<String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (zip.nextEntry != null) xml += zip.readBytes().toString(Charsets.UTF_8)
        }
        assertTrue(xml.any { it.contains("Historial") })
        assertTrue(xml.any { it.contains("=1+1") })
        assertFalse(xml.any { it.contains("<f>") })
        assertTrue(xml.any { it.contains("run-1") })
    }
}
