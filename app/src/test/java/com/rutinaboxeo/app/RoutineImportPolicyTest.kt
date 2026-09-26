package com.rutinaboxeo.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Comprueba la migración entre la antigua plantilla precargada y las importaciones. */
class RoutineImportPolicyTest {
    /** Plan mínimo reutilizado en los escenarios de migración. */
    private val example = RoutinePlan(listOf(
        RoutineExercise(1, "Lunes", "Fuerza", "Sentadilla", "2", "8", "90 s")
    ))

    /** Verifica que una instalación nueva comienza sin una rutina visible. */
    @Test fun aFreshInstallShowsNoWorkout() {
        assertFalse(RoutineImportPolicy.shouldShowRoutine(null, null, null))
    }

    /** Verifica que la antigua plantilla incluida no se considera una importación. */
    @Test fun theOldBundledExampleIsNotTreatedAsAnImport() {
        assertFalse(RoutineImportPolicy.shouldShowRoutine(null, example, example.copy(
            sessions = mapOf("Lunes" to SessionInfo("Piernas", "Fuerza"))
        )))
    }

    /** Verifica que una rutina antigua pero personalizada se conserva. */
    @Test fun aDifferentLegacyWorkoutIsPreserved() {
        val imported = example.copy(exercises = listOf(example.exercises.first().copy(exercise = "Remo")))
        assertTrue(RoutineImportPolicy.shouldShowRoutine(null, imported, example))
        assertTrue(RoutineImportPolicy.shouldShowRoutine(true, example, null))
    }

    /** Verifica que el estado no importado prevalece sobre cualquier dato almacenado. */
    @Test fun anExplicitlyUnimportedWorkoutStaysHidden() {
        assertFalse(RoutineImportPolicy.shouldShowRoutine(false, example, example))
    }
}
