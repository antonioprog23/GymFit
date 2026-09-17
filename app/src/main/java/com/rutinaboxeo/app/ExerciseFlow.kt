package com.rutinaboxeo.app

enum class ExercisePhase { READY, WORK, REST, DONE }

data class ExerciseRun(
    val phase: ExercisePhase = ExercisePhase.READY,
    val round: Int = 1,
    val remainingSeconds: Int = 0
)

object ExerciseFlow {
    fun start(exercise: RoutineExercise): ExerciseRun =
        ExerciseRun(ExercisePhase.WORK, 1, ExerciseTiming.workSeconds(exercise.reps))

    fun completeWork(exercise: RoutineExercise, run: ExerciseRun): ExerciseRun {
        require(run.phase == ExercisePhase.WORK)
        val rest = ExerciseTiming.restSeconds(exercise.rest)
        return if (rest > 0) run.copy(phase = ExercisePhase.REST, remainingSeconds = rest)
            else advance(exercise, run)
    }

    fun completeRest(exercise: RoutineExercise, run: ExerciseRun): ExerciseRun {
        require(run.phase == ExercisePhase.REST)
        return advance(exercise, run)
    }

    private fun advance(exercise: RoutineExercise, run: ExerciseRun): ExerciseRun =
        if (run.round >= ExerciseTiming.seriesCount(exercise.series))
            run.copy(phase = ExercisePhase.DONE, remainingSeconds = 0)
        else ExerciseRun(ExercisePhase.WORK, run.round + 1, ExerciseTiming.workSeconds(exercise.reps))
}
