package com.rutinaboxeo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** Comprueba los modelos preparados para calendario, progreso e históricos. */
class RoutinePresentationTest {
    @Test fun calendarRowsUseMondayFirstAndContainEveryDateOnce() {
        val rows = RoutinePresentation.calendarRows(YearMonth.of(2026, 10))
        assertEquals(listOf(null, null, null, 1, 2, 3, 4), rows.first())
        assertEquals((1..31).toList(), rows.flatten().filterNotNull())
        assertTrue(rows.all { it.size == 7 })
    }

    @Test fun initialDayUsesTodayOnlyInsideThePlanMonth() {
        val plan = monthlyPlan(31)
        assertEquals(12, RoutinePresentation.initialDay(plan, LocalDate.of(2026, 10, 12)))
        assertEquals(1, RoutinePresentation.initialDay(plan, LocalDate.of(2026, 11, 1)))
    }

    @Test fun monthlyProgressCountsSessionsGroupsAndRecentActivity() {
        val plan = monthlyPlan(31)
        val values = plan.exercises.associate { exercise ->
            exercise.key() to ExerciseProgress(done = exercise.dayOfMonth in 1..7)
        }
        val summary = RoutinePresentation.progress(plan, values)
        assertEquals(7, summary.completedSessions)
        assertEquals(7, summary.completedExercises)
        assertEquals(22, summary.percent)
        assertEquals(listOf("1–7", "8–14", "15–21", "22–28", "29–31"), summary.groups.map { it.label })
        assertEquals(7, summary.history.size)
        assertTrue(summary.history.all { it.isComplete })
    }

    @Test fun legacyProgressRemainsAvailableOnlyAsPresentationData() {
        val exercise = RoutineExercise(2, "Martes", "Fuerza", "Remo", "3", "10", "60 s")
        val summary = RoutinePresentation.progress(
            RoutinePlan(listOf(exercise)),
            mapOf(exercise.key() to ExerciseProgress(done = true))
        )
        assertEquals("S2", summary.groups.single().label)
        assertEquals("Martes · S2", summary.history.single().label)
        assertFalse(summary.groups.isEmpty())
    }

    @Test fun weightHistoryKeepsDocumentAndExtraSessionProvenance() {
        val plan = monthlyPlan(1)
        val exercise = plan.exercises.single()
        val document = MonthlyRoutine("10_2026_1", YearMonth.of(2026, 10), "2026-10-01", plan)
        document.workouts += WorkoutRecord("run", 1, exercise.day, "2026-10-12T18:00:00+02:00",
            "2026-10-12T19:00:00+02:00", mutableMapOf(exercise.key() to
                ExerciseProgress(weight = "40", actualReps = "10", rir = "2", done = true)),
            kind = "extra", dayOfMonth = 1)

        val entry = RoutinePresentation.weightHistory(listOf(document)).single()

        assertEquals(exercise.exercise, entry.exercise)
        assertEquals("40", entry.weight)
        assertEquals("10_2026_1", entry.routineId)
        assertEquals("12 de octubre", entry.dateLabel)
        assertTrue(entry.extra)
    }

    private fun monthlyPlan(days: Int): RoutinePlan {
        val month = YearMonth.of(2026, 10)
        val exercises = (1..days).map { day ->
            RoutineExercise((day - 1) / 7 + 1, month.atDay(day).dayOfWeek.name, "Bloque", "Ejercicio $day",
                "1", "5 min", "-", dayOfMonth = day)
        }
        return RoutinePlan(exercises, month = month,
            calendar = (1..days).associateWith { CalendarDay(it, "Entrenamiento", "Día $it") })
    }
}
