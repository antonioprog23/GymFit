package com.rutinaboxeo.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ExerciseFlowTest {
    @Test fun threeSetsCompleteOnlyAfterTheThirdRest() {
        val exercise = RoutineExercise(1, "Lunes", "Fuerza", "Sentadilla", "3", "10", "30 s")
        var run = ExerciseFlow.start(exercise)
        assertEquals(ExerciseRun(ExercisePhase.WORK, 1, 0), run)
        for (round in 1..3) {
            run = ExerciseFlow.completeWork(exercise, run)
            assertEquals(ExerciseRun(ExercisePhase.REST, round, 30), run)
            run = ExerciseFlow.completeRest(exercise, run)
            assertEquals(if (round == 3) ExercisePhase.DONE else ExercisePhase.WORK, run.phase)
            if (round < 3) assertEquals(round + 1, run.round)
        }
    }

    @Test fun timedWarmupStartsWithWorkTimerAndFinishesWithoutRest() {
        val exercise = RoutineExercise(1, "Lunes", "Calentamiento", "Bicicleta", "-", "5 min", "-")
        val run = ExerciseFlow.start(exercise)
        assertEquals(ExerciseRun(ExercisePhase.WORK, 1, 300), run)
        assertEquals(ExercisePhase.DONE, ExerciseFlow.completeWork(exercise, run).phase)
    }
}
