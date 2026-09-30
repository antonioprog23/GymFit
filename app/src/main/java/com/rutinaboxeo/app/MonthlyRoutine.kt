package com.rutinaboxeo.app

import java.time.YearMonth
import java.util.Locale

/** Documento mensual independiente que reúne planificación y resultados. */
data class MonthlyRoutine(
    /** Nombre único del archivo privado, sin extensión. */
    val id: String,
    /** Mes y año asignados por el usuario. */
    val month: YearMonth,
    /** Instante de importación ISO; no representa la fecha de entrenamiento. */
    val importedAt: String,
    /** Planificación original del Excel. */
    val plan: RoutinePlan,
    /** Últimos resultados por ejercicio para las pantallas de progreso. */
    val progress: MutableMap<String, ExerciseProgress> = mutableMapOf(),
    /** Realizaciones independientes de los entrenamientos. */
    val workouts: MutableList<WorkoutRecord> = mutableListOf(),
    /** Indica que los datos proceden de la versión anterior. */
    val migrated: Boolean = false
) {
    /** Nombre legible del período y de su importación, sin confundir meses repetidos. */
    fun label(): String = "${month.month.getDisplayName(java.time.format.TextStyle.FULL, Locale("es", "ES"))} ${month.year} · $id"
}

/** Realización de una sesión; las fechas vacías identifican registros antiguos desconocidos. */
data class WorkoutRecord(
    /** Identificador estable de esta realización. */
    val id: String,
    /** Semana de la planificación; cero para mañana o datos heredados. */
    val week: Int,
    /** Día planificado o descripción de la sesión. */
    val day: String,
    /** Fecha y hora real de inicio, con zona horaria. */
    val startedAt: String,
    /** Fecha y hora de finalización; vacía mientras sigue abierta. */
    var finishedAt: String = "",
    /** Resultados de esta realización, independientes de repeticiones posteriores. */
    val results: MutableMap<String, ExerciseProgress> = mutableMapOf(),
    /** Distingue tarde, mañana y datos anteriores sin fecha. */
    val kind: String = "tarde",
    /** Día real del mes; vacío para realizaciones antiguas organizadas por semanas. */
    val dayOfMonth: Int? = null
)
