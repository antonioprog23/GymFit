package com.rutinaboxeo.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.YearMonth
import java.io.File
import org.json.JSONObject

/** Verifica aislamiento mensual, reanudación, repetición y migración sin fechas inventadas. */
class MonthlyRepositoryTest {
    /** Borrar una importación duplicada no modifica ni activa ni elimina sus hermanas. */
    @Test fun deletesOnlySelectedHistoricalImport() {
        val repository = MonthlyRepository(temporary.newFolder())
        val first = repository.create(plan, YearMonth.of(2026, 9))
        repository.begin(1, "Lunes")
        val second = repository.create(plan, first.month)
        assertFalse(repository.delete(first.id))
        assertEquals(second.id, repository.active()!!.id)
        assertEquals(listOf(second.id), repository.list().map { it.id })
    }

    /** El borrado activo sobrevive al reinicio sin seleccionar históricos ni recuperar datos heredados. */
    @Test fun activeDeletionLeavesPersistentEmptySelection() {
        val folder = temporary.newFolder()
        val repository = MonthlyRepository(folder)
        val historical = repository.create(plan, YearMonth.of(2026, 8))
        val active = repository.create(plan, YearMonth.of(2026, 9))
        assertTrue(repository.delete(active.id))
        val reopened = MonthlyRepository(folder)
        assertNull(reopened.active())
        assertTrue(reopened.hasMonthlyState())
        assertEquals(historical.id, reopened.list().single().id)
        val next = reopened.create(plan, YearMonth.of(2026, 10))
        assertEquals(next.id, reopened.active()!!.id)
    }

    /** Una sesión pausada sigue pendiente y bloquea el borrado hasta su finalización. */
    @Test fun unfinishedWorkoutsBlockActiveDeletion() {
        val repository = MonthlyRepository(temporary.newFolder())
        val active = repository.create(plan, YearMonth.of(2026, 9))
        repository.begin(1, "Lunes")
        assertTrue(runCatching { repository.delete(active.id) }.exceptionOrNull() is IllegalStateException)
        assertEquals(active.id, repository.active()!!.id)
        repository.finish(1, "Lunes")
        repository.beginMorning()
        assertTrue(runCatching { repository.delete(active.id) }.isFailure)
        repository.finishMorning()
        assertTrue(repository.delete(active.id))
        assertTrue(repository.list().isEmpty())
    }

    /** Las rutas ajenas y los identificadores inexistentes no cambian la selección activa. */
    @Test fun invalidDeletionDoesNotChangeData() {
        val repository = MonthlyRepository(temporary.newFolder())
        val active = repository.create(plan, YearMonth.of(2026, 9))
        assertTrue(runCatching { repository.delete("../09_2026") }.isFailure)
        assertTrue(runCatching { repository.delete("01_2020") }.isFailure)
        assertEquals(active.id, repository.active()!!.id)
    }

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

    /** Las sesiones mensuales se reanudan y persisten con su día real. */
    @Test fun monthlyWorkoutUsesCalendarDay() {
        val repository = MonthlyRepository(temporary.newFolder())
        val exercise = RoutineExercise(1, "Martes", "Fuerza", "Remo", "3", "10", "60 s", dayOfMonth = 1)
        val monthlyPlan = RoutinePlan(listOf(exercise), month = YearMonth.of(2026, 9),
            calendar = mapOf(1 to CalendarDay(1, "Entrenamiento", "Torso")))
        repository.create(monthlyPlan, YearMonth.of(2026, 9))
        repository.beginDate(1)
        repository.beginDate(1)
        assertEquals(1, repository.active()!!.workouts.size)
        assertEquals(1, repository.active()!!.workouts.single().dayOfMonth)
        repository.finishDate(1)
        assertTrue(repository.active()!!.workouts.single().finishedAt.isNotBlank())
        assertTrue(runCatching { repository.beginDate(31) }.isFailure)
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

    /** Los documentos de esquema 2 permanecen legibles sin inventar un día del mes. */
    @Test fun readsSchemaTwoAsLegacyHistory() {
        val folder = temporary.newFolder()
        val repository = MonthlyRepository(folder)
        val saved = repository.create(plan, YearMonth.of(2026, 8))
        repository.begin(1, "Lunes")
        repository.finish(1, "Lunes")
        val file = File(folder, "${saved.id}.json")
        val root = JSONObject(file.readText()).put("schemaVersion", 2)
        val workouts = root.getJSONArray("workouts")
        for (index in 0 until workouts.length()) workouts.getJSONObject(index).remove("dayOfMonth")
        file.writeText(root.toString(2))

        val restored = MonthlyRepository(folder).read(saved.id)
        assertNull(restored.workouts.single().dayOfMonth)
        assertEquals("Lunes", restored.workouts.single().day)
    }

    /** La consulta de un identificador externo no puede salir de la carpeta privada. */
    @Test(expected = IllegalArgumentException::class) fun rejectsExternalPaths() {
        MonthlyRepository(temporary.newFolder()).read("../other")
    }
}
