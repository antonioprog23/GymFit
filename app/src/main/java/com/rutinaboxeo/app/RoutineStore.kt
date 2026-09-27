package com.rutinaboxeo.app

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** Gestiona la persistencia local de rutina, progreso y preferencias de navegación. */
object RoutineStore {
    /** Accede a los documentos mensuales privados sin mezclar sus progresos. */
    fun monthly(context: Context): MonthlyRepository = MonthlyRepository(File(context.filesDir, "routines"))

    /** Guarda el plan completo y lo marca como una importación explícita. */
    fun saveRoutine(context: Context, plan: RoutinePlan) {
        preferences(context).edit()
            .putString(ROUTINE, RoutineJson.encodePlan(plan))
            .putBoolean(IMPORTED, true)
            .apply()
    }

    /** Devuelve el estado de importación o `null` para instalaciones de versiones antiguas. */
    fun importedStatus(context: Context): Boolean? {
        val repository = monthly(context)
        if (repository.hasMonthlyState()) return repository.active() != null
        return preferences(context).let { prefs ->
            if (prefs.contains(IMPORTED)) prefs.getBoolean(IMPORTED, false) else null
        }
    }

    /** Registra si existe una rutina importada que deba mostrarse. */
    fun setImported(context: Context, imported: Boolean) {
        preferences(context).edit().putBoolean(IMPORTED, imported).apply()
    }

    /** Recupera el plan guardado o `null` cuando falta o está dañado. */
    fun loadRoutine(context: Context): RoutinePlan? {
        val repository = monthly(context)
        if (repository.hasMonthlyState()) return repository.active()?.plan
        return preferences(context).getString(ROUTINE, null)?.let(RoutineJson::decodePlan)
    }

    /** Recupera todo el progreso; devuelve un mapa vacío si los datos no son válidos. */
    fun loadProgress(context: Context): MutableMap<String, ExerciseProgress> {
        val repository = monthly(context)
        if (repository.hasMonthlyState()) return repository.active()?.progress ?: mutableMapOf()
        return RoutineJson.decodeProgress(preferences(context).getString(PROGRESS, EMPTY_JSON).orEmpty())
    }

    /** Guarda el progreso en el JSON activo o en preferencias si aún falta migrar. */
    fun saveProgress(context: Context, map: Map<String, ExerciseProgress>) {
        if (monthly(context).hasMonthlyState() && monthly(context).active() == null) return
        if (monthly(context).active() != null) {
            monthly(context).updateProgress(map)
            return
        }
        preferences(context).edit().putString(PROGRESS, RoutineJson.encodeProgress(map)).apply()
    }

    /** Devuelve la última semana elegida o la primera si todavía no se guardó. */
    fun activeWeek(context: Context): Int = preferences(context).getInt(ACTIVE_WEEK, FIRST_WEEK)

    /** Persiste la semana seleccionada para restaurarla en el siguiente inicio. */
    fun saveActiveWeek(context: Context, week: Int) {
        preferences(context).edit().putInt(ACTIVE_WEEK, week).apply()
    }

    /** Elimina únicamente registros del usuario y conserva la rutina importada. */
    fun clearProgress(context: Context) {
        if (monthly(context).active() != null) {
            monthly(context).clearProgress()
            return
        }
        preferences(context).edit().remove(PROGRESS).apply()
    }

    /** Centraliza el acceso al único archivo de preferencias de la aplicación. */
    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** Nombre del archivo privado de preferencias. */
    private const val PREFERENCES = "routine_store"

    /** Clave del JSON con el plan importado. */
    private const val ROUTINE = "routine_json"

    /** Clave del JSON con registros y ejercicios completados. */
    private const val PROGRESS = "progress_json"

    /** Clave de la última semana seleccionada. */
    private const val ACTIVE_WEEK = "active_week"

    /** Clave que distingue una importación de la antigua plantilla incluida. */
    private const val IMPORTED = "routine_imported"

    /** Objeto JSON vacío utilizado como valor seguro por defecto. */
    private const val EMPTY_JSON = "{}"

    /** Primera semana seleccionada antes de importar preferencias. */
    private const val FIRST_WEEK = 1
}
