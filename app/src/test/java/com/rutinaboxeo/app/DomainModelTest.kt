package com.rutinaboxeo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Comprueba reglas de dominio compartidas por las distintas pantallas. */
class DomainModelTest {
    /** Verifica orden semanal, orden de sesión y búsquedas de un plan indexado. */
    @Test fun planIndexesAndOrdersSessions() {
        val plan = RoutinePlan(
            exercises = listOf(
                RoutineExercise(2, "Martes", "Fuerza", "Remo", "3", "8", "60 s", order = 2),
                RoutineExercise(1, "Miércoles", "Core", "Plancha", "2", "30 s", "30 s"),
                RoutineExercise(2, "Martes", "Fuerza", "Jalón", "3", "10", "60 s", order = 1),
                RoutineExercise(2, "Lunes", "Fuerza", "Sentadilla", "3", "8", "90 s")
            )
        )

        assertEquals(listOf(1, 2), plan.weeks())
        assertEquals(listOf("Lunes", "Martes"), plan.days(2))
        assertEquals(listOf("Jalón", "Remo"), plan.forDay(2, "Martes").map { it.exercise })
        assertEquals(listOf("Sentadilla", "Jalón", "Remo"), plan.forWeek(2).map { it.exercise })
        assertTrue(plan.forDay(3, "Viernes").isEmpty())
    }

    /** Verifica que una indicación importada prevalece sobre la guía genérica. */
    @Test fun importedInstructionTakesPriority() {
        val exercise = RoutineExercise(
            1, "Lunes", "Fuerza", "Sentadilla", "3", "8", "90 s",
            instruction = "Mantén el talón apoyado."
        )

        assertEquals("Mantén el talón apoyado.", ExerciseGuide.forExercise(exercise))
    }

    /** Verifica que solo se conservan enlaces HTTPS sin credenciales incrustadas. */
    @Test fun videoLinksRejectUnsafeAddresses() {
        assertEquals("https://example.com/demo", VideoLinks.clean(" https://example.com/demo "))
        assertEquals("", VideoLinks.clean("http://example.com/demo"))
        assertEquals("", VideoLinks.clean("https://user:secret@example.com/demo"))
    }
}
