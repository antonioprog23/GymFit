package com.rutinaboxeo.app

import java.time.YearMonth

/** Grupo visual que mantiene juntas todas las importaciones de un mismo período. */
data class RoutineArchiveMonth(
    val month: YearMonth,
    val primary: MonthlyRoutine?,
    val routines: List<MonthlyRoutine>
) {
    /** Importaciones del período distintas de la principal seleccionada. */
    val alternatives: List<MonthlyRoutine> get() = routines.filter { it.id != primary?.id }
}

/** Prepara el archivo mensual sin introducir reglas de navegación en la interfaz. */
object RoutineArchivePresentation {
    /** Años disponibles, mostrando primero el más reciente. */
    fun years(snapshot: MonthlyRoutineSnapshot): List<Int> =
        snapshot.documents.map { it.month.year }.distinct().sortedDescending()

    /** Meses e importaciones del año solicitado, del más reciente al más antiguo. */
    fun months(snapshot: MonthlyRoutineSnapshot, year: Int): List<RoutineArchiveMonth> =
        snapshot.documents.filter { it.month.year == year }
            .groupBy { it.month }
            .toSortedMap(compareByDescending { it })
            .map { (month, documents) ->
                RoutineArchiveMonth(month, snapshot.primary(month),
                    documents.sortedWith(compareByDescending<MonthlyRoutine> { it.id == snapshot.primary(month)?.id }
                        .thenByDescending { it.importedAt }))
            }

    /** Construye las doce celdas del calendario anual, incluso para meses todavía vacíos. */
    fun yearCalendar(snapshot: MonthlyRoutineSnapshot, year: Int): List<RoutineArchiveMonth> {
        val stored = months(snapshot, year).associateBy { it.month }
        return (1..12).map { month ->
            val period = YearMonth.of(year, month)
            stored[period] ?: RoutineArchiveMonth(period, null, emptyList())
        }
    }
}
