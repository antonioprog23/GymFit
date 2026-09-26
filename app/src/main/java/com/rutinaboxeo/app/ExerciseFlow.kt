package com.rutinaboxeo.app

/** Fase actual de la máquina de estados de un ejercicio. */
enum class ExercisePhase {
    /** El ejercicio todavía no ha comenzado. */
    READY,
    /** El usuario está realizando una serie. */
    WORK,
    /** El usuario está en la pausa posterior a una serie. */
    REST,
    /** Todas las series y descansos han terminado. */
    DONE
}

/** Estado inmutable de ejecución de un ejercicio. */
data class ExerciseRun(
    /** Fase que controla la siguiente acción disponible. */
    val phase: ExercisePhase = ExercisePhase.READY,
    /** Serie actual, empezando siempre en uno. */
    val round: Int = 1,
    /** Segundos pendientes de trabajo o descanso. */
    val remainingSeconds: Int = 0
)

/** Máquina de estados pura que gobierna series, trabajo y descansos. */
object ExerciseFlow {
    /** Inicia la primera serie y carga su duración cuando el ejercicio es temporizado. */
    fun start(exercise: RoutineExercise): ExerciseRun =
        ExerciseRun(ExercisePhase.WORK, 1, ExerciseTiming.workSeconds(exercise.reps))

    /** Cierra una serie y abre el descanso o avanza directamente a la siguiente fase. */
    fun completeWork(exercise: RoutineExercise, run: ExerciseRun): ExerciseRun {
        require(run.phase == ExercisePhase.WORK)
        val rest = ExerciseTiming.restSeconds(exercise.rest)
        return if (rest > 0) run.copy(phase = ExercisePhase.REST, remainingSeconds = rest)
            else advance(exercise, run)
    }

    /** Cierra el descanso actual y avanza de serie o termina el ejercicio. */
    fun completeRest(exercise: RoutineExercise, run: ExerciseRun): ExerciseRun {
        require(run.phase == ExercisePhase.REST)
        return advance(exercise, run)
    }

    /** Crea la siguiente serie o marca el ejercicio como completado. */
    private fun advance(exercise: RoutineExercise, run: ExerciseRun): ExerciseRun =
        if (run.round >= ExerciseTiming.seriesCount(exercise.series))
            run.copy(phase = ExercisePhase.DONE, remainingSeconds = 0)
        else ExerciseRun(ExercisePhase.WORK, run.round + 1, ExerciseTiming.workSeconds(exercise.reps))
}
