package com.rutinaboxeo.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.YearMonth

/** Verifica aislamiento mensual, reanudación, repetición y migración sin fechas inventadas. */
class MonthlyRepositoryTest {
    /** Carpeta desechable exclusiva de cada prueba. */
    @get:Rule val temporary = TemporaryFolder()
    /** Plan mínimo con una sesión de tarde. */
    private val plan = RoutinePlan(listOf(RoutineExercise(1, "Lunes", "Fuerza", "Remo", "3", "10", "60 s")))

    /** Cada importación colisionada conserva el mes anterior y empieza sin resultados. */
    @Test fun importsNeverOverwritePreviousMonth() {
        val repository = MonthlyRepository(temporary.newFolder())
        val period = YearMonth.of(2026, 9)
        val first = repository.create(plan, period)
        repository.begin(1, "Lunes")
        repository.updateProgress(mapOf(plan.exercises[0].key() to ExerciseProgress(weight = "25", done = true)))
        repository.finish(1, "Lunes")
        assertEquals("09_2026", first.id)
        assertEquals("09_2026_1", repository.create(plan, period).id)
        assertEquals("09_2026_2", repository.create(plan, period).id)
        assertTrue(repository.active()!!.progress.isEmpty())
        assertEquals("25", repository.read(first.id).workouts.single().results.values.single().weight)
        assertEquals(3, repository.list().size)
    }

    /** Reabrir continúa una realización pendiente y repetir crea otra sin alterar la anterior. */
    @Test fun repeatedWorkoutsHaveIndependentResults() {
        val folder = temporary.newFolder()
        val repository = MonthlyRepository(folder)
        repository.create(plan, YearMonth.of(2026, 9))
        repository.begin(1, "Lunes")
        repository.updateProgress(mapOf(plan.exercises[0].key() to ExerciseProgress(weight = "20", done = true)))
        MonthlyRepository(folder).begin(1, "Lunes")
        assertEquals(1, repository.active()!!.workouts.size)
        repository.finish(1, "Lunes")
        repository.begin(1, "Lunes", repeat = true)
        assertEquals("", repository.active()!!.progress.values.single().weight)
        repository.updateProgress(mapOf(plan.exercises[0].key() to ExerciseProgress(weight = "30")))
        val records = repository.active()!!.workouts
        assertEquals("20", records[0].results.values.single().weight)
        assertEquals("30", records[1].results.values.single().weight)
        assertTrue(records[0].finishedAt.isNotBlank())
    }

    /** Los datos heredados conservan notas y resultados con fecha desconocida. */
    @Test fun migrationPreservesUndatedResults() {
        val repository = MonthlyRepository(temporary.newFolder())
        val legacy = mapOf(plan.exercises[0].key() to ExerciseProgress(weight = "15", userNote = "Anterior"))
        val saved = repository.create(plan, YearMonth.of(2026, 8), legacy)
        val restored = repository.read(saved.id)
        assertTrue(restored.migrated)
        assertEquals(legacy, restored.progress)
        assertEquals("", restored.workouts.single().startedAt)
        assertEquals("anterior", restored.workouts.single().kind)
    }

    /** La consulta de un identificador externo no puede salir de la carpeta privada. */
    @Test(expected = IllegalArgumentException::class) fun rejectsExternalPaths() {
        MonthlyRepository(temporary.newFolder()).read("../other")
    }
}
