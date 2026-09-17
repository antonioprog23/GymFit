package com.rutinaboxeo.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineImportPolicyTest {
    private val example = RoutinePlan(listOf(
        RoutineExercise(1, "Lunes", "Fuerza", "Sentadilla", "2", "8", "90 s")
    ))

    @Test fun aFreshInstallShowsNoWorkout() {
        assertFalse(RoutineImportPolicy.shouldShowRoutine(null, null, null))
    }

    @Test fun theOldBundledExampleIsNotTreatedAsAnImport() {
        assertFalse(RoutineImportPolicy.shouldShowRoutine(null, example, example.copy(
            sessions = mapOf("Lunes" to SessionInfo("Piernas", "Fuerza"))
        )))
    }

    @Test fun aDifferentLegacyWorkoutIsPreserved() {
        val imported = example.copy(exercises = listOf(example.exercises.first().copy(exercise = "Remo")))
        assertTrue(RoutineImportPolicy.shouldShowRoutine(null, imported, example))
        assertTrue(RoutineImportPolicy.shouldShowRoutine(true, example, null))
    }

    @Test fun anExplicitlyUnimportedWorkoutStaysHidden() {
        assertFalse(RoutineImportPolicy.shouldShowRoutine(false, example, example))
    }
}
