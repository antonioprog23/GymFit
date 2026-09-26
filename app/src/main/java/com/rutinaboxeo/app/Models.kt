package com.rutinaboxeo.app

/** Ejercicio planificado con su ubicación, carga de trabajo y material de ayuda. */
data class RoutineExercise(
    /** Semana del plan a la que pertenece el ejercicio. */
    val week: Int,
    /** Día de la semana en el que debe realizarse. */
    val day: String,
    /** Bloque funcional, como fuerza, cardio o recuperación. */
    val block: String,
    /** Nombre visible del ejercicio. */
    val exercise: String,
    /** Número o descripción de las series. */
    val series: String,
    /** Repeticiones, distancia o duración del trabajo. */
    val reps: String,
    /** Descanso indicado entre series. */
    val rest: String,
    /** Observación opcional incluida en la plantilla. */
    val note: String = "",
    /** Posición estable del ejercicio dentro de la sesión. */
    val order: Int = 0,
    /** Instrucción específica escrita en la plantilla. */
    val instruction: String = "",
    /** Enlace HTTPS opcional a una demostración. */
    val videoUrl: String = "",
    /** Sustituciones válidas para el ejercicio principal. */
    val alternatives: List<ExerciseAlternative> = emptyList()
) {
    /** Genera la clave estable utilizada para asociar el progreso local. */
    fun key(): String = "$week|$day|$order|$exercise"
}

/** Alternativa de un ejercicio con instrucciones y vídeo opcionales. */
data class ExerciseAlternative(
    /** Nombre visible de la alternativa. */
    val title: String,
    /** Explicación breve para ejecutar la alternativa. */
    val instruction: String = "",
    /** Enlace HTTPS opcional a una demostración. */
    val videoUrl: String = ""
)

/** Metadatos que resumen una sesión diaria. */
data class SessionInfo(
    /** Título principal de la sesión. */
    val title: String,
    /** Objetivo o zona de trabajo de la sesión. */
    val focus: String,
    /** Consejo u observación complementaria. */
    val note: String = ""
)

/** Plan completo importado, con sesiones de tarde y rutina matinal. */
data class RoutinePlan(
    /** Ejercicios de todas las semanas del plan. */
    val exercises: List<RoutineExercise>,
    /** Información de sesión indexada por día de la semana. */
    val sessions: Map<String, SessionInfo> = emptyMap(),
    /** Pasos ordenados de la rutina de mañana. */
    val morningSteps: List<MorningStep> = emptyList(),
    /** Mes indicado en la hoja Periodo; vacío para plantillas antiguas. */
    val month: java.time.YearMonth? = null
) {
    /** Ejercicios indexados una vez para evitar filtros repetidos al dibujar pantallas. */
    private val exercisesBySession by lazy(LazyThreadSafetyMode.NONE) {
        exercises.groupBy { it.week to it.day }.mapValues { (_, values) -> values.sortedBy { it.order } }
    }

    /** Semanas disponibles, calculadas de forma diferida y ordenadas. */
    private val availableWeeks by lazy(LazyThreadSafetyMode.NONE) {
        exercises.asSequence().map { it.week }.distinct().sorted().toList()
    }

    /** Indica si otro plan conserva exactamente la estructura de ejercicios. */
    fun hasSameExerciseStructure(other: RoutinePlan): Boolean =
        exercises.size == other.exercises.size && exercises.zip(other.exercises).all { (first, second) ->
            first.week == second.week && first.day == second.day && first.block == second.block &&
                first.exercise == second.exercise && first.series == second.series && first.reps == second.reps &&
                first.rest == second.rest && first.order == second.order
        }

    /** Devuelve los metadatos del día o un resumen neutro si no existen. */
    fun session(day: String): SessionInfo = sessions[day] ?: SessionInfo(day, "")

    /** Devuelve las semanas presentes en el plan en orden ascendente. */
    fun weeks(): List<Int> = availableWeeks

    /** Devuelve los días con ejercicios de una semana en orden natural. */
    fun days(week: Int): List<String> = exercisesBySession.keys.asSequence()
        .filter { (candidateWeek, _) -> candidateWeek == week }
        .map { (_, day) -> day }
        .distinct()
        .sortedBy(::dayIndex)
        .toList()

    /** Devuelve los ejercicios de una sesión ya ordenados, sin volver a filtrar el plan. */
    fun forDay(week: Int, day: String): List<RoutineExercise> = exercisesBySession[week to day].orEmpty()

    /** Devuelve todos los ejercicios de una semana agrupados por el orden natural de sus días. */
    fun forWeek(week: Int): List<RoutineExercise> = days(week).flatMap { day -> forDay(week, day) }

    /** Convierte un día en su posición semanal para mantener un orden coherente. */
    private fun dayIndex(day: String): Int = DAY_ORDER.indexOf(day).let { if (it >= 0) it else Int.MAX_VALUE }

    private companion object {
        /** Orden canónico de los días utilizado por todas las vistas del plan. */
        val DAY_ORDER = listOf("Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo")
    }
}

/** Registro editable del resultado real de un ejercicio. */
data class ExerciseProgress(
    /** Peso utilizado por el usuario. */
    var weight: String = "",
    /** Repeticiones realmente completadas. */
    var actualReps: String = "",
    /** Repeticiones estimadas en reserva. */
    var rir: String = "",
    /** Nota personal asociada al ejercicio. */
    var userNote: String = "",
    /** Indica si el ejercicio se ha completado. */
    var done: Boolean = false
)
