package com.rutinaboxeo.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Comprueba la interpretación de series y duraciones escritas en la plantilla. */
class ExerciseTimingTest {
    /** Impide que un descanso extremo desborde el contador y produzca segundos negativos. */
    @Test fun extremeRestIsClampedWithoutOverflow() {
        assertEquals(3600, ExerciseTiming.restSeconds("2147483647 min"))
    }

    /** Verifica que el descanso total incluye la pausa de la última serie. */
    @Test fun restIsCountedAfterEverySeriesIncludingTheLast() {
        val exercise = RoutineExercise(1, "Lunes", "Fuerza", "Sentadilla", "3", "8", "90 s")
        assertEquals(270, ExerciseTiming.totalRestSeconds(exercise))
        assertEquals(90, ExerciseTiming.totalRestSeconds(exercise.copy(series = "1")))
        assertEquals(90, ExerciseTiming.totalRestSeconds(exercise.copy(series = "-")))
    }

    /** Verifica que un rango de descanso usa su extremo más conservador. */
    @Test fun restRangesUseTheLongerDuration() {
        assertEquals(180, ExerciseTiming.restSeconds("2-3 min"))
        assertEquals(45, ExerciseTiming.restSeconds("30-45 s"))
        assertEquals(0, ExerciseTiming.restSeconds("-"))
    }

    /** Verifica minutos, intervalos, trabajo por lado y valores no temporales. */
    @Test fun timedWarmupsAndIntervalsHaveWorkCountdowns() {
        assertEquals(300, ExerciseTiming.workSeconds("5 min"))
        assertEquals(180, ExerciseTiming.workSeconds("2 min fuerte + 1 min suave"))
        assertEquals(90, ExerciseTiming.workSeconds("30-45 s por lado"))
        assertEquals(0, ExerciseTiming.workSeconds("8-10"))
        assertEquals(0, ExerciseTiming.workSeconds("30-40 m"))
    }
}
