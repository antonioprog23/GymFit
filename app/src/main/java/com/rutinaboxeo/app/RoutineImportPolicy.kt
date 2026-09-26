package com.rutinaboxeo.app

/** Decide si una rutina guardada procede de una importación real del usuario. */
object RoutineImportPolicy {
    /** Preserva importaciones explícitas y descarta la antigua plantilla precargada. */
    fun shouldShowRoutine(importedStatus: Boolean?, stored: RoutinePlan?, bundled: RoutinePlan?): Boolean =
        when (importedStatus) {
            true -> stored != null
            false -> false
            null -> stored != null && bundled != null && !stored.hasSameExerciseStructure(bundled)
        }
}
