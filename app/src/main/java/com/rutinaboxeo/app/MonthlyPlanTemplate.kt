package com.rutinaboxeo.app

import java.time.DayOfWeek
import java.time.YearMonth

/** Ajusta una planificación mensual al número exacto de días del período elegido. */
object MonthlyPlanTemplate {
    /** Conserva los días existentes, recorta los sobrantes y completa los nuevos con recuperación activa. */
    fun forMonth(source: RoutinePlan, month: YearMonth): RoutinePlan {
        require(source.isMonthlyCalendar()) { "La plantilla base debe estar organizada por días del mes" }
        val days = 1..month.lengthOfMonth()
        val recoveryInfo = source.calendar.values.lastOrNull { it.type.equals("Recuperación", true) }
            ?: CalendarDay(1, "Recuperación", "Movilidad y estiramientos",
                "Movilidad articular, flexibilidad y respiración", "Sesión suave")
        val recoveryExercises = source.exercises.filter { exercise ->
            exercise.dayOfMonth?.let(source::calendarDay)?.type.equals("Recuperación", true)
        }.groupBy { it.dayOfMonth }.values.lastOrNull().orEmpty()

        val calendar = days.associateWith { day ->
            (source.calendar[day] ?: recoveryInfo).copy(dayOfMonth = day)
        }
        val exercises = days.flatMap { day ->
            val existing = source.forDate(day)
            val selected = existing.ifEmpty { recoveryExercises.ifEmpty { listOf(defaultRecovery()) } }
            selected.mapIndexed { order, exercise ->
                exercise.copy(
                    week = (day - 1) / 7 + 1,
                    day = dayName(month.atDay(day).dayOfWeek),
                    dayOfMonth = day,
                    order = if (existing.isEmpty()) order else exercise.order
                )
            }
        }
        return source.copy(exercises = exercises, month = month, calendar = calendar)
    }

    /** Ejercicio seguro para meses con un día que no exista en la plantilla base. */
    private fun defaultRecovery() = RoutineExercise(
        week = 1,
        day = "",
        block = "Recuperación",
        exercise = "Movilidad y estiramientos guiados",
        series = "1",
        reps = "20 min",
        rest = "-",
        instruction = "Movimientos suaves de cuerpo completo, sin dolor, y respiración controlada."
    )

    /** Nombre español estable para una fecha concreta. */
    private fun dayName(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "Lunes"
        DayOfWeek.TUESDAY -> "Martes"
        DayOfWeek.WEDNESDAY -> "Miércoles"
        DayOfWeek.THURSDAY -> "Jueves"
        DayOfWeek.FRIDAY -> "Viernes"
        DayOfWeek.SATURDAY -> "Sábado"
        DayOfWeek.SUNDAY -> "Domingo"
    }
}
