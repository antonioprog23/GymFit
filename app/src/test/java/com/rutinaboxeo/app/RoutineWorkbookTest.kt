package com.rutinaboxeo.app

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.YearMonth
import java.util.zip.ZipInputStream

/** Comprueba el contrato de intercambio Excel, su período y la separación de resultados. */
class RoutineWorkbookTest {
    /** La plantilla generada se ajusta a los 31 días del mes elegido y vuelve a importarse. */
    @Test fun bundledExampleRoundTripsWithMonth() {
        val original = File("src/main/assets/rutina_plantilla.xlsx").inputStream().use(XlsxRoutineParser::parse)
        val period = YearMonth.of(2026, 10)
        val bytes = ByteArrayOutputStream().also { RoutineWorkbook.write(it, original, period) }.toByteArray()
        val restored = XlsxRoutineParser.parse(bytes.inputStream())
        assertEquals(period, restored.month)
        assertEquals((1..31).toList(), restored.dateDays())
        assertTrue(restored.dateDays().all { restored.forDate(it).isNotEmpty() })
        assertEquals("Recuperación", restored.calendarDay(31).type)
        assertEquals(original.morningSteps, restored.morningSteps)
    }

    /** Exporta un solo documento y la reimportación lee planificación, nunca resultados. */
    @Test fun exportsResultsAsLiteralTextAndKeepsHistorySeparate() {
        val exercise = RoutineExercise(1, "Martes", "Fuerza", "Remo & control", "3", "10", "60 s",
            instruction = "<Suave>", dayOfMonth = 1)
        val calendar = (1..30).associateWith { day -> CalendarDay(day, "Recuperación", "Movilidad") }
        val exercises = (1..30).map { day -> exercise.copy(dayOfMonth = day, day = "Martes") }
        val plan = RoutinePlan(exercises, month = YearMonth.of(2026, 9), calendar = calendar)
        val doc = MonthlyRoutine("09_2026", YearMonth.of(2026, 9), "2026-09-01", plan)
        doc.progress[exercises.first().key()] = ExerciseProgress(weight = "25", userNote = "=1+1", done = true)
        doc.workouts += WorkoutRecord("run-1", 1, "Martes", "2026-09-02",
            results = doc.progress.toMutableMap(), dayOfMonth = 1)
        val bytes = ByteArrayOutputStream().also { RoutineWorkbook.write(it, plan, doc.month, doc) }.toByteArray()
        val restored = XlsxRoutineParser.parse(bytes.inputStream())
        assertEquals(30, restored.dateDays().size)
        assertEquals("Remo & control", restored.forDate(1).single().exercise)
        val xml = mutableListOf<String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (zip.nextEntry != null) xml += zip.readBytes().toString(Charsets.UTF_8)
        }
        assertTrue(xml.any { it.contains("Historial") })
        assertTrue(xml.any { it.contains("=1+1") })
        assertFalse(xml.any { it.contains("<f>") })
        assertTrue(xml.any { it.contains("run-1") })
    }

    /** Un JSON semanal antiguo se exporta como informe, nunca como plantilla semanal reutilizable. */
    @Test fun legacyDocumentExportsAsReadOnlyReport() {
        val exercise = RoutineExercise(1, "Lunes", "Fuerza", "Remo", "3", "10", "60 s")
        val plan = RoutinePlan(listOf(exercise))
        val doc = MonthlyRoutine("09_2026", YearMonth.of(2026, 9), "2026-09-01", plan)
        val bytes = ByteArrayOutputStream().also { RoutineWorkbook.write(it, plan, doc.month, doc) }.toByteArray()
        val xml = mutableListOf<String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (zip.nextEntry != null) xml += zip.readBytes().toString(Charsets.UTF_8)
        }
        assertTrue(xml.any { it.contains("Plan anterior") })
        assertFalse(xml.any { it.contains("Semana 1") })
        assertTrue(runCatching { XlsxRoutineParser.parse(bytes.inputStream()) }.isFailure)
    }
}
