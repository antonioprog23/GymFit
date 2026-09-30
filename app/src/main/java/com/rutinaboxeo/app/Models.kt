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
    val alternatives: List<ExerciseAlternative> = emptyList(),
    /** Día real del mes para las plantillas mensuales; vacío en planes antiguos por semanas. */
    val dayOfMonth: Int? = null
) {
    /** Genera la clave estable utilizada para asociar el progreso local. */
    fun key(): String = dayOfMonth?.let { "date|$it|$order|$exercise" } ?: "$week|$day|$order|$exercise"
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

/** Metadatos de una fecha concreta dentro de una rutina mensual. */
data class CalendarDay(
    /** Número de día dentro del mes. */
    val dayOfMonth: Int,
    /** Tipo de jornada: entrenamiento, recuperación o descanso. */
    val type: String,
    /** Título principal de la sesión. */
    val title: String,
    /** Objetivo o zona de trabajo. */
    val focus: String = "",
    /** Consejo u observación de ese día. */
    val note: String = "",
    /** Fase de carga indicada en la plantilla. */
    val phase: String = "",
    /** Intensidad prevista. */
    val intensity: String = "",
    /** Regla de ejecución de la fase. */
    val rule: String = ""
)

/** Plan completo importado, con sesiones de tarde y rutina matinal. */
data class RoutinePlan(
    /** Ejercicios del plan mensual o, únicamente en datos históricos, del formato semanal anterior. */
    val exercises: List<RoutineExercise>,
    /** Información de sesión indexada por día de la semana. */
    val sessions: Map<String, SessionInfo> = emptyMap(),
    /** Pasos ordenados de la rutina de mañana. */
    val morningSteps: List<MorningStep> = emptyList(),
    /** Mes indicado en la hoja Periodo; vacío solo en datos históricos antiguos. */
    val month: java.time.YearMonth? = null,
    /** Metadatos indexados por día real del mes; vacío solo en datos históricos antiguos. */
    val calendar: Map<Int, CalendarDay> = emptyMap()
) {
    /** Ejercicios indexados una vez para evitar filtros repetidos al dibujar pantallas. */
    private val exercisesBySession by lazy(LazyThreadSafetyMode.NONE) {
        exercises.groupBy { it.week to it.day }.mapValues { (_, values) -> values.sortedBy { it.order } }
    }

    /** Semanas disponibles, calculadas de forma diferida y ordenadas. */
    private val availableWeeks by lazy(LazyThreadSafetyMode.NONE) {
        exercises.asSequence().map { it.week }.distinct().sorted().toList()
    }

    /** Ejercicios mensuales indexados por su fecha concreta. */
    private val exercisesByDate by lazy(LazyThreadSafetyMode.NONE) {
        exercises.filter { it.dayOfMonth != null }.groupBy { it.dayOfMonth!! }
            .mapValues { (_, values) -> values.sortedBy { it.order } }
    }

    /** Indica si otro plan conserva exactamente la estructura de ejercicios. */
    fun hasSameExerciseStructure(other: RoutinePlan): Boolean =
        exercises.size == other.exercises.size && exercises.zip(other.exercises).all { (first, second) ->
            first.week == second.week && first.day == second.day && first.dayOfMonth == second.dayOfMonth && first.block == second.block &&
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

    /** Indica que el plan utiliza fechas mensuales en lugar de hojas semanales. */
    fun isMonthlyCalendar(): Boolean = calendar.isNotEmpty() || exercisesByDate.isNotEmpty()

    /** Devuelve todos los días definidos por la planificación mensual. */
    fun dateDays(): List<Int> = month?.let { (1..it.lengthOfMonth()).toList() }
        ?: (calendar.keys + exercisesByDate.keys).distinct().sorted()

    /** Devuelve los ejercicios asociados a un día real del mes. */
    fun forDate(dayOfMonth: Int): List<RoutineExercise> = exercisesByDate[dayOfMonth].orEmpty()

    /** Devuelve los metadatos de una fecha o construye un resumen seguro desde sus ejercicios. */
    fun calendarDay(dayOfMonth: Int): CalendarDay = calendar[dayOfMonth] ?: CalendarDay(
        dayOfMonth = dayOfMonth,
        type = if (forDate(dayOfMonth).any { it.block.equals("Descanso", true) }) "Descanso" else "Entrenamiento",
        title = forDate(dayOfMonth).firstOrNull()?.block ?: "Día $dayOfMonth"
    )

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
