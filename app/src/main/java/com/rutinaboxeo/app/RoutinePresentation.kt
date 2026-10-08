package com.rutinaboxeo.app

import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Datos ya agregados que la interfaz necesita para representar el progreso. */
data class RoutineProgressSummary(
    val completedSessions: Int,
    val completedExercises: Int,
    val totalExercises: Int,
    val groups: List<RoutineProgressEntry>,
    val history: List<RoutineProgressEntry>
) {
    /** Porcentaje entero y seguro incluso cuando el plan está vacío. */
    val percent: Int get() = if (totalExercises == 0) 0 else completedExercises * 100 / totalExercises
}

/** Fila de progreso reutilizada por el gráfico y el historial. */
data class RoutineProgressEntry(val label: String, val completed: Int, val total: Int) {
    val isComplete: Boolean get() = total > 0 && completed == total
}

/** Resultado de peso localizable sin mezclar la identidad persistida de los ejercicios. */
data class ExerciseWeightEntry(
    val exercise: String,
    val weight: String,
    val reps: String,
    val rir: String,
    val dateLabel: String,
    val routineId: String,
    val month: YearMonth,
    val extra: Boolean,
    val startedAt: String
)

/** Realizaciones de una fecha mensual preparadas para el calendario histórico. */
data class RoutineHistoryDay(val day: Int, val workouts: List<WorkoutRecord>) {
    val completed: Int get() = workouts.count { it.finishedAt.isNotBlank() }
    val pending: Int get() = workouts.size - completed
}

/** Mejor carga finalizada de un ejercicio, sin incluir la sesión todavía abierta. */
data class ExerciseBestResult(val weight: String, val reps: String, val rir: String, val dateLabel: String)

/** Horario legible de una realización y duración cuando ya ha finalizado. */
data class WorkoutTimeSummary(val range: String, val duration: String?)

/** Prepara fechas y agregados sin depender de Android ni de las vistas. */
object RoutinePresentation {
    private val locale = Locale("es", "ES")
    private val fullDate = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", locale)
    private val dayMonth = DateTimeFormatter.ofPattern("d 'de' MMMM", locale)
    private val shortDate = DateTimeFormatter.ofPattern("d MMM", locale)
    private val time = DateTimeFormatter.ofPattern("HH:mm", locale)

    /** Día inicial del calendario: hoy si pertenece al plan y, en otro caso, el primero. */
    fun initialDay(plan: RoutinePlan, today: LocalDate = LocalDate.now()): Int {
        val month = plan.month ?: return 1
        return (if (month == YearMonth.from(today)) today.dayOfMonth else 1).coerceIn(1, month.lengthOfMonth())
    }

    /** Filas de lunes a domingo, con huecos nulos antes y después de las fechas reales. */
    fun calendarRows(month: YearMonth): List<List<Int?>> {
        val leading = List(month.atDay(1).dayOfWeek.value - 1) { null }
        val days = (1..month.lengthOfMonth()).map<Int, Int?> { it }
        val cells = leading + days
        return (cells + List((7 - cells.size % 7) % 7) { null }).chunked(7)
    }

    /** Agrupa las realizaciones fechadas sin mezclar días ni inventar fechas antiguas. */
    fun historyDays(document: MonthlyRoutine): List<RoutineHistoryDay> = document.workouts
        .filter { it.dayOfMonth in 1..document.month.lengthOfMonth() }
        .groupBy { requireNotNull(it.dayOfMonth) }
        .toSortedMap()
        .map { (day, workouts) -> RoutineHistoryDay(day, workouts.sortedByDescending { it.startedAt }) }

    /** Selecciona de inicio el último día registrado del documento. */
    fun initialHistoryDay(document: MonthlyRoutine): Int? = document.workouts
        .filter { it.dayOfMonth in 1..document.month.lengthOfMonth() }
        .maxByOrNull { it.startedAt }
        ?.dayOfMonth

    /** Calcula la mejor carga histórica válida de un ejercicio. */
    fun bestResult(document: MonthlyRoutine, exerciseKey: String): ExerciseBestResult? = document.workouts
        .asSequence()
        .filter { it.finishedAt.isNotBlank() }
        .mapNotNull { record ->
            val result = record.results[exerciseKey] ?: return@mapNotNull null
            result.weight.trim().replace(',', '.').toDoubleOrNull()
                ?.let { weight -> Triple(record, result, weight) }
        }
        .maxByOrNull { it.third }
        ?.let { (record, result) -> ExerciseBestResult(result.weight, result.actualReps, result.rir,
            workoutDate(record.startedAt)) }

    /** Convierte las marcas temporales ISO en hora y duración fáciles de consultar. */
    fun workoutTime(record: WorkoutRecord): WorkoutTimeSummary {
        val start = runCatching { OffsetDateTime.parse(record.startedAt) }.getOrNull()
        val finish = runCatching { OffsetDateTime.parse(record.finishedAt) }.getOrNull()
        val range = when {
            start == null -> "Hora desconocida"
            finish == null -> "${start.format(time)} · en curso"
            else -> "${start.format(time)}–${finish.format(time)}"
        }
        val duration = if (start != null && finish != null) Duration.between(start, finish)
            .takeIf { !it.isNegative }
            ?.let { value ->
                val minutes = value.toMinutes()
                if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
            } else null
        return WorkoutTimeSummary(range, duration)
    }

    /** Resume el plan mensual o el histórico semanal antiguo mediante el mismo contrato. */
    fun progress(plan: RoutinePlan, values: Map<String, ExerciseProgress>): RoutineProgressSummary {
        val completedExercises = plan.exercises.count { values[it.key()]?.done == true }
        return if (plan.isMonthlyCalendar()) monthlyProgress(plan, values, completedExercises)
            else legacyProgress(plan, values, completedExercises)
    }

    /** Fecha larga para cabeceras y tarjetas de una sesión. */
    fun fullDate(date: LocalDate): String = date.format(fullDate).replaceFirstChar { it.titlecase(locale) }

    /** Ubicación legible de un ejercicio, conservando el formato anterior cuando no había fecha. */
    fun exerciseLocation(month: YearMonth, item: RoutineExercise): String = item.dayOfMonth
        ?.let { month.atDay(it).format(dayMonth) } ?: "Semana ${item.week} · ${item.day}"

    /** Ubicación legible de una realización, sin inventar fechas para registros antiguos. */
    fun workoutLocation(month: YearMonth, record: WorkoutRecord): String = record.dayOfMonth
        ?.let { month.atDay(it).format(dayMonth) } ?: "${record.day} · Semana ${record.week}"

    /** Título del período mensual en español. */
    fun monthTitle(month: YearMonth): String =
        "${month.month.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.titlecase(locale) }} ${month.year}"

    /** Reúne pesos de cualquier documento indicando siempre su procedencia y fecha real. */
    fun weightHistory(documents: List<MonthlyRoutine>): List<ExerciseWeightEntry> = documents.flatMap { document ->
        document.workouts.flatMap { record ->
            record.results.mapNotNull { (key, value) ->
                val weight = value.weight.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val exercise = document.plan.exercises.firstOrNull { it.key() == key }?.exercise ?: key
                ExerciseWeightEntry(exercise, weight, value.actualReps, value.rir,
                    workoutDate(record.startedAt), document.id, document.month, record.kind == "extra", record.startedAt)
            }
        }
    }.sortedByDescending { it.startedAt }

    private fun workoutDate(value: String): String = runCatching {
        OffsetDateTime.parse(value).toLocalDate().format(dayMonth)
    }.getOrDefault("Fecha desconocida")

    private fun monthlyProgress(plan: RoutinePlan, values: Map<String, ExerciseProgress>, completed: Int): RoutineProgressSummary {
        val month = requireNotNull(plan.month)
        val days = plan.dateDays()
        val entries = days.associateWith { day -> progressEntry(month.atDay(day).format(shortDate), plan.forDate(day), values) }
        val groups = days.chunked(7).map { group ->
            progressEntry("${group.first()}–${group.last()}", group.flatMap(plan::forDate), values)
        }
        return RoutineProgressSummary(
            completedSessions = entries.values.count { it.isComplete },
            completedExercises = completed,
            totalExercises = plan.exercises.size,
            groups = groups,
            history = days.mapNotNull(entries::get).filter { it.completed > 0 }.takeLast(12).asReversed()
        )
    }

    private fun legacyProgress(plan: RoutinePlan, values: Map<String, ExerciseProgress>, completed: Int): RoutineProgressSummary {
        val sessions = plan.weeks().flatMap { week -> plan.days(week).map { day -> week to day } }
        val entries = sessions.associateWith { (week, day) -> progressEntry("$day · S$week", plan.forDay(week, day), values) }
        val groups = plan.weeks().map { week -> progressEntry("S$week", plan.forWeek(week), values) }
        return RoutineProgressSummary(
            completedSessions = entries.values.count { it.isComplete },
            completedExercises = completed,
            totalExercises = plan.exercises.size,
            groups = groups,
            history = sessions.mapNotNull(entries::get).filter { it.completed > 0 }.takeLast(12).asReversed()
        )
    }

    private fun progressEntry(label: String, exercises: List<RoutineExercise>, values: Map<String, ExerciseProgress>) =
        RoutineProgressEntry(label, exercises.count { values[it.key()]?.done == true }, exercises.size)
}
