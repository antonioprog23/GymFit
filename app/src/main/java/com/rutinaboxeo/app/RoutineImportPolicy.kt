package com.rutinaboxeo.app

object RoutineImportPolicy {
    fun shouldShowRoutine(importedStatus: Boolean?, stored: RoutinePlan?, bundled: RoutinePlan?): Boolean =
        when (importedStatus) {
            true -> stored != null
            false -> false
            null -> stored != null && bundled != null && !stored.hasSameExerciseStructure(bundled)
        }
}
